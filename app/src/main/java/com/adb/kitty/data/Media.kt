package com.adb.kitty.data

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.annotation.Keep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

@Keep
@SuppressLint("WrongConstant")
suspend fun extractAudioToMusicDirectory(
    context: Context,
    videoUri: Uri,
    baseFileName: String,
    onProgress: ((Float) -> Unit)? = null
): Uri? = withContext(Dispatchers.IO) {

    val extractor = MediaExtractor()
    var muxer: MediaMuxer? = null
    var pfd: ParcelFileDescriptor? = null
    var tempFile: File? = null
    var audioOutputUri: Uri? = null
    val contentResolver = context.contentResolver

    try {
        extractor.setDataSource(context, videoUri, null)

        var audioTrackIndex = -1
        var audioFormat: MediaFormat? = null

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                audioFormat = format
                break
            }
        }

        if (audioTrackIndex == -1 || audioFormat == null) return@withContext null
        extractor.selectTrack(audioTrackIndex)

        val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: ""
        
        val (outputFormat, fileExtension, mediaStoreMime) = when {
            mime.contains("aac", ignoreCase = true) -> {
                Triple(MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, ".m4a", "audio/mp4")
            }
            (mime.contains("opus", ignoreCase = true) || mime.contains("vorbis", ignoreCase = true)) 
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                Triple(MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG, ".ogg", "audio/ogg")
            }
            else -> {
                Triple(MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, ".m4a", "audio/mp4")
            }
        }

        val finalFileName = "$baseFileName$fileExtension"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, finalFileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mediaStoreMime)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/NekoExtractor")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        audioOutputUri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: return@withContext null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            pfd = contentResolver.openFileDescriptor(audioOutputUri, "rw") ?: return@withContext null
            muxer = MediaMuxer(pfd.fileDescriptor, outputFormat)
        } else {
            tempFile = File(context.cacheDir, "temp_extract_${System.currentTimeMillis()}$fileExtension")
            muxer = MediaMuxer(tempFile.absolutePath, outputFormat)
        }

        val writeTrackIndex = muxer.addTrack(audioFormat)
        muxer.start()

        val maxBufferSize = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } else {
            512 * 1024
        }
        val buffer = ByteBuffer.allocate(maxBufferSize)
        val bufferInfo = MediaCodec.BufferInfo()
        val durationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
            audioFormat.getLong(MediaFormat.KEY_DURATION)
        } else {
            1L
        }

        while (true) {
            buffer.clear()
            bufferInfo.offset = 0
            bufferInfo.size = extractor.readSampleData(buffer, 0)

            if (bufferInfo.size < 0) {
                bufferInfo.size = 0
                break
            }

            bufferInfo.presentationTimeUs = extractor.sampleTime
            bufferInfo.flags = extractor.sampleFlags

            muxer.writeSampleData(writeTrackIndex, buffer, bufferInfo)
            
            if (durationUs > 0) {
                onProgress?.invoke((bufferInfo.presentationTimeUs.toFloat() / durationUs.toFloat()).coerceIn(0f, 1f))
            }
            extractor.advance()
        }

        muxer.stop()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && tempFile != null) {
            contentResolver.openOutputStream(audioOutputUri)?.use { outputStream ->
                FileInputStream(tempFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(audioOutputUri, contentValues, null, null)
        }

        return@withContext audioOutputUri

    } catch (e: Exception) {
        e.printStackTrace()
        audioOutputUri?.let { uri ->
            try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}
        }
        return@withContext null
    } finally {
        try { extractor.release() } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
        try { tempFile?.delete() } catch (_: Exception) {}
    }
}

package com.adb.kitty.compose.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import androidx.annotation.Keep

/**
 * 从选取的视频 Uri 中提取音频，并安全保存到系统公共音乐（Music）目录下
 * [适用环境]: minSdk >= 29 (Android 10+)
 */
@Keep
@Suppress("WrongConstant")
suspend fun extractAudioToMusicDirectory(
    context: Context,
    videoUri: Uri,
    baseFileName: String,
    onProgress: ((Float) -> Unit)? = null
): Uri? = withContext(Dispatchers.IO) {

    val extractor = MediaExtractor()
    var muxer: MediaMuxer? = null
    var pfd: ParcelFileDescriptor? = null
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
            mime.contains("opus", ignoreCase = true) || mime.contains("vorbis", ignoreCase = true) -> {
                Triple(MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG, ".ogg", "audio/ogg")
            }
            else -> {
                Triple(MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, ".m4a", "audio/mp4")
            }
        }

        val finalFileName = "$baseFileName${fileExtension}"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, finalFileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mediaStoreMime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/NekoExtractor")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        audioOutputUri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: return@withContext null

        pfd = contentResolver.openFileDescriptor(audioOutputUri, "rw") ?: return@withContext null
        
        muxer = MediaMuxer(pfd.fileDescriptor, outputFormat)
        val writeTrackIndex = muxer.addTrack(audioFormat)
        muxer.start()

        val maxBufferSize = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } else {
            512 * 1024
        }
        val buffer = ByteBuffer.allocate(maxBufferSize)
        val bufferInfo = MediaCodec.BufferInfo()
        val durationUs = audioFormat.run { if (containsKey(MediaFormat.KEY_DURATION)) getLong(MediaFormat.KEY_DURATION) else 1L }

        while (true) {
            bufferInfo.offset = 0
            bufferInfo.size = extractor.readSampleData(buffer, 0)
            
            if (bufferInfo.size < 0) {
                bufferInfo.size = 0
                break
            }
            
            bufferInfo.presentationTimeUs = extractor.sampleTime
            bufferInfo.flags = extractor.sampleFlags

            muxer.writeSampleData(writeTrackIndex, buffer, bufferInfo)
            onProgress?.invoke(bufferInfo.presentationTimeUs.toFloat() / durationUs.toFloat())
            extractor.advance()
        }

        muxer.stop()

        contentValues.clear()
        contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
        contentResolver.update(audioOutputUri, contentValues, null, null)

        return@withContext audioOutputUri

    } catch (e: Exception) {
        e.printStackTrace()
        audioOutputUri?.let { uri ->
            try { contentResolver.delete(uri, null, null) } catch (ex: Exception) {}
        }
        return@withContext null
    } finally {
        try { extractor.release() } catch (e: Exception) {}
        try { muxer?.release() } catch (e: Exception) {}
        try { pfd?.close() } catch (e: Exception) {}
    }
}

package com.adb.kitty.compose.data

import net.sf.sevenzipjbinding.*
import net.sf.sevenzipjbinding.impl.OutItemFactory
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import net.sf.sevenzipjbinding.impl.RandomAccessFileOutStream
import net.sf.sevenzipjbinding.impl.VolumedArchiveInStream
import java.io.*
import androidx.annotation.Keep

@Keep
object OmniCompressUtils {

    private const val BUFFER_SIZE = 65536
    private var isSevenZipInitialized = false

    fun ensureInitialized() {
        if (!isSevenZipInitialized) {
            try {
                System.setProperty("sevenzip.jbinding.use.platform.jar", "false")
                SevenZip.initSevenZipFromPlatformJAR()
                isSevenZipInitialized = true
            } catch (e: Exception) {
                e.printStackTrace()
                throw RuntimeException("7-Zip Native engine failed to link: ${e.message}")
            }
        }
    }

    fun compress(
        format: String, 
        source: File, 
        output: File,
        password: String? = null,
        onStatusUpdate: ((fileName: String, status: String) -> Unit)? = null
    ): Boolean {
        if (!source.exists()) return false
        ensureInitialized()

        val fileList = mutableListOf<File>()
        if (source.isDirectory) {
            collectFilesRecursive(source, fileList)
        } else {
            fileList.add(source)
        }

        val normFormat = format.lowercase().trim()
        
        return when (normFormat) {
            "7z" -> compress7z(source, fileList, output, password, onStatusUpdate)
            "zip" -> compressZip(source, fileList, output, password, onStatusUpdate)
            "tar" -> compressTar(source, fileList, output, onStatusUpdate)
            else -> compressGeneric(normFormat, source, fileList, output, onStatusUpdate)
        }
    }

    private fun compress7z(
        baseDir: File,
        fileList: List<File>,
        output: File,
        password: String?, 
        onStatusUpdate: ((String, String) -> Unit)?
    ): Boolean {
        var raf: RandomAccessFile? = null
        var outArchive: IOutCreateArchive7z? = null 
        return try {
            if (output.exists()) output.delete()
            
            raf = RandomAccessFile(output, "rw")
            raf.setLength(0)
            val outStream = RandomAccessFileOutStream(raf)
            
            outArchive = SevenZip.openOutArchive7z()
            applyMultiThreading(outArchive)
            
            if (!password.isNullOrEmpty()) {
                outArchive.setHeaderEncryption(true)
            }
            
            open class Base7zCallback : IOutCreateCallback<IOutItem7z> {
                var currentFileIndex: Int = -1
                override fun setTotal(total: Long) {}
                override fun setCompleted(complete: Long) {}

                override fun getItemInformation(index: Int, outItemFactory: OutItemFactory<IOutItem7z>): IOutItem7z {
                    val file = fileList[index]
                    val outItem = outItemFactory.createOutItem()
                    outItem.setPropertyPath(getRelativePath(baseDir, file))
                    outItem.setPropertyIsDir(file.isDirectory)
                    if (!file.isDirectory) outItem.setDataSize(file.length())
                    return outItem
                }

                override fun getStream(index: Int): ISequentialInStream? {
                    currentFileIndex = index
                    val file = fileList[index]
                    onStatusUpdate?.invoke(file.name, "START")
                    if (file.isDirectory) {
                        onStatusUpdate?.invoke(file.name, "SUCCESS")
                        return null
                    }
                    return SafeSequentialInStream(file) 
                }

                override fun setOperationResult(result: Boolean) {
                    notifyResult(currentFileIndex, fileList, result, onStatusUpdate)
                }
            }

            class Crypto7zCallback(private val pass: String) : Base7zCallback(), ICryptoGetTextPassword {
                override fun cryptoGetTextPassword(): String = pass
            }

            val callback = if (!password.isNullOrEmpty()) {
                Crypto7zCallback(password)
            } else {
                Base7zCallback()
            }

            outArchive.createArchive(outStream, fileList.size, callback)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            outArchive?.close()
            raf?.close()
        }
    }

    private fun compressZip(
        baseDir: File,
        fileList: List<File>,
        output: File,
        password: String?, 
        onStatusUpdate: ((String, String) -> Unit)?
    ): Boolean {
        var raf: RandomAccessFile? = null
        var outArchive: IOutCreateArchiveZip? = null
        return try {
            if (output.exists()) output.delete()
            
            raf = RandomAccessFile(output, "rw")
            raf.setLength(0) 
            val outStream = RandomAccessFileOutStream(raf)
            
            outArchive = SevenZip.openOutArchiveZip()
            outArchive.setLevel(5)
            applyMultiThreading(outArchive)
            
            open class BaseZipCallback : IOutCreateCallback<IOutItemZip> {
                var currentFileIndex: Int = -1
                override fun setTotal(total: Long) {}
                override fun setCompleted(complete: Long) {}

                override fun getItemInformation(index: Int, outItemFactory: OutItemFactory<IOutItemZip>): IOutItemZip {
                    val file = fileList[index]
                    val outItem = outItemFactory.createOutItem()
                    
                    outItem.setPropertyPath(getRelativePath(baseDir, file))
                    outItem.setPropertyIsDir(file.isDirectory)
                    
                    if (!file.isDirectory) {
                        outItem.setDataSize(file.length())
                    }
                    return outItem
                }

                override fun getStream(index: Int): ISequentialInStream? {
                    currentFileIndex = index
                    val file = fileList[index]
                    onStatusUpdate?.invoke(file.name, "START")
                    if (file.isDirectory) {
                        onStatusUpdate?.invoke(file.name, "SUCCESS")
                        return null
                    }
                    return SafeSequentialInStream(file) 
                }

                override fun setOperationResult(result: Boolean) {
                    notifyResult(currentFileIndex, fileList, result, onStatusUpdate)
                }
            }

            class CryptoZipCallback(private val pass: String) : BaseZipCallback(), ICryptoGetTextPassword {
                override fun cryptoGetTextPassword(): String = pass
            }

            val callback = if (!password.isNullOrEmpty()) {
                CryptoZipCallback(password)
            } else {
                BaseZipCallback()
            }

            outArchive.createArchive(outStream, fileList.size, callback)
            true
        } catch (e: Throwable) {
            Log.e("7ZipCore", "ZIP压缩底层发生异常: ${e.message}", e)
            false
        } finally {
            outArchive?.close()
            raf?.close()
        }
    }

    private fun compressTar(
        baseDir: File,
        fileList: List<File>,
        output: File,
        onStatusUpdate: ((String, String) -> Unit)?
    ): Boolean {
        var raf: RandomAccessFile? = null
        var outArchive: IOutArchive<IOutItemTar>? = null
        return try {
            if (output.exists()) output.delete()
            raf = RandomAccessFile(output, "rw")
            val outStream = RandomAccessFileOutStream(raf)
            
            @Suppress("UNCHECKED_CAST")
            outArchive = SevenZip.openOutArchive(ArchiveFormat.TAR) as IOutArchive<IOutItemTar>
            
            applyMultiThreading(outArchive)
            
            val callback = object : IOutCreateCallback<IOutItemTar> {
                private var currentFileIndex: Int = -1
                override fun setTotal(total: Long) {}
                override fun setCompleted(complete: Long) {}

                override fun getItemInformation(index: Int, outItemFactory: OutItemFactory<IOutItemTar>): IOutItemTar {
                    val file = fileList[index]
                    val outItem = outItemFactory.createOutItem()
                    outItem.setPropertyPath(getRelativePath(baseDir, file))
                    outItem.setPropertyIsDir(file.isDirectory)
                    if (!file.isDirectory) outItem.setDataSize(file.length())
                    return outItem
                }

                override fun getStream(index: Int): ISequentialInStream? {
                    currentFileIndex = index
                    val file = fileList[index]
                    onStatusUpdate?.invoke(file.name, "START")
                    if (file.isDirectory) {
                        onStatusUpdate?.invoke(file.name, "SUCCESS")
                        return null
                    }
                    return SafeSequentialInStream(file)
                }

                override fun setOperationResult(result: Boolean) {
                    notifyResult(currentFileIndex, fileList, result, onStatusUpdate)
                }
            }

            outArchive.updateItems(outStream, fileList.size, callback)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            outArchive?.close()
            raf?.close()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun compressGeneric(
        format: String, baseDir: File, fileList: List<File>, output: File, onStatusUpdate: ((String, String) -> Unit)?
    ): Boolean {
        var raf: RandomAccessFile? = null
        var outArchive: IOutArchive<IOutItemAllFormats>? = null
        return try {
            val archiveFormat = when (format) {
                "gzip", "gz" -> ArchiveFormat.GZIP
                "bzip2", "bz2" -> ArchiveFormat.BZIP2
                else -> throw IllegalArgumentException("Unsupported format: $format")
            }
            if (output.exists()) output.delete()
            raf = RandomAccessFile(output, "rw")
            val outStream = RandomAccessFileOutStream(raf)
            outArchive = SevenZip.openOutArchive(archiveFormat) as IOutArchive<IOutItemAllFormats>
            
            applyMultiThreading(outArchive)
            
            val callback = object : IOutCreateCallback<IOutItemAllFormats> {
                private var currentFileIndex: Int = -1
                override fun setTotal(total: Long) {}
                override fun setCompleted(complete: Long) {}
                override fun getItemInformation(index: Int, outItemFactory: OutItemFactory<IOutItemAllFormats>): IOutItemAllFormats {
                    val file = fileList[index]
                    val outItem = outItemFactory.createOutItem()
                    outItem.setPropertyPath(getRelativePath(baseDir, file))
                    outItem.setPropertyIsDir(file.isDirectory)
                    if (!file.isDirectory) outItem.setDataSize(file.length())
                    return outItem
                }
                override fun getStream(index: Int): ISequentialInStream? {
                    currentFileIndex = index
                    val file = fileList[index]
                    onStatusUpdate?.invoke(file.name, "START")
                    if (file.isDirectory) return null
                    return SafeSequentialInStream(file)
                }
                override fun setOperationResult(result: Boolean) {
                    notifyResult(currentFileIndex, fileList, result, onStatusUpdate)
                }
            }
            outArchive.updateItems(outStream, fileList.size, callback)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            outArchive?.close()
            raf?.close()
        }
    }

    fun decompress(
        sourceFile: File, 
        outputTarget: File, 
        password: String? = null,
        onProgress: ((currentFile: String, progressPercent: Int) -> Unit)? = null
    ): Boolean {
        if (!sourceFile.exists()) return false
        ensureInitialized()

        val volumeCallback = SmartVolumeCallback(sourceFile)
        var inArchive: IInArchive? = null
        
        return try {
            val inStream = if (sourceFile.name.endsWith(".001")) {
                VolumedArchiveInStream(sourceFile.absolutePath, volumeCallback)
            } else {
                volumeCallback.getStream(sourceFile.name) ?: throw FileNotFoundException("无法加载初始卷")
            }

            val archive = SevenZip.openInArchive(null, inStream, volumeCallback)
            inArchive = archive

            if (archive != null) {
                if (!outputTarget.exists()) outputTarget.mkdirs()
                
                val callback = ExtractArchiveCallback(sourceFile, archive, outputTarget, password, onProgress)
                archive.extract(null, false, callback)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            inArchive?.close()
            volumeCallback.close()
        }
    }

    private fun applyMultiThreading(outArchive: Any) {
        if (outArchive is IOutFeatureSetMultithreading) {
            val cores = Runtime.getRuntime().availableProcessors()
            outArchive.setThreadCount(if (cores > 2) cores - 1 else 1)
        }
    }

    private fun collectFilesRecursive(dir: File, fileList: MutableList<File>) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            fileList.add(file)
            if (file.isDirectory) collectFilesRecursive(file, fileList)
        }
    }

    private fun getRelativePath(baseDir: File, file: File): String {
        return if (baseDir.isDirectory) {
            file.relativeTo(baseDir).path.replace("\\", "/")
        } else {
            file.name
        }
    }

    private class SafeSequentialInStream(file: File) : ISequentialInStream, Closeable {
        private var fis: FileInputStream? = FileInputStream(file)

        override fun read(data: ByteArray): Int {
            val stream = fis ?: return 0
            return try {
                val read = stream.read(data)
                if (read == -1) {
                    close()
                    return 0
                }
                read
            } catch (e: Exception) {
                close()
                0
            }
        }

        override fun close() {
            fis?.close()
            fis = null
        }
    }

    private fun notifyResult(index: Int, list: List<File>, res: Boolean, cb: ((String, String) -> Unit)?) {
        if (index in list.indices) {
            val status = if (res) "SUCCESS" else "FAILED"
            cb?.invoke(list[index].name, status)
        }
    }

    private class SmartVolumeCallback(private val firstVolume: File) : 
        IArchiveOpenCallback, IArchiveOpenVolumeCallback, Closeable {
        
        private val openedFiles = mutableMapOf<String, RandomAccessFile>()
        private var lastName: String = firstVolume.name

        override fun setTotal(files: Long?, bytes: Long?) {}
        override fun setCompleted(files: Long?, bytes: Long?) {}

        override fun getProperty(propID: PropID): Any? {
            return if (propID == PropID.NAME) lastName else null
        }

        override fun getStream(filename: String): IInStream? {
            val fileToOpen = if (filename == firstVolume.name) {
                firstVolume
            } else {
                File(firstVolume.parentFile, filename)
            }
            if (!fileToOpen.exists()) return null

            return try {
                val raf = openedFiles.getOrPut(filename) { RandomAccessFile(fileToOpen, "r") }
                raf.seek(0)
                lastName = filename
                RandomAccessFileInStream(raf)
            } catch (e: Exception) {
                null
            }
        }

        override fun close() {
            openedFiles.values.forEach { runCatching { it.close() } }
            openedFiles.clear()
        }
    }

    private class ExtractArchiveCallback(
        private val sourceFile: File, 
        private val inArchive: IInArchive, 
        private val outputDir: File, 
        private val password: String?,
        private val onProgress: ((String, Int) -> Unit)?
    ) : IArchiveExtractCallback, ICryptoGetTextPassword {
        private var fos: FileOutputStream? = null
        private var bos: BufferedOutputStream? = null
        private var currentFile: File? = null
        private var currentFileName: String = ""
        private var totalBytes: Long = 0

        override fun setTotal(total: Long) {
            this.totalBytes = total
        }

        override fun setCompleted(complete: Long) {
            if (totalBytes > 0) {
                val percent = ((complete * 100) / totalBytes).toInt()
                onProgress?.invoke(currentFileName, percent.coerceIn(0, 100))
            }
        }

        override fun prepareOperation(askMode: ExtractAskMode) {}

        override fun getStream(index: Int, askMode: ExtractAskMode): ISequentialOutStream? {
            if (askMode != ExtractAskMode.EXTRACT) return null
            val isFolder = inArchive.getProperty(index, PropID.IS_FOLDER) as? Boolean ?: false
            var path = inArchive.getProperty(index, PropID.PATH) as? String
            if (path.isNullOrEmpty()) path = sourceFile.nameWithoutExtension

            currentFileName = File(path).name
            val targetFile = File(outputDir, path)
            currentFile = targetFile

            if (isFolder) {
                targetFile.mkdirs()
                return null
            }
            targetFile.parentFile?.mkdirs()
            fos = FileOutputStream(targetFile)
            bos = BufferedOutputStream(fos, BUFFER_SIZE)
            return ISequentialOutStream { data ->
                bos?.write(data)
                data.size 
            }
        }

        override fun setOperationResult(result: ExtractOperationResult) {
            bos?.flush()
            bos?.close()
            fos?.close()
            bos = null
            fos = null
            if (result != ExtractOperationResult.OK) {
                currentFile?.delete() 
                throw RuntimeException("Extraction failed: $result")
            }
        }
        override fun cryptoGetTextPassword(): String? = password
    }
}

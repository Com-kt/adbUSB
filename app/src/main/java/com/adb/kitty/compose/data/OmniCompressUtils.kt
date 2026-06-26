package com.adb.kitty.compose.data

import net.sf.sevenzipjbinding.*
import net.sf.sevenzipjbinding.impl.OutItemFactory
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import net.sf.sevenzipjbinding.impl.RandomAccessFileOutStream
import java.io.*

object OmniCompressUtils {

    private const val BUFFER_SIZE = 65536
    
    private var isSevenZipInitialized = false

    private fun ensureInitialized() {
        if (!isSevenZipInitialized) {
            SevenZip.initSevenZipFromPlatformJAR()
            isSevenZipInitialized = true
        }
    }

    fun compress(
        format: String, 
        source: File, 
        output: File,
        onStatusUpdate: ((fileName: String, status: String) -> Unit)? = null
    ): Boolean {
        if (!source.exists()) return false
        var raf: RandomAccessFile? = null
        var outArchive: IOutArchive<IOutItemAllFormats>? = null

        return try {
            ensureInitialized()

            val archiveFormat = when (format.lowercase().trim()) {
                "7z" -> ArchiveFormat.SEVEN_ZIP
                "zip" -> ArchiveFormat.ZIP
                "tar" -> ArchiveFormat.TAR
                "gzip", "gz" -> ArchiveFormat.GZIP
                "bzip2", "bz2" -> ArchiveFormat.BZIP2
                "rar" -> throw IllegalArgumentException("7-Zip 原生不支持直接打包创建 RAR 格式！")
                else -> throw IllegalArgumentException("不支持的输出格式: $format")
            }

            val fileList = mutableListOf<File>()
            if (source.isDirectory) {
                collectFilesRecursive(source, fileList)
            } else {
                fileList.add(source)
            }

            raf = RandomAccessFile(output, "rw")
            val outStream = RandomAccessFileOutStream(raf)
            
            @Suppress("UNCHECKED_CAST")
            outArchive = SevenZip.openOutArchive(archiveFormat) as IOutArchive<IOutItemAllFormats>

            val callback = CreateArchiveCallback(source, fileList, onStatusUpdate)
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

    fun decompress(sourceFile: File, outputTarget: File, password: String? = null): Boolean {
        if (!sourceFile.exists()) return false
        var raf: RandomAccessFile? = null
        var inArchive: IInArchive? = null

        return try {
            ensureInitialized()

            raf = RandomAccessFile(sourceFile, "r")
            val inStream = RandomAccessFileInStream(raf)

            inArchive = SevenZip.openInArchive(null, inStream)

            if (!outputTarget.exists()) outputTarget.mkdirs()

            val callback = ExtractArchiveCallback(sourceFile, inArchive, outputTarget, password)
            inArchive.extract(null, false, callback)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            inArchive?.close()
            raf?.close()
        }
    }

    private fun collectFilesRecursive(dir: File, fileList: MutableList<File>) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            fileList.add(file)
            if (file.isDirectory) {
                collectFilesRecursive(file, fileList)
            }
        }
    }

    private class ExtractArchiveCallback(
        private val sourceFile: File,
        private val inArchive: IInArchive,
        private val outputDir: File,
        private val password: String?
    ) : IArchiveExtractCallback, ICryptoGetTextPassword {

        private var fos: FileOutputStream? = null
        private var bos: BufferedOutputStream? = null
        private var currentFile: File? = null

        override fun setTotal(total: Long) {}
        override fun setCompleted(complete: Long) {}

        override fun getStream(index: Int, askMode: ExtractAskMode): ISequentialOutStream? {
            if (askMode != ExtractAskMode.EXTRACT) return null

            val isFolder = inArchive.getProperty(index, PropID.IS_FOLDER) as? Boolean ?: false
            var path = inArchive.getProperty(index, PropID.PATH) as? String

            if (path.isNullOrEmpty()) {
                path = sourceFile.nameWithoutExtension
            }

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

        override fun prepareOperation(askMode: ExtractAskMode) {}

        override fun setOperationResult(result: ExtractOperationResult) {
            bos?.flush()
            bos?.close()
            fos?.close()
            bos = null
            fos = null
            if (result != ExtractOperationResult.OK) {
                currentFile?.delete() 
                throw RuntimeException("7-Zip 操作失败: $result")
            }
        }

        override fun cryptoGetTextPassword(): String? = password
    }

    private class CreateArchiveCallback(
        private val baseDir: File,
        private val fileList: List<File>,
        private val onStatusUpdate: ((fileName: String, status: String) -> Unit)?
    ) : IOutCreateCallback<IOutItemAllFormats> {

        private var currentFileIndex: Int = -1

        override fun setTotal(total: Long) {}
        override fun setCompleted(complete: Long) {}

        override fun getItemInformation(
            index: Int, 
            outItemFactory: OutItemFactory<IOutItemAllFormats>
        ): IOutItemAllFormats {
            val file = fileList[index]
            val outItem = outItemFactory.createOutItem()
            
            val relativePath = if (baseDir.isDirectory) {
                file.relativeTo(baseDir).path.replace("\\", "/")
            } else {
                file.name
            }

            outItem.setPropertyPath(relativePath)
            outItem.setPropertyIsDir(file.isDirectory)

            if (!file.isDirectory) {
                outItem.setDataSize(file.length())
            }

            return outItem
        }

        override fun getStream(index: Int): ISequentialInStream? {
            val file = fileList[index]
            currentFileIndex = index
            
            onStatusUpdate?.invoke(file.name, "START")

            if (file.isDirectory) {
                onStatusUpdate?.invoke(file.name, "SUCCESS")
                return null
            }

            val fis = FileInputStream(file)
            
            return object : ISequentialInStream {
                override fun read(data: ByteArray): Int {
                    val read = fis.read(data)
                    if (read == -1) {
                        fis.close()
                        return 0
                    }
                    return read
                }

                override fun close() {
                    fis.close()
                }
            }
        }

        override fun setOperationResult(operationResult: Boolean) {
            if (currentFileIndex in fileList.indices) {
                val file = fileList[currentFileIndex]
                val statusString = if (operationResult) "SUCCESS" else "FAILED"
                onStatusUpdate?.invoke(file.name, statusString)
            }
        }
    }
}

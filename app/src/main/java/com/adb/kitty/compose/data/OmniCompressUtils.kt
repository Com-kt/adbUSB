package com.adb.kitty.compose.data

import net.sf.sevenzipjbinding.*
import net.sf.sevenzipjbinding.impl.OutItemFactory
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import net.sf.sevenzipjbinding.impl.RandomAccessFileOutStream
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
            "7z" -> compress7z(source, fileList, output, onStatusUpdate)
            "zip" -> compressZip(source, fileList, output, onStatusUpdate)
            "tar" -> compressTar(source, fileList, output, onStatusUpdate)
            else -> compressGeneric(normFormat, source, fileList, output, onStatusUpdate)
        }
    }

    private fun compress7z(
        baseDir: File,
        fileList: List<File>,
        output: File,
        onStatusUpdate: ((String, String) -> Unit)?
    ): Boolean {
        var raf: RandomAccessFile? = null
        var outArchive: IOutArchive<IOutItem7z>? = null
        return try {
            if (output.exists()) output.delete()
            
            raf = RandomAccessFile(output, "rw")
            raf.setLength(0)
            val outStream = RandomAccessFileOutStream(raf)
            
            @Suppress("UNCHECKED_CAST")
            outArchive = SevenZip.openOutArchive(ArchiveFormat.SEVEN_ZIP) as IOutArchive<IOutItem7z>
            
            val callback = object : IOutCreateCallback<IOutItem7z> {
                private var currentFileIndex: Int = -1
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

    private fun compressZip(
        baseDir: File,
        fileList: List<File>,
        output: File,
        onStatusUpdate: ((String, String) -> Unit)?
    ): Boolean {
        var raf: RandomAccessFile? = null
        var outArchive: IOutArchive<IOutItemZip>? = null
        return try {
            if (output.exists()) {
                output.delete()
            }
            
            raf = RandomAccessFile(output, "rw")
            raf.setLength(0) 
            
            val outStream = RandomAccessFileOutStream(raf)
            
            @Suppress("UNCHECKED_CAST")
            outArchive = SevenZip.openOutArchive(ArchiveFormat.ZIP) as IOutArchive<IOutItemZip>
            
            if (outArchive is net.sf.sevenzipjbinding.IOutFeatureSetLevel) {
                outArchive.setLevel(5)
            }
            
            val callback = object : IOutCreateCallback<IOutItemZip> {
                private var currentFileIndex: Int = -1
                override fun setTotal(total: Long) {}
                override fun setCompleted(complete: Long) {}

                override fun getItemInformation(index: Int, outItemFactory: OutItemFactory<IOutItemZip>): IOutItemZip {
                    val file = fileList[index]
                    val outItem = outItemFactory.createOutItem()
                    outItem.setPropertyPath(getRelativePath(baseDir, file))
                    outItem.setPropertyIsDir(file.isDirectory)
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

    private fun compressTar(
        baseDir: File,
        fileList: List<File>,
        output: File,
        onStatusUpdate: ((String, String) -> Unit)?
    ): Boolean {
        var raf: RandomAccessFile? = null
        var outArchive: IOutArchive<IOutItemTar>? = null
        return try {
            raf = RandomAccessFile(output, "rw")
            val outStream = RandomAccessFileOutStream(raf)
            
            @Suppress("UNCHECKED_CAST")
            outArchive = SevenZip.openOutArchive(ArchiveFormat.TAR) as IOutArchive<IOutItemTar>
            
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
            raf = RandomAccessFile(output, "rw")
            val outStream = RandomAccessFileOutStream(raf)
            outArchive = SevenZip.openOutArchive(archiveFormat) as IOutArchive<IOutItemAllFormats>
            
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
        override fun prepareOperation(askMode: ExtractAskMode) {}

        override fun getStream(index: Int, askMode: ExtractAskMode): ISequentialOutStream? {
            if (askMode != ExtractAskMode.EXTRACT) return null
            val isFolder = inArchive.getProperty(index, PropID.IS_FOLDER) as? Boolean ?: false
            var path = inArchive.getProperty(index, PropID.PATH) as? String
            if (path.isNullOrEmpty()) path = sourceFile.nameWithoutExtension

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

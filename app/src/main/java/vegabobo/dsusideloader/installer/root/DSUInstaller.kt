package vegabobo.dsusideloader.installer.root

import android.app.Application
import android.gsi.IGsiService
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.util.Log
import java.io.*
import java.net.URL
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.*
import org.lsposed.hiddenapibypass.HiddenApiBypass
import vegabobo.dsusideloader.model.*
import vegabobo.dsusideloader.preparation.InstallationStep
import vegabobo.dsusideloader.service.PrivilegedProvider

class CustomROMPorter(
    private val application: Application,
    private val userdataSize: Long,
    private val dsuInstallation: DSUInstallationSource,
    private var installationJob: Job = Job(),
    private val onInstallationError: (error: InstallationStep, errorInfo: String) -> Unit,
    private val onInstallationProgressUpdate: (progress: Float, partition: String) -> Unit,
    private val onCreatePartition: (partition: String) -> Unit,
    private val onInstallationStepUpdate: (step: InstallationStep) -> Unit,
    private val onInstallationSuccess: () -> Unit,
) : () -> Unit {

    private val tag = this.javaClass.simpleName

    object Constants {
        const val DEFAULT_SLOT = "dsu"
        const val SHARED_MEM_SIZE: Int = 524288
        const val MIN_PROGRESS_TO_PUBLISH = (1 shl 27).toLong()
        const val BUFFER_SIZE = 8192
    }

    private class MappedMemoryBuffer(var mBuffer: ByteBuffer?) : AutoCloseable {
        override fun close() {
            if (mBuffer != null) {
                SharedMemory.unmap(mBuffer!!)
                mBuffer = null
            }
        }
    }

    private val SUPPORTED_PARTITIONS: List<String> = listOf(
        "system", "vendor", "product", "system_ext"
    )

    private fun isPartitionSupported(partitionName: String): Boolean =
        SUPPORTED_PARTITIONS.contains(partitionName)

    private fun getFdDup(sharedMemory: SharedMemory): ParcelFileDescriptor {
        return HiddenApiBypass.invoke(
            sharedMemory.javaClass,
            sharedMemory,
            "getFdDup"
        ) as ParcelFileDescriptor
    }

    private fun shouldProcessEntry(name: String): Boolean {
        if (!name.endsWith(".img")) {
            return false
        }
        val partitionName = name.substringBeforeLast(".")
        return isPartitionSupported(partitionName)
    }

    private fun publishProgress(bytesRead: Long, totalBytes: Long, partition: String) {
        var progress = 0F
        if (totalBytes != 0L && bytesRead != 0L) {
            progress = (bytesRead.toFloat() / totalBytes.toFloat())
        }
        onInstallationProgressUpdate(progress, partition)
    }

    private fun processImage(
        partition: String, uncompressedSize: Long, inputStream: InputStream, outputFile: File
    ) {
        val buffer = ByteArray(Constants.BUFFER_SIZE)
        var bytesRead: Int
        var totalBytesRead: Long = 0

        FileOutputStream(outputFile).use { outputStream ->
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
                publishProgress(totalBytesRead, uncompressedSize, partition)
            }
        }

        Log.d(tag, "Partition $partition processed, size: $uncompressedSize")
    }

    private fun processStreamingZipUpdate(inputStream: InputStream, outputDir: File): Boolean {
        val zis = ZipInputStream(inputStream)
        var entry: ZipEntry?
        while (zis.nextEntry.also { entry = it } != null) {
            val fileName = entry!!.name
            if (shouldProcessEntry(fileName)) {
                processImageFromAnEntry(entry!!, zis, outputDir)
            } else {
                Log.d(tag, "$fileName processing is not supported, skipping it.")
            }
            if (installationJob.isCancelled) break
        }
        return true
    }

    private fun processImageFromAnEntry(entry: ZipEntry, inputStream: InputStream, outputDir: File) {
        val fileName = entry.name
        Log.d(tag, "Processing: $fileName")
        val partitionName = fileName.substring(0, fileName.length - 4)
        val uncompressedSize = entry.size
        val outputFile = File(outputDir, "${partitionName}.img")
        processImage(partitionName, uncompressedSize, inputStream, outputFile)
    }

    private fun startPorting() {
        onInstallationStepUpdate(InstallationStep.PREPARING)
        val outputDir = File(application.filesDir, "ported_rom")
        outputDir.mkdirs()

        when (dsuInstallation.type) {
            Type.SINGLE_SYSTEM_IMAGE -> {
                val outputFile = File(outputDir, "system.img")
                processImage("system", dsuInstallation.fileSize, openInputStream(dsuInstallation.uri), outputFile)
            }
            Type.MULTIPLE_IMAGES -> {
                processImages(dsuInstallation.images, outputDir)
            }
            Type.DSU_PACKAGE -> {
                processStreamingZipUpdate(openInputStream(dsuInstallation.uri), outputDir)
            }
            Type.URL -> {
                val url = URL(dsuInstallation.uri.toString())
                processStreamingZipUpdate(url.openStream(), outputDir)
            }
            else -> {}
        }

        if (!installationJob.isCancelled) {
            onInstallationStepUpdate(InstallationStep.FINALIZING)
            val finalOutputFile = File(outputDir, "output.img")
            combineImages(outputDir, finalOutputFile)
            
            Log.d(tag, "Porting finished successfully. Output: ${finalOutputFile.absolutePath}")
            onInstallationSuccess()
        }
    }

    private fun processImages(images: List<ImagePartition>, outputDir: File) {
        for (image in images) {
            if (isPartitionSupported(image.partitionName)) {
                val outputFile = File(outputDir, "${image.partitionName}.img")
                processImage(image.partitionName, image.fileSize, openInputStream(image.uri), outputFile)
            }
            if (installationJob.isCancelled) {
                return
            }
        }
    }

    private fun openInputStream(uri: Uri): InputStream {
        return application.contentResolver.openInputStream(uri)!!
    }

    private fun combineImages(inputDir: File, outputFile: File) {
        Log.d(tag, "Combining images into GSI compatible format")
        
        val partitionOrder = listOf("system", "vendor", "product", "system_ext")
        var totalSize = 0L
        val partitionSizes = mutableMapOf<String, Long>()

        for (partition in partitionOrder) {
            val partitionFile = File(inputDir, "$partition.img")
            if (partitionFile.exists()) {
                val size = partitionFile.length()
                partitionSizes[partition] = size
                totalSize += size
            }
        }

        outputFile.outputStream().use { output ->
            for (partition in partitionOrder) {
                val partitionFile = File(inputDir, "$partition.img")
                if (partitionFile.exists()) {
                    partitionFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }
            }
        }

        // Update partition table
        updatePartitionTable(outputFile, partitionSizes, totalSize)

        Log.d(tag, "Combined GSI image created: ${outputFile.absolutePath}")
    }

    private fun updatePartitionTable(outputFile: File, partitionSizes: Map<String, Long>, totalSize: Long) {
        // This is a simplified example. In a real implementation, you'd need to create a proper
        // partition table format (e.g., GPT) and write it to the beginning of the file.
        val partitionTable = StringBuilder()
        var offset = 0L
        
        for ((partition, size) in partitionSizes) {
            partitionTable.append("$partition:$offset:$size\n")
            offset += size
        }

        RandomAccessFile(outputFile, "rw").use { raf ->
            raf.seek(totalSize)
            raf.write(partitionTable.toString().toByteArray())
        }
    }

    private fun installGSI(gsiFile: File) {
        Log.d(tag, "Installing GSI: ${gsiFile.absolutePath}")
        
        val gsiService = PrivilegedProvider.getService()
        
        // Ensure DSU is not already in use
        if (gsiService.isInUse) {
            onInstallationError(InstallationStep.ERROR_ALREADY_RUNNING_DYN_OS, "")
            return
        }

        // Start installation
        val installationResult = gsiService.startInstallation(Constants.DEFAULT_SLOT, gsiFile.absolutePath)
        if (installationResult != IGsiService.INSTALL_OK) {
            onInstallationError(InstallationStep.ERROR_INSTALL_FAILED, "Installation failed with code: $installationResult")
            return
        }

        // Monitor installation progress
        var prevProgress = 0L
        while (true) {
            val progress = gsiService.getInstallationProgress()
            if (progress.status == IGsiService.STATUS_COMPLETE) {
                break
            } else if (progress.status == IGsiService.STATUS_ERROR) {
                onInstallationError(InstallationStep.ERROR_INSTALL_FAILED, "Installation failed during progress")
                return
            }

            if (progress.bytes_processed > prevProgress + Constants.MIN_PROGRESS_TO_PUBLISH) {
                prevProgress = progress.bytes_processed
                publishProgress(progress.bytes_processed.toLong(), progress.total_bytes.toLong(), "GSI")
            }

            runBlocking { delay(100) }
        }

        Log.d(tag, "GSI installation completed successfully")
    }

    override fun invoke() {
        runBlocking {
            launch(Dispatchers.IO) {
                startPorting()
                val gsiFile = File(application.filesDir, "ported_rom/output.img")
                installGSI(gsiFile)
            }
        }
    }
}

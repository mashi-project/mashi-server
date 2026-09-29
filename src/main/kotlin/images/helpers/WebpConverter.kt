package com.mashiverse.images.helpers

import com.mashiverse.data.models.ImageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

suspend fun convertToWebp(imageBytes: ByteArray, imageType: ImageType): ByteArray = withContext(Dispatchers.IO) {
    val extension = when (imageType) {
        ImageType.PNG -> ".png"
        ImageType.GIF -> ".gif"
        ImageType.APNG -> ".apng"
        ImageType.WEBP -> ".webp"
        else -> ".img"
    }

    val tempInputFile = File.createTempFile("img_input_", extension)
    val tempOutputFile = File.createTempFile("img_output_", ".webp")

    try {
        tempInputFile.writeBytes(imageBytes)

        val processBuilder = ProcessBuilder(
            "ffmpeg",
            "-y",
            "-i", tempInputFile.absolutePath,
            "-c:v", "libwebp_anim",
            "-lossless", "1",
            "-loop", "0",
            tempOutputFile.absolutePath
        )

        val process = processBuilder.start()

        // Drain stderr concurrently to avoid deadlock on large logs
        val errorOutput = StringBuilder()
        val errorThread = Thread {
            process.errorStream.bufferedReader().forEachLine { errorOutput.appendLine(it) }
        }
        errorThread.start()

        val exitCode = process.waitFor()
        errorThread.join()

        if (exitCode != 0) {
            throw RuntimeException("FFmpeg conversion failed with exit code $exitCode: $errorOutput")
        }

        return@withContext tempOutputFile.readBytes()

    } finally {
        try { tempInputFile.delete() } catch (_: Exception) {}
        try { tempOutputFile.delete() } catch (_: Exception) {}
    }
}
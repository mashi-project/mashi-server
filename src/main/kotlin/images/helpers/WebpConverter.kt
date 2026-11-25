package com.mashiverse.images.helpers

import com.mashiverse.data.models.ImageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.util.concurrent.TimeUnit

private const val MAX_WIDTH = 828
private const val MAX_HEIGHT = 1104
private const val CONVERT_TIMEOUT_SEC = 120L

/**
 * Pillow does the conversion. It sniffs the format from the bytes itself (PNG, APNG, GIF, WebP,
 * animated WebP, ...), so no type detection is needed on the Kotlin side.
 *
 * Args: input path, output path, max width, max height.
 * Never upscales, keeps the aspect ratio, keeps per-frame durations, lossless RGBA.
 */
private val PILLOW_SCRIPT = """
import sys
from PIL import Image, ImageSequence

src, dst = sys.argv[1], sys.argv[2]
max_w, max_h = int(sys.argv[3]), int(sys.argv[4])

with Image.open(src) as im:
    frames, durations = [], []
    for frame in ImageSequence.Iterator(im):
        durations.append(int(frame.info.get("duration", 100)))
        f = frame.convert("RGBA")
        f.thumbnail((max_w, max_h), Image.LANCZOS)
        frames.append(f)

frames[0].save(
    dst,
    "WEBP",
    save_all=len(frames) > 1,
    append_images=frames[1:],
    duration=durations,
    loop=0,
    lossless=True,
    quality=80,
    method=4,
)
""".trimIndent()

@Suppress("UNUSED_PARAMETER")
suspend fun convertToWebp(imageBytes: ByteArray, imageType: ImageType): ByteArray = withContext(Dispatchers.IO) {
    require(imageBytes.isNotEmpty()) { "Empty image" }

    val tempInputFile = Files.createTempFile("img_input_", ".img").toFile()
    val tempOutputFile = Files.createTempFile("img_output_", ".webp").toFile()

    try {
        tempInputFile.writeBytes(imageBytes)

        val command = listOf(
            "python3", "-I", "-c", PILLOW_SCRIPT,
            tempInputFile.absolutePath,
            tempOutputFile.absolutePath,
            MAX_WIDTH.toString(),
            MAX_HEIGHT.toString()
        )

        val process = ProcessBuilder(command)
            .redirectErrorStream(true)   // one stream to drain, so a full pipe can never deadlock
            .start()

        val output = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader().forEachLine { output.appendLine(it) }
        }.apply { isDaemon = true; start() }

        if (!process.waitFor(CONVERT_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw RuntimeException("Pillow conversion timed out after ${CONVERT_TIMEOUT_SEC}s")
        }
        reader.join(5_000)

        val exitCode = process.exitValue()
        if (exitCode != 0) {
            throw RuntimeException("Pillow conversion failed with exit code $exitCode (declared=$imageType): $output")
        }

        val result = tempOutputFile.readBytes()
        check(result.isNotEmpty()) { "Pillow produced an empty file (declared=$imageType): $output" }
        result
    } finally {
        runCatching { tempInputFile.delete() }
        runCatching { tempOutputFile.delete() }
    }
}
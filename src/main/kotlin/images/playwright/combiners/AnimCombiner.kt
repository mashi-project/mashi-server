package com.mashiverse.images.playwright.combiners

import com.mashiverse.configs.*
import com.mashiverse.images.playwright.PlaywrightPool
import com.mashiverse.utils.helpers.executeCmd
import com.mashiverse.utils.helpers.readImageFiles
import com.microsoft.playwright.Browser
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.ViewportSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import java.nio.file.Path
import kotlin.io.path.absolutePathString

class AnimCombiner : KoinComponent {

    suspend fun generateAnim(tempDir: Path, isLowerRes: Boolean = false): Path {
        val targetDurationSec = DURATION_LIMIT_SEC
        val width = if (isLowerRes) LOWER_RES_GIF_WIDTH else GIF_WIDTH
        val height = if (isLowerRes) LOWER_RES_GIF_HEIGHT else GIF_HEIGHT

        val imageUrls = readImageFiles(tempDir)
        val htmlContent = prepareHtml(
            urls = imageUrls,
            width = width,
            height = height
        )

        var startOffsetSec = 0.0

        PlaywrightPool.execute { browser ->
            // 1. Warm-up pass to resolve dimensions and structure
            val warmupCtx = browser.newContext(
                Browser.NewContextOptions()
                    .setViewportSize(ViewportSize(width, height))
                    .setDeviceScaleFactor(1.0)
            )

            val correctedHtml = warmupCtx.use { ctx ->
                val warmupPage = ctx.newPage()
                warmupPage.setContent(htmlContent)
                warmupPage.waitForLoadState(LoadState.LOAD)
                preparePage(warmupPage, if (isLowerRes) getLowerResGifArgs() else getGifArgs())
                warmupPage.content()
            }

            // 2. Recording pass
            val recordingCtx = browser.newContext(
                Browser.NewContextOptions()
                    .setViewportSize(ViewportSize(width, height))
                    .setDeviceScaleFactor(1.0)
                    .setRecordVideoDir(tempDir)
                    .setRecordVideoSize(width, height)
            )

            recordingCtx.use { ctx ->
                val page = ctx.newPage()
                val recordingStartedAt = System.nanoTime()

                page.setContent(correctedHtml)

                // Force explicit image decoding to avoid blank missing frames at start
                page.evaluate(
                    """
                    () => Promise.all(
                        Array.from(document.images).map(img => img.decode ? img.decode().catch(() => {}) : Promise.resolve())
                    )
                    """.trimIndent()
                )

                // Calculate offset from initial context record trigger to paint finish
                startOffsetSec = (System.nanoTime() - recordingStartedAt) / 1_000_000_000.0

                // Total sleep duration matching exact frame capture window
                val totalSleepMs = ((targetDurationSec + startOffsetSec + 0.3) * 1000).toLong()
                Thread.sleep(totalSleepMs)

                page.close()
            }
        }

        val videoFile = withContext(Dispatchers.IO) {
            tempDir.toFile().listFiles { _, name -> name.endsWith(".webm") }?.firstOrNull()
                ?: throw IllegalStateException("Playwright video was not recorded successfully.")
        }

        return withContext(Dispatchers.IO) {
            makeGifFromVideo(
                videoPath = videoFile.toPath(),
                tempDir = tempDir,
                durationSec = targetDurationSec,
                startOffsetSec = startOffsetSec,
                isLowerRes = isLowerRes
            )
        }
    }

    private fun makeGifFromVideo(
        videoPath: Path,
        tempDir: Path,
        durationSec: Double,
        startOffsetSec: Double,
        isLowerRes: Boolean = false
    ): Path {
        val gifPath = tempDir.resolve("result.gif")
        val width = if (isLowerRes) LOWER_RES_GIF_WIDTH else GIF_WIDTH
        val height = if (isLowerRes) LOWER_RES_GIF_HEIGHT else GIF_HEIGHT

        // Format seek accurately
        val seekArg = String.format(java.util.Locale.US, "%.3f", startOffsetSec)
        val durationArg = String.format(java.util.Locale.US, "%.3f", durationSec)

        // Enforce nearest-neighbor scaling inside FFmpeg filter chain to prevent pixel blurring
        val baseFilter =
            "fps=$PLAYBACK_FPS,scale=$width:$height:flags=neighbor:force_original_aspect_ratio=decrease,pad=$width:$height:(ow-iw)/2:(oh-ih)/2,setsar=1"

        // Updated filter graph:
// 1. stats_mode=full ensures consistent color palette across loop boundary
// 2. dither=sierra2_4a or bayer with lower scale prevents patterned shimmering
// 3. diff_mode=rectangle prevents ghosting artifacts
        val filterGraph = "[0:v]$baseFilter,split[stream][paletteSource];" +
                "[paletteSource]palettegen=max_colors=256:stats_mode=full[palette];" +
                "[stream][palette]paletteuse=dither=sierra2_4a:diff_mode=rectangle"

        executeCmd(
            "ffmpeg",
            "-y",
            "-threads", "0",
            "-ss", seekArg,
            "-i", videoPath.absolutePathString(),
            "-t", durationArg,
            "-filter_complex", filterGraph,
            gifPath.absolutePathString()
        )

// Add --loopcount=0 to Gifsicle to explicitly mark smooth infinite looping
        executeCmd(
            "gifsicle",
            "-b",
            "-O2",
            "--loopcount=0",
            "--no-comments",
            "--no-names",
            "--no-extensions",
            gifPath.absolutePathString()
        )

        return gifPath
    }
}
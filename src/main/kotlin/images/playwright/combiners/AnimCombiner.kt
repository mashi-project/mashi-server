package com.mashiverse.images.playwright.combiners

import com.mashiverse.configs.*
import com.mashiverse.data.db.daos.HistoryDao
import com.mashiverse.images.playwright.PlaywrightPool
import com.mashiverse.utils.helpers.executeCmd
import com.mashiverse.utils.helpers.readImageFiles
import com.microsoft.playwright.Browser
import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.ScreenshotType
import com.microsoft.playwright.options.WaitUntilState
import com.microsoft.playwright.options.ViewportSize
import kotlinx.coroutines.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.absolutePathString
import kotlin.io.path.readBytes
import kotlin.math.roundToInt

class AnimCombiner : KoinComponent {

    companion object {
        // Background color used in the HTML (html, body { background: #111214; })
        // and keyed out to transparent in ffmpeg. Both must match.
        const val KEY_HEX = "FF00FF"
        const val KEY_SIMILARITY = "0.02"

        // Number of parallel capture workers. Each one uses its own PlaywrightPool slot
        // (Playwright Java is not thread-safe, so every worker needs its own browser),
        // so the pool must allow at least this many concurrent execute() calls.
        const val PARALLEL_WORKERS = 4

        // Installed once per capture page. Replaces animated WebP <img> elements with canvases
        // that can be drawn at an arbitrary time, and exposes window.__seek(tMs).
        // `data` maps image src -> base64 bytes (read on the JVM side, so no CORS/file:// issues).
        private val INIT_JS = """
            async (data) => {
              const b64ToBytes = (b64) => {
                const bin = atob(b64);
                const u = new Uint8Array(bin.length);
                for (let i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i);
                return u;
              };
              const isWebp = (b) => b.length > 12 &&
                String.fromCharCode(b[0], b[1], b[2], b[3]) === 'RIFF' &&
                String.fromCharCode(b[8], b[9], b[10], b[11]) === 'WEBP';

              const items = [];
              for (const img of Array.from(document.images)) {
                const src = img.currentSrc || img.src;
                let bytes;
                try {
                  bytes = data[src]
                    ? b64ToBytes(data[src])
                    : new Uint8Array(await (await fetch(src)).arrayBuffer());
                } catch (e) { continue; }
                if (!isWebp(bytes)) continue;

                const dec = new ImageDecoder({ data: bytes, type: 'image/webp' });
                await dec.tracks.ready;
                const n = dec.tracks.selectedTrack.frameCount;
                if (n <= 1) { dec.close(); continue; }   // static WebP: leave the <img> alone

                const frames = [];
                let t = 0;
                for (let i = 0; i < n; i++) {
                  const { image } = await dec.decode({ frameIndex: i });
                  const dur = (image.duration || 100000) / 1000;   // microseconds -> ms
                  frames.push({ start: t, end: t + dur, bitmap: await createImageBitmap(image) });
                  t += dur;
                  image.close();
                }
                dec.close();

                const cs = getComputedStyle(img);
                const canvas = document.createElement('canvas');
                canvas.width = frames[0].bitmap.width;
                canvas.height = frames[0].bitmap.height;
                canvas.className = img.className;
                if (img.id) canvas.id = img.id;
                canvas.setAttribute('style', img.getAttribute('style') || '');
                for (const p of ['position', 'left', 'top', 'right', 'bottom', 'transform',
                                 'transformOrigin', 'width', 'height', 'objectFit', 'objectPosition',
                                 'zIndex', 'opacity', 'mixBlendMode', 'imageRendering', 'display', 'margin']) {
                  canvas.style[p] = cs[p];
                }
                img.replaceWith(canvas);
                items.push({ canvas, frames, total: t });
              }

              // Make sure the remaining (static) images are decoded before the first screenshot.
              await Promise.all(Array.from(document.images).map(i => i.decode ? i.decode().catch(() => {}) : Promise.resolve()));

              window.__seek = async (tMs) => {
                for (const it of items) {
                  const t = it.total > 0 ? tMs % it.total : 0;
                  let f = it.frames[it.frames.length - 1];
                  for (const fr of it.frames) { if (t >= fr.start && t < fr.end) { f = fr; break; } }
                  const ctx = it.canvas.getContext('2d');
                  ctx.clearRect(0, 0, it.canvas.width, it.canvas.height);
                  ctx.drawImage(f.bitmap, 0, 0);
                }
                // SMIL in inline <svg>
                document.querySelectorAll('svg').forEach(s => {
                  if (s.pauseAnimations) { s.pauseAnimations(); s.setCurrentTime(tMs / 1000); }
                });
                // CSS animations / transitions / Web Animations
                document.getAnimations().forEach(a => { a.pause(); a.currentTime = tMs; });
                await new Promise(r => requestAnimationFrame(() => r()));
              };
              return items.length;
            }
        """.trimIndent()

        private val httpClient by lazy { HttpClient.newHttpClient() }
    }

    private val historyDao by inject<HistoryDao>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun generateAnim(tempDir: Path, wallet: String? = null, isSmall: Boolean = false): Path {
        val width = if (isSmall) {
            SMALL_GIF_WIDTH
        } else {
            GIF_WIDTH
        }
        val height = if (isSmall) {
            SMALL_GIF_HEIGHT
        } else {
            GIF_HEIGHT
        }

        val imageUrls = readImageFiles(tempDir)
        val htmlContent = prepareHtml(
            urls = imageUrls,
            width = width,
            height = height
        )

        val args = if (isSmall) {
            getSmallGifArgs()
        } else {
            getGifArgs()
        }

        // 1. Warm-up pass to resolve dimensions and structure (unchanged)
        var correctedHtml = ""
        var imageSrcs: List<String> = emptyList()

        PlaywrightPool.execute { browser ->
            val warmupCtx = browser.newContext(
                Browser.NewContextOptions()
                    .setViewportSize(ViewportSize(width, height))
                    .setDeviceScaleFactor(1.0)
            )

            warmupCtx.use { ctx ->
                val warmupPage = ctx.newPage()
                warmupPage.setContent(htmlContent)
                warmupPage.waitForLoadState(LoadState.LOAD)
                preparePage(warmupPage, args)
                correctedHtml = warmupPage.content()

                @Suppress("UNCHECKED_CAST")
                imageSrcs = warmupPage.evaluate(
                    "() => Array.from(document.images).map(i => i.currentSrc || i.src)"
                ) as List<String>
            }
        }

        // 2. Read image bytes once on the JVM side and share them with all workers
        val imageData = withContext(Dispatchers.IO) { loadImageData(imageSrcs) }

        // 3. Deterministic parallel capture: frame i is rendered at t = i / fps
        val framesDir = tempDir.resolve("frames")
        withContext(Dispatchers.IO) { Files.createDirectories(framesDir) }

        val fps = PLAYBACK_FPS.toDouble()
        val totalFrames = (DURATION_LIMIT_SEC * fps).roundToInt()

        val resultGifPath = try {
            captureFrames(
                html = correctedHtml,
                imageData = imageData,
                framesDir = framesDir,
                width = width,
                height = height,
                fps = fps,
                totalFrames = totalFrames
            )

            withContext(Dispatchers.IO) {
                makeGifFromFrames(
                    framesDir = framesDir,
                    tempDir = tempDir,
                    isSmall = isSmall
                )
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                framesDir.toFile().deleteRecursively()
            }
        }

        // Launch separate background coroutine to save history asynchronously
        if (!wallet.isNullOrBlank()) {
            scope.launch {
                try {
                    val imageBytes = resultGifPath.readBytes()
                    historyDao.addHistory(
                        wallet = wallet,
                        image = imageBytes
                    )
                } catch (e: Exception) {
                    System.err.println("Failed to save animation to history for wallet $wallet: ${e.message}")
                }
            }
        }

        return resultGifPath
    }

    /**
     * Splits the frames across [PARALLEL_WORKERS] independent browsers.
     * Worker w renders frames w, w + N, w + 2N, ... (interleaved so they finish together).
     * Every frame time is computed, not measured, so there is no start-offset drift or seam.
     */
    private suspend fun captureFrames(
        html: String,
        imageData: Map<String, String>,
        framesDir: Path,
        width: Int,
        height: Int,
        fps: Double,
        totalFrames: Int
    ) = coroutineScope {
        (0 until PARALLEL_WORKERS).map { worker ->
            async(Dispatchers.IO) {
                PlaywrightPool.execute { browser ->
                    browser.newContext(
                        Browser.NewContextOptions()
                            .setViewportSize(ViewportSize(width, height))
                            .setDeviceScaleFactor(1.0)
                    ).use { ctx ->
                        val page = ctx.newPage()
                        // WebCodecs (ImageDecoder) only exists in secure contexts, and about:blank
                        // from setContent() is not one. http://localhost is treated as secure, so
                        // serve the HTML there via request interception (no real server needed).
                        page.route("http://localhost/**") { route ->
                            route.fulfill(
                                Route.FulfillOptions()
                                    .setContentType("text/html; charset=utf-8")
                                    .setBody(html)
                            )
                        }
                        page.navigate(
                            "http://localhost/anim.html",
                            Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD)
                        )

                        check(page.evaluate("() => typeof ImageDecoder !== 'undefined'") as Boolean) {
                            "ImageDecoder is not available in this Chromium build"
                        }
                        page.evaluate(INIT_JS, imageData)

                        var i = worker
                        while (i < totalFrames) {
                            val tMs = i * 1000.0 / fps
                            page.evaluate("t => window.__seek(t)", tMs)
                            page.screenshot(
                                Page.ScreenshotOptions()
                                    .setType(ScreenshotType.PNG)
                                    .setPath(framesDir.resolve("%05d.png".format(i)))
                            )
                            i += PARALLEL_WORKERS
                        }
                        page.close()
                    }
                }
            }
        }.awaitAll()
    }

    private fun loadImageData(srcs: List<String>): Map<String, String> {
        val result = HashMap<String, String>()
        for (src in srcs.distinct()) {
            if (src.isBlank() || src.startsWith("data:")) continue   // data: URLs can be fetched in-page
            try {
                val bytes = when {
                    src.startsWith("file:") -> Files.readAllBytes(Path.of(URI(src)))
                    src.startsWith("http://") || src.startsWith("https://") -> {
                        val req = HttpRequest.newBuilder(URI(src)).GET().build()
                        httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray()).body()
                    }
                    else -> continue
                }
                result[src] = Base64.getEncoder().encodeToString(bytes)
            } catch (e: Exception) {
                System.err.println("Could not preload image $src: ${e.message}")   // page falls back to fetch()
            }
        }
        return result
    }

    private fun makeGifFromFrames(
        framesDir: Path,
        tempDir: Path,
        isSmall: Boolean = false
    ): Path {
        val gifPath = tempDir.resolve("result.gif")

        val keyColor = "0x$KEY_HEX"

        // Frames are already exactly width x height PNGs (lossless), so no fps/scale/pad is needed.
        // format=rgba converts to full-precision RGB with an alpha channel BEFORE colorkey,
        // so keyed pixels get alpha = 0.
        val baseFilter = "format=rgba,colorkey=$keyColor:$KEY_SIMILARITY:0.0"

        // palettegen stats_mode=diff: only pixels that CHANGE between frames count toward the palette,
        // so the moving WebP content gets the palette entries and the static layers get fewer.
        val filterGraph = "[0:v]$baseFilter,split[stream][paletteSource];" +
                "[paletteSource]palettegen=max_colors=256:stats_mode=diff:reserve_transparent=1[palette];" +
                "[stream][palette]paletteuse=dither=bayer:bayer_scale=3:alpha_threshold=128:diff_mode=rectangle"

        executeCmd(
            "ffmpeg",
            "-y",
            "-threads", "0",
            "-framerate", "$PLAYBACK_FPS",
            "-i", framesDir.resolve("%05d.png").absolutePathString(),
            "-filter_complex", filterGraph,
            gifPath.absolutePathString()
        )

        // Add --loopcount=0 to Gifsicle to explicitly mark smooth infinite looping
//        executeCmd(
//            "gifsicle",
//            "-b",
//            "-O2",
//            "--loopcount=0",
//            "--no-comments",
//            "--no-names",
//            "--no-extensions",
//            gifPath.absolutePathString()
//        )

        return gifPath
    }
}
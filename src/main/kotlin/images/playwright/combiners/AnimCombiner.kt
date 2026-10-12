package com.mashiverse.images.playwright.combiners

import com.google.gson.JsonObject
import com.mashiverse.configs.*
import com.mashiverse.data.db.daos.HistoryDao
import com.mashiverse.images.playwright.PlaywrightPool
import com.mashiverse.utils.helpers.executeCmd
import com.microsoft.playwright.Browser
import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.options.ViewportSize
import com.microsoft.playwright.options.WaitUntilState
import kotlinx.coroutines.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Base64
import java.util.Locale
import kotlin.io.path.absolutePathString
import kotlin.math.roundToInt

class AnimCombiner : KoinComponent {

    companion object {
        // Background color used in prepareHtml (#FF00FF) and keyed out to transparent in ffmpeg.
        const val KEY_HEX = "FF00FF"
        const val KEY_SIMILARITY = "0.02"

        private const val ORIGIN = "http://localhost"

        // Each worker occupies one PlaywrightPool slot (POOL_SIZE = 9), and the pool is shared with
        // CompositeCombiner. Capping at 6 leaves room for other requests so a single GIF can't
        // starve them. Workers beyond the pool size would only queue.
        val PARALLEL_WORKERS = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 6)

        // Installed once per capture page, AFTER preparePage (which styles <img> elements).
        // Replaces animated WebP <img> with canvases drawable at arbitrary time and exposes:
        //   window.__sig(tMs)  -> string identifying which WebP frame each layer shows (no drawing)
        //   window.__seek(tMs) -> draws those frames
        // Images are fetched from the same-origin route, so no data needs to be passed in.
        private val INIT_JS = """
            async () => {
              const isWebp = (b) => b.length > 12 &&
                String.fromCharCode(b[0], b[1], b[2], b[3]) === 'RIFF' &&
                String.fromCharCode(b[8], b[9], b[10], b[11]) === 'WEBP';

              const items = [];
              for (const img of Array.from(document.images)) {
                const src = img.currentSrc || img.src;
                let bytes;
                try {
                  bytes = new Uint8Array(await (await fetch(src)).arrayBuffer());
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

              const frameAt = (it, tMs) => {
                const t = it.total > 0 ? tMs % it.total : 0;
                for (let i = 0; i < it.frames.length; i++) {
                  if (t >= it.frames[i].start && t < it.frames[i].end) return i;
                }
                return it.frames.length - 1;
              };

              window.__sig = (tMs) => items.map(it => frameAt(it, tMs)).join(',');

              window.__seek = async (tMs) => {
                for (const it of items) {
                  const f = it.frames[frameAt(it, tMs)];
                  const ctx = it.canvas.getContext('2d');
                  ctx.clearRect(0, 0, it.canvas.width, it.canvas.height);
                  ctx.drawImage(f.bitmap, 0, 0);
                }
                await new Promise(r => requestAnimationFrame(() => r()));
              };
              return items.length;
            }
        """.trimIndent()

        private val TEMP_ROOT: Path = Paths.get(System.getProperty("java.io.tmpdir")).resolve("mashi-temp")
    }

    /**
     * A run of [count] consecutive output frames that all look identical.
     * [frameIndex] is the index of the first frame ever rendered with this look, i.e. the PNG on disk.
     */
    private class Run(val frameIndex: Int, val count: Int)

    private val historyDao by inject<HistoryDao>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * @param traits layers bottom to top as (mime type, raw bytes). They are served to the browser
     *               directly from memory, so nothing is base64-encoded or written to disk for them.
     * @return the finished GIF. The working directory is created and removed here, so the caller never
     *         has to manage temp files (and the history save below works on the bytes, not on a file
     *         that could already be deleted).
     */
    suspend fun generateAnim(
        traits: List<Pair<String, ByteArray>>,
        wallet: String? = null,
        isSmall: Boolean = false
    ): ByteArray {
        require(traits.isNotEmpty()) { "No layers to render" }

        val width = if (isSmall) SMALL_GIF_WIDTH else GIF_WIDTH
        val height = if (isSmall) SMALL_GIF_HEIGHT else GIF_HEIGHT
        val args = if (isSmall) getSmallGifArgs() else getGifArgs()

        val html = prepareHtml(
            urls = traits.indices.map { "$ORIGIN/img/$it" },
            width = width,
            height = height
        )

        val workDir = withContext(Dispatchers.IO) {
            Files.createDirectories(TEMP_ROOT)
            Files.createTempDirectory(TEMP_ROOT, "anim-")
        }

        try {
            val framesDir = workDir.resolve("frames")
            withContext(Dispatchers.IO) { Files.createDirectories(framesDir) }

            val fps = PLAYBACK_FPS.toDouble()
            val totalFrames = (DURATION_LIMIT_SEC * fps).roundToInt()

            val runs = captureFrames(
                html = html,
                traits = traits,
                args = args,
                framesDir = framesDir,
                width = width,
                height = height,
                fps = fps,
                totalFrames = totalFrames
            )

            val gifBytes = withContext(Dispatchers.IO) {
                val gifPath = makeGifFromRuns(runs = runs, framesDir = framesDir, workDir = workDir, fps = fps)
                Files.readAllBytes(gifPath)
            }

            // Save history asynchronously from the bytes in memory
            if (!wallet.isNullOrBlank()) {
                scope.launch {
                    try {
                        historyDao.addHistory(wallet = wallet, image = gifBytes)
                    } catch (e: Exception) {
                        System.err.println("Failed to save animation to history for wallet $wallet: ${e.message}")
                    }
                }
            }

            return gifBytes
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                workDir.toFile().deleteRecursively()
            }
        }
    }

    /**
     * Every worker opens its own browser page, computes the per-frame signatures (cheap, no drawing),
     * derives the same list of unique frames, and screenshots its interleaved share of them.
     * Returns the timeline as runs of identical frames; ffmpeg later encodes one picture per run.
     */
    private suspend fun captureFrames(
        html: String,
        traits: List<Pair<String, ByteArray>>,
        args: Map<String, Int>,
        framesDir: Path,
        width: Int,
        height: Int,
        fps: Double,
        totalFrames: Int
    ): List<Run> {
        val times = (0 until totalFrames).map { it * 1000.0 / fps }
        val shotParams = JsonObject().apply {
            addProperty("format", "png")
            addProperty("optimizeForSpeed", true)   // faster, larger PNG; lossless, so the key color survives
        }

        val sigsPerWorker: List<List<String>> = coroutineScope {
            (0 until PARALLEL_WORKERS).map { worker ->
                async(Dispatchers.IO) {
                    PlaywrightPool.execute { browser ->
                        browser.newContext(
                            Browser.NewContextOptions()
                                .setViewportSize(ViewportSize(width, height))
                                .setDeviceScaleFactor(1.0)
                        ).use { ctx ->
                            val page = ctx.newPage()

                            // Serve the HTML and the layer bytes from a secure origin
                            // (WebCodecs needs a secure context; http://localhost counts as one).
                            page.route("$ORIGIN/**") { route ->
                                val path = URI.create(route.request().url()).path
                                val index = if (path.startsWith("/img/")) path.removePrefix("/img/").toIntOrNull() else null
                                val trait = index?.let { traits.getOrNull(it) }

                                when {
                                    path == "/anim.html" -> route.fulfill(
                                        Route.FulfillOptions()
                                            .setContentType("text/html; charset=utf-8")
                                            .setBody(html)
                                    )
                                    trait != null -> route.fulfill(
                                        Route.FulfillOptions()
                                            .setContentType(trait.first)
                                            .setBodyBytes(trait.second)
                                    )
                                    else -> route.fulfill(Route.FulfillOptions().setStatus(404))
                                }
                            }

                            // navigate() waits for the load event, i.e. for all <img> in the HTML.
                            page.navigate(
                                "$ORIGIN/anim.html",
                                Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD)
                            )

                            check(page.evaluate("() => typeof ImageDecoder !== 'undefined'") as Boolean) {
                                "ImageDecoder is not available in this Chromium build"
                            }

                            // Must run while the layers are still <img> elements (it reads naturalWidth/Height).
                            preparePage(page, args)
                            page.evaluate(INIT_JS)

                            @Suppress("UNCHECKED_CAST")
                            val sigs = page.evaluate("ts => ts.map(t => window.__sig(t))", times) as List<String>

                            // Same input -> same result in every worker, so no coordination is needed.
                            val unique = uniqueFrameIndices(sigs)

                            val cdp = ctx.newCDPSession(page)
                            var k = worker
                            while (k < unique.size) {
                                val i = unique[k]
                                page.evaluate("t => window.__seek(t)", times[i])
                                val b64 = cdp.send("Page.captureScreenshot", shotParams).get("data").asString
                                Files.write(framesDir.resolve(frameName(i)), Base64.getDecoder().decode(b64))
                                k += PARALLEL_WORKERS
                            }
                            page.close()
                            sigs
                        }
                    }
                }
            }.awaitAll()
        }

        return buildRuns(sigsPerWorker.first())
    }

    /** Index of the first frame for every distinct signature, in ascending order. */
    private fun uniqueFrameIndices(sigs: List<String>): List<Int> {
        val seen = HashSet<String>()
        val unique = ArrayList<Int>()
        sigs.forEachIndexed { i, s -> if (seen.add(s)) unique.add(i) }
        return unique
    }

    /**
     * Collapses consecutive frames with the same signature into one run.
     * A look that comes back later (loop wrap, ping-pong) is a separate run that points at the
     * same PNG, so it is rendered once but listed again in the concat file.
     */
    private fun buildRuns(sigs: List<String>): List<Run> {
        val firstOf = HashMap<String, Int>()
        sigs.forEachIndexed { i, s -> firstOf.putIfAbsent(s, i) }

        val runs = ArrayList<Run>()
        var runStart = 0
        for (i in 1..sigs.size) {
            if (i == sigs.size || sigs[i] != sigs[runStart]) {
                runs.add(Run(frameIndex = firstOf.getValue(sigs[runStart]), count = i - runStart))
                runStart = i
            }
        }
        return runs
    }

    private fun frameName(i: Int) = "%05d.png".format(i)

    /**
     * Encodes one picture per run, using the concat demuxer so each picture keeps its real duration
     * (run length / fps). ffmpeg now processes only the distinct pictures instead of every output frame,
     * and the GIF contains one frame per run with the right delay, so the file is smaller as well.
     */
    private fun makeGifFromRuns(runs: List<Run>, framesDir: Path, workDir: Path, fps: Double): Path {
        val gifPath = workDir.resolve("result.gif")
        val listPath = workDir.resolve("frames.txt")

        fun quoted(p: Path) = "'" + p.absolutePathString().replace("'", "'\\''") + "'"

        val list = buildString {
            for (run in runs) {
                append("file ").append(quoted(framesDir.resolve(frameName(run.frameIndex)))).append('\n')
                append("duration ").append(String.format(Locale.ROOT, "%.6f", run.count / fps)).append('\n')
            }
            // The concat demuxer ignores the duration of the last entry unless the file is repeated.
            append("file ").append(quoted(framesDir.resolve(frameName(runs.last().frameIndex)))).append('\n')
        }
        Files.writeString(listPath, list)

        val keyColor = "0x$KEY_HEX"

        // format=rgba BEFORE colorkey so keyed pixels get alpha = 0.
        val baseFilter = "format=rgba,colorkey=$keyColor:$KEY_SIMILARITY:0.0"

        val filterGraph = "[0:v]$baseFilter,split[stream][paletteSource];" +
                "[paletteSource]palettegen=max_colors=256:stats_mode=diff:reserve_transparent=1[palette];" +
                "[stream][palette]paletteuse=dither=bayer:bayer_scale=3:alpha_threshold=128:diff_mode=rectangle"

        executeCmd(
            "ffmpeg",
            "-y",
            "-threads", "0",
            "-f", "concat",
            "-safe", "0",
            "-i", listPath.absolutePathString(),
            "-filter_complex", filterGraph,
            gifPath.absolutePathString()
        )

        return gifPath
    }
}
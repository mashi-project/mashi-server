package com.mashiverse.images.playwright.combiners

import com.mashiverse.configs.PNG_HEIGHT
import com.mashiverse.configs.PNG_WIDTH
import com.mashiverse.data.db.daos.HistoryDao
import com.mashiverse.images.playwright.PlaywrightPool
import com.microsoft.playwright.Browser
import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.options.ScreenshotType
import com.microsoft.playwright.options.ViewportSize
import com.microsoft.playwright.options.WaitUntilState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.net.URI

class CompositeCombiner : KoinComponent {

    private companion object {
        const val ORIGIN = "http://localhost"

        // Replaces every <img> with a static render of its first frame (frame 0).
        // Animated GIF / WebP / APNG / AVIF would otherwise be captured at whatever frame
        // happens to be showing when the screenshot is taken.
        // If the image can't be decoded this way, it is left untouched.
        const val FIRST_FRAME_JS = """
            async () => {
                if (!('ImageDecoder' in window)) return;
                const imgs = Array.from(document.images);
                await Promise.all(imgs.map(async img => {
                    try {
                        const res = await fetch(img.src);
                        const type = (res.headers.get('content-type') || '').split(';')[0];
                        if (!(await ImageDecoder.isTypeSupported(type))) return;

                        const decoder = new ImageDecoder({ data: res.body, type });
                        const { image } = await decoder.decode({ frameIndex: 0 });

                        const canvas = document.createElement('canvas');
                        canvas.width = image.displayWidth;
                        canvas.height = image.displayHeight;
                        canvas.getContext('2d').drawImage(image, 0, 0);
                        image.close();
                        decoder.close();

                        const blob = await new Promise(r => canvas.toBlob(r, 'image/png'));
                        await new Promise((ok, fail) => {
                            img.onload = ok;
                            img.onerror = fail;
                            img.src = URL.createObjectURL(blob);
                        });
                    } catch (e) { /* keep original image */ }
                }));
            }
        """

        // Decodes every image and returns how many are broken (0 = all good).
        const val DECODE_JS = """
            async () => {
                const imgs = Array.from(document.images);
                await Promise.all(imgs.map(i => i.decode ? i.decode().catch(() => {}) : Promise.resolve()));
                return imgs.filter(i => !(i.complete && i.naturalWidth > 0 && i.naturalHeight > 0)).length;
            }
        """

        // Re-check after preparePage (it may touch the DOM/CSS) and let one frame paint.
        // Playwright's screenshot also forces a frame, so a single rAF is enough.
        const val FINALIZE_JS = """
            async () => {
                const imgs = Array.from(document.images);
                await Promise.all(imgs.map(i => i.decode ? i.decode().catch(() => {}) : Promise.resolve()));
                await new Promise(r => requestAnimationFrame(() => r()));
                return imgs.filter(i => !(i.complete && i.naturalWidth > 0 && i.naturalHeight > 0)).length;
            }
        """
    }

    private val historyDao by inject<HistoryDao>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun generateComposite(
        traitsBytes: List<Pair<String, ByteArray>>,
        wallet: String? = null
    ): ByteArray {

        // Serve the raw bytes through request interception instead of base64 data: URLs.
        // That avoids the +33% base64 inflation, the multi-MB HTML string, and the extra
        // copies through the Playwright protocol.
        val imageUrls = traitsBytes.indices.map { "$ORIGIN/img/$it" }

        val htmlContent = prepareHtml(
            urls = imageUrls,
            width = PNG_WIDTH,
            height = PNG_HEIGHT,
            isTransparent = true
        )

        val imageBytes = PlaywrightPool.execute { browser ->

            val context = browser.newContext(
                Browser.NewContextOptions()
                    .setViewportSize(ViewportSize(PNG_WIDTH, PNG_HEIGHT))
                    .setDeviceScaleFactor(1.0)
            )

            context.use { ctx ->

                val page = ctx.newPage()

                page.route("$ORIGIN/**") { route ->
                    val path = URI.create(route.request().url()).path
                    val index = if (path.startsWith("/img/")) path.removePrefix("/img/").toIntOrNull() else null

                    when {
                        path == "/index.html" -> route.fulfill(
                            Route.FulfillOptions()
                                .setContentType("text/html; charset=utf-8")
                                .setBody(htmlContent)
                        )

                        index != null && index in traitsBytes.indices -> route.fulfill(
                            Route.FulfillOptions()
                                .setContentType(traitsBytes[index].first)
                                .setBodyBytes(traitsBytes[index].second)
                        )

                        else -> route.fulfill(Route.FulfillOptions().setStatus(404))
                    }
                }

                // navigate() already waits for the load event (and thus for all <img> in the HTML).
                page.navigate(
                    "$ORIGIN/index.html",
                    Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD)
                )

                // Freeze animated traits on their first frame (frame 0).
                // Must run before the decode check and before preparePage.
                page.evaluate(FIRST_FRAME_JS)

                // Images must be decoded BEFORE preparePage (it styles based on them).
                val brokenBefore = (page.evaluate(DECODE_JS) as Number).toInt()
                check(brokenBefore == 0) { "$brokenBefore image(s) failed to load" }

                preparePage(page, getPngArgs())

                val brokenAfter = (page.evaluate(FINALIZE_JS) as Number).toInt()
                check(brokenAfter == 0) { "$brokenAfter image(s) failed to load after preparePage" }

                return@execute page.screenshot(
                    Page.ScreenshotOptions()
                        .setType(ScreenshotType.PNG)
                        .setOmitBackground(true)
                )
            }
        }

        // Launch separate background coroutine to save history asynchronously
        if (!wallet.isNullOrBlank()) {
            scope.launch {
                try {
                    historyDao.addHistory(
                        wallet = wallet,
                        image = imageBytes
                    )
                } catch (e: Exception) {
                    System.err.println("Failed to save composite to history for wallet $wallet: ${e.message}")
                }
            }
        }

        return imageBytes
    }
}
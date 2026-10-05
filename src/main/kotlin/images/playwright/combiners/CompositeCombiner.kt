package com.mashiverse.images.playwright.combiners

import com.mashiverse.configs.PNG_HEIGHT
import com.mashiverse.configs.PNG_WIDTH
import com.mashiverse.data.db.daos.HistoryDao
import com.mashiverse.images.playwright.PlaywrightPool
import com.microsoft.playwright.Browser
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.ScreenshotType
import com.microsoft.playwright.options.ViewportSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.*

class CompositeCombiner : KoinComponent {

    private val historyDao by inject<HistoryDao>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun generateComposite(
        traitsBytes: List<Pair<String, ByteArray>>,
        wallet: String? = null
    ): ByteArray {

        val imageUrls = traitsBytes.map { (mime, bytes) ->
            val b64 = Base64.getEncoder().encodeToString(bytes)
            "data:$mime;base64,$b64"
        }

        val htmlContent = prepareHtml(
            urls = imageUrls,
            width = PNG_WIDTH,
            height = PNG_HEIGHT,
            isTransparent = true
        )

        val imageBytes = PlaywrightPool.execute { browser ->

            val context = browser.newContext(
                Browser.NewContextOptions()
                    .setViewportSize(
                        ViewportSize(PNG_WIDTH, PNG_HEIGHT)
                    )
                    .setDeviceScaleFactor(1.0)
            )

            context.use { ctx ->

                val page = ctx.newPage()

                // ---------------------------------------------------------
                // Load page
                // ---------------------------------------------------------

                page.setContent(htmlContent)

                page.waitForLoadState(LoadState.LOAD)

                // ---------------------------------------------------------
                // Wait for every image to actually load
                // ---------------------------------------------------------

                page.waitForFunction(
                    """
                    () => Array.from(document.images).every(
                        img => img.complete &&
                               img.naturalWidth > 0 &&
                               img.naturalHeight > 0
                    )
                    """.trimIndent()
                )

                // ---------------------------------------------------------
                // Decode every image
                // ---------------------------------------------------------

                page.evaluate(
                    """
                    async () => {
                        const images = Array.from(document.images);

                        await Promise.all(
                            images.map(async (img) => {
                                if (typeof img.decode === 'function') {
                                    try {
                                        await img.decode();
                                    } catch (_) {
                                        // Ignore decode errors.
                                    }
                                }
                            })
                        );
                    }
                    """.trimIndent()
                )

                // ---------------------------------------------------------
                // Apply your page styling AFTER images are available
                // ---------------------------------------------------------

                preparePage(page, getPngArgs())

                // ---------------------------------------------------------
                // Wait again because preparePage may alter the DOM/CSS
                // ---------------------------------------------------------

                page.waitForFunction(
                    """
                    () => Array.from(document.images).every(
                        img => img.complete &&
                               img.naturalWidth > 0 &&
                               img.naturalHeight > 0
                    )
                    """.trimIndent()
                )

                page.evaluate(
                    """
                    async () => {
                        const images = Array.from(document.images);

                        await Promise.all(
                            images.map(async (img) => {
                                if (typeof img.decode === 'function') {
                                    try {
                                        await img.decode();
                                    } catch (_) {}
                                }
                            })
                        );

                        // Allow browser to paint the final composition.
                        await new Promise(requestAnimationFrame);
                        await new Promise(requestAnimationFrame);
                    }
                    """.trimIndent()
                )

                // ---------------------------------------------------------
                // Screenshot
                // ---------------------------------------------------------

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
package com.mashiverse.images.playwright

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One dedicated thread that owns one Playwright driver and one Chromium.
 * Everything Playwright-related (create, launch, use, close) happens on that thread only,
 * because Playwright Java is not thread-safe.
 * The browser is launched lazily and relaunched if it died or has served too many jobs.
 */
class PlaywrightWorkerInstance(val id: Int) : Closeable {

    private companion object {
        // Recycle Chromium after this many jobs to cap memory growth.
        const val MAX_USES_PER_BROWSER = 500

        val LAUNCH_ARGS = listOf(
            "--headless=new",
            "--no-sandbox",
            "--disable-setuid-sandbox",
            "--disable-dev-shm-usage",              // Docker: /dev/shm is often tiny and crashes Chromium
            "--force-color-profile=srgb",           // deterministic colors (matters for the colorkey)
            "--disable-extensions",
            "--disable-background-networking",
            "--disable-background-timer-throttling",
            "--disable-renderer-backgrounding",
            "--mute-audio"
        )
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Playwright-Worker-$id").apply { isDaemon = true }
    }
    val dispatcher = executor.asCoroutineDispatcher()

    // Only touched from this worker's thread.
    private var playwright: Playwright? = null
    private var browser: Browser? = null
    private var uses = 0

    /** Runs [block] on this worker's thread with a healthy browser. */
    suspend fun <T> run(block: (Browser) -> T): T = withContext(dispatcher) {
        val b = healthyBrowser()
        uses++
        block(b)
    }

    // Worker thread only.
    private fun healthyBrowser(): Browser {
        browser?.let { if (it.isConnected && uses < MAX_USES_PER_BROWSER) return it }
        closeBrowser()

        val options = BrowserType.LaunchOptions().setArgs(LAUNCH_ARGS)
        val pw = playwright ?: Playwright.create().also { playwright = it }
        val launched = try {
            pw.chromium().launch(options)
        } catch (e: Exception) {
            // The driver itself may be broken: recreate it once and retry.
            runCatching { pw.close() }
            playwright = null
            Playwright.create().also { playwright = it }.chromium().launch(options)
        }
        browser = launched
        uses = 0
        return launched
    }

    // Worker thread only.
    private fun closeBrowser() {
        runCatching { browser?.close() }
        browser = null
    }

    override fun close() {
        // Close on the owning thread, then stop it.
        runCatching {
            executor.submit {
                closeBrowser()
                runCatching { playwright?.close() }
                playwright = null
            }.get(10, TimeUnit.SECONDS)
        }
        executor.shutdownNow()
    }
}

object PlaywrightPool : Closeable {
    // Number of browsers allowed at once. Each Chromium is roughly 100-200 MB; adjust to host CPU/RAM.
    // Override without recompiling: PLAYWRIGHT_POOL_SIZE=6
    private val POOL_SIZE = System.getenv("PLAYWRIGHT_POOL_SIZE")?.toIntOrNull()?.coerceAtLeast(1) ?: 9

    // Creating a worker only creates its (idle) executor; browsers launch on first use,
    // so a failed launch can never poison this object's initialization.
    private val workers = List(POOL_SIZE) { PlaywrightWorkerInstance(it) }
    private val pool = Channel<PlaywrightWorkerInstance>(POOL_SIZE).also { channel ->
        workers.forEach { channel.trySend(it) }
    }

    init {
        Runtime.getRuntime().addShutdownHook(Thread { close() })
    }

    /**
     * Acquires a worker, runs [block] on that worker's dedicated thread, and returns the worker.
     * Same signature as before, so existing callers don't change.
     */
    suspend fun <T> execute(block: (Browser) -> T): T {
        val worker = pool.receive()
        try {
            return worker.run(block)
        } finally {
            // Capacity equals the number of workers, so this never blocks or fails with an exception;
            // after shutdown it just returns a failure result.
            pool.trySend(worker)
        }
    }

    /** Convenience: opens a context, runs [block], always closes the context. */
    suspend fun <T> executeInContext(
        options: Browser.NewContextOptions = Browser.NewContextOptions(),
        block: (BrowserContext) -> T
    ): T = execute { browser -> browser.newContext(options).use(block) }

    /**
     * Optional: call once at application start to launch all browsers in parallel,
     * so the first requests don't pay the Chromium startup cost.
     * Safe while requests are running: each worker has a single thread, so jobs never overlap.
     */
    suspend fun warmUp() = coroutineScope {
        workers.map { worker -> async { worker.run { } } }.awaitAll()
    }

    override fun close() {
        pool.close()
        workers.forEach { it.close() }
    }
}
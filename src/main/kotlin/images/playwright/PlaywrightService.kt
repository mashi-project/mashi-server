package com.mashiverse.images.playwright

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.Executors

class PlaywrightWorkerInstance(val id: Int) : Closeable {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Playwright-Worker-$id").apply { isDaemon = true }
    }
    val dispatcher = executor.asCoroutineDispatcher()

    val playwright: Playwright = Playwright.create()
    val browser: Browser

    init {
        val launchOptions = BrowserType.LaunchOptions().setArgs(
            listOf(
                "--headless=new",
                "--no-sandbox",
                "--disable-setuid-sandbox",
                "--disable-extensions",
                "--disable-background-networking",
                "--disable-background-timer-throttling",
                "--disable-renderer-backgrounding",
                "--mute-audio"
            )
        )
        browser = playwright.chromium().launch(launchOptions)
    }

    override fun close() {
        runCatching { browser.close() }
        runCatching { playwright.close() }
        executor.shutdown()
    }
}

object PlaywrightPool : Closeable {
    // Number of browsers to keep warm simultaneously. Adjust according to host CPU/RAM.
    private const val POOL_SIZE = 5

    private val pool = Channel<PlaywrightWorkerInstance>(POOL_SIZE)
    private val workers = mutableListOf<PlaywrightWorkerInstance>()

    init {
        repeat(POOL_SIZE) { id ->
            val worker = PlaywrightWorkerInstance(id)
            workers.add(worker)
            pool.trySend(worker)
        }

        Runtime.getRuntime().addShutdownHook(Thread { close() })
    }

    /**
     * Acquires a worker from the pool, runs the block inside that worker's
     * dedicated thread context, and returns the worker back to the pool.
     */
    suspend fun <T> execute(block: (Browser) -> T): T {
        val worker = pool.receive()
        return try {
            withContext(worker.dispatcher) {
                block(worker.browser)
            }
        } finally {
            pool.send(worker)
        }
    }

    override fun close() {
        workers.forEach { it.close() }
        pool.close()
    }
}
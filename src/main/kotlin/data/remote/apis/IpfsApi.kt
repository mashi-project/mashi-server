package com.mashiverse.data.remote.apis

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.milliseconds

class IpfsApi : KoinComponent {
    private val client by inject<HttpClient>()

    suspend fun getImageSrc(imageUrl: String, maxRetries: Int = 5): ByteArray? {

        for (attempt in 0 until maxRetries) {
            try {
                val response =
                    client.get(imageUrl.replace("ipfs://","https://round-peach-hippopotamus.myfilebase.com/ipfs/").replace("https://ipfs.io/", "https://round-peach-hippopotamus.myfilebase.com/"))

                if (response.status == HttpStatusCode.OK) {
                    val bytes = response.bodyAsBytes()

                    if (isHtmlResponse(bytes)) {
                        println("Attempt ${attempt + 1}: Received HTML instead of image data for $imageUrl")
                        kotlinx.coroutines.delay((1000L * (attempt + 1)).milliseconds)
                        continue
                    }

                    return bytes
                }

                if (response.status == HttpStatusCode.NotFound) {
                    break
                }

            } catch (e: Exception) {
                println("Attempt ${attempt + 1} failed for $imageUrl: ${e.localizedMessage}")
            }
        }

        return null
    }

    private fun isHtmlResponse(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val sample = String(bytes.take(50).toByteArray(), Charsets.UTF_8).trim().lowercase()
        return sample.startsWith("<!doctype") || sample.startsWith("<html") || sample.startsWith("<head")
    }
}
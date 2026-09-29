package com.mashiverse.data.remote.apis

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class IpfsApi : KoinComponent {
    private val client by inject<HttpClient>()

    suspend fun getImageSrc(imageUrl: String, maxRetries: Int = 5): ByteArray? {
        for (attempt in 0 until maxRetries) {
            try {
                val response = client.get(
                    imageUrl.replace("ipfs.io", "round-peach-hippopotamus.myfilebase.com")
                        .replace("ipfs://", "https://round-peach-hippopotamus.myfilebase.com/ipfs/")
                )

                if (response.status == HttpStatusCode.OK) {
                    return response.bodyAsBytes()
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
}
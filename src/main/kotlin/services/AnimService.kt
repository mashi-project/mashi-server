package com.mashiverse.services

import com.mashiverse.data.models.Asset
import com.mashiverse.data.models.Colors
import com.mashiverse.data.models.Mashup
import com.mashiverse.data.remote.apis.IpfsApi
import com.mashiverse.data.remote.dto.NotifyDto
import com.mashiverse.data.repos.ImageRepo
import com.mashiverse.utils.helpers.isImageAnimated
import data.models.DownloadType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class AnimService : KoinComponent {
    private val ipfsApi by inject<IpfsApi>()
    private val imageRepo by inject<ImageRepo>()

    // Limits parallel gateway requests so we don't get throttled
    private val ipfsSemaphore = Semaphore(4)

    private fun getAssets(notifyDto: NotifyDto): List<Asset> {
        return with(notifyDto.assets) {
            listOf(
                "eyes" to this.eyes,
                "head" to this.head,
                "upper" to this.upper,
                "bottom" to this.bottom,
                "cape" to this.cape,
                "hair_back" to this.hairBack,
                "hair_front" to this.hairFront,
                "hat" to this.hat,
                "left_accessory" to this.leftAccessory,
                "right_accessory" to this.rightAccessory,
                "background" to this.background
            ).mapNotNull { (key, value) ->
                if (!value.isNullOrBlank()) Asset(key, value) else null
            }
        }
    }

    suspend fun checkIfAnyAnimated(notifyDto: NotifyDto): Boolean {
        return try {
            // Several traits share the same CID, so fetch each unique image only once
            val assets: List<Asset> = getAssets(notifyDto).distinctBy { it.image }
            if (assets.isEmpty()) return false

            val bytes: List<ByteArray> = coroutineScope {
                assets.map { asset ->
                    async {
                        ipfsSemaphore.withPermit {
                            ipfsApi.getImageSrc(imageUrl = asset.image, maxRetries = 5)
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            bytes.any { isImageAnimated(bytes = it).isAnimated }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Error in checkIfAnyAnimated: ${e::class.simpleName}: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    suspend fun generateAnim(notifyDto: NotifyDto): ByteArray? {
        return try {
            val assets = getAssets(notifyDto)
            val colors = Colors("#A15A05", "#C9C937", "#8E8EC1")
            val mashup = Mashup(
                colors = colors,
                traits = assets
            )

            imageRepo.getImage(
                mashup = mashup,
                downloadType = DownloadType.GIF
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Error in generateAnim: ${e::class.simpleName}: ${e.message}")
            e.printStackTrace()
            null
        }
    }
}
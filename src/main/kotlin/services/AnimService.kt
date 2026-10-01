package com.mashiverse.services

import com.mashiverse.data.models.Asset
import com.mashiverse.data.models.Colors
import com.mashiverse.data.models.Mashup
import com.mashiverse.data.remote.apis.IpfsApi
import com.mashiverse.data.remote.dto.NotifyDto
import com.mashiverse.data.repos.ImageRepo
import com.mashiverse.utils.helpers.isImageAnimated
import data.models.DownloadType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class AnimService : KoinComponent {
    private val ipfsApi by inject<IpfsApi>()
    private val imageRepo by inject<ImageRepo>()

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
                if (!value.isNullOrBlank()) {
                    Asset(key, value)
                } else null
            }
        }
    }

    suspend fun checkIfAnyAnimated(notifyDto: NotifyDto): Boolean {
        return try {
            val assets: List<Asset> = getAssets(notifyDto)
            if (assets.isEmpty()) return false

            val bytes: List<ByteArray> = coroutineScope {
                assets.map { asset ->
                    async { ipfsApi.getImageSrc(imageUrl = asset.image, maxRetries = 5) }
                }.awaitAll()
                    .filterNotNull()
            }

            bytes.any { isImageAnimated(bytes = it).isAnimated }
        } catch (e: Exception) {
            println("Error in checkIfAnyAnimated: ${e.message}")
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
                downloadType = DownloadType.SMALLER_GIF
            )
        } catch (e: Exception) {
            println("Error in generateAnim: ${e.message}")
            e.printStackTrace()
            null
        }
    }
}
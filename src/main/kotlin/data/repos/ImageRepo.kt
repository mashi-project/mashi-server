package com.mashiverse.data.repos

import com.mashiverse.configs.LAYER_ORDER
import com.mashiverse.data.db.daos.ImageDao
import com.mashiverse.data.models.*
import com.mashiverse.data.remote.apis.IpfsApi
import com.mashiverse.images.helpers.*
import com.mashiverse.images.playwright.combiners.AnimCombiner
import com.mashiverse.images.playwright.combiners.CompositeCombiner
import com.mashiverse.utils.helpers.readFile
import com.mashiverse.utils.helpers.rmDir
import com.mashiverse.utils.helpers.writeFile
import data.models.DownloadType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*

class ImageRepo : KoinComponent {
    private val animCombiner by inject<AnimCombiner>()
    private val compositeCombiner by inject<CompositeCombiner>()
    private val imageDao by inject<ImageDao>()
    private val ipfsApi by inject<IpfsApi>()

    suspend fun getAsset(asset: Asset, colors: Colors): ImageDetails {
        return withContext(Dispatchers.IO) {
            val name = asset.name.lowercase()
            val url = asset.image
            try {
                val imageId = url.split("/").last()

                var data = imageDao.getImage(imageId)
                var imageType = imageDao.getImageType(imageId)

                if (data == null || imageType == null) {
                    println("Fetching from IPFS: $url")
                    val rawData = ipfsApi.getImageSrc(url) ?: run {
                        System.err.println("❌ IPFS returned null for: $url")
                        return@withContext ImageDetails(imageType = ImageType.UNKNOWN)
                    }

                    imageType = getImageType(rawData)
                    if (imageType == ImageType.UNKNOWN) {
                        System.err.println("❌ Unknown image type detected for: $url")
                        return@withContext ImageDetails(imageType = ImageType.UNKNOWN)
                    }

                    var storageData = rawData
                    var storageType = imageType

                    if (imageType == ImageType.SVG) {
                        try {
                            storageData = SvgCorrector.processSvg(rawData)
                        } catch (e: Exception) {
                            System.err.println("❌ SVG correction failed for $url: ${e.message}")
                        }
                    } else if (imageType != ImageType.WEBP) {
                        try {
                            storageData = convertToWebp(rawData, imageType)
                            storageType = ImageType.WEBP
                        } catch (e: Exception) {
                            System.err.println("❌ WebP conversion failed for $url: ${e.message}")
                        }
                    }

                    imageDao.addImage(imageId, storageData, storageType)
                    data = storageData
                    imageType = storageType
                }

                if (imageType == ImageType.SVG) {
                    data = replaceColors(
                        data = data, body = colors.base, eyes = colors.eyes, hair = colors.hair
                    )
                }

                ImageDetails(name = name, data = data, imageType = imageType)
            } catch (e: Exception) {
                System.err.println("💥 Failed to process asset [$name] from $url: ${e.message}")
                e.printStackTrace() // Prints full stack trace so you see the exact line causing the failure
                ImageDetails(imageType = ImageType.UNKNOWN)
            }
        }
    }

    suspend fun getImageData(
        mashup: Mashup,
        downloadType: DownloadType = DownloadType.PNG,
        wallet: String? = null,
    ): Pair<ByteArray, Long>? = withContext(Dispatchers.IO) {
        val assets = mashup.traits
        val colors = mashup.colors

        if (assets.isEmpty()) return@withContext null

        val assetJobs = assets.map { asset -> async { getAsset(asset, colors) } }
        val images = assetJobs.awaitAll().filter { it.imageType != ImageType.UNKNOWN }

        // Safely map layer order, omitting missing/failed layers without crashing
        val traits = LAYER_ORDER.mapNotNull { name ->
            images.firstOrNull { it.name == name }?.data
        }.toMutableList()

        if (traits.isEmpty()) return@withContext null

        val imagesWithMime = traits.map { bytes ->
            val image = images.first { it.data.contentEquals(bytes) }
            image.copy(mimeType = getMime(image.imageType))
        }

        val traitsWithMime =
            imagesWithMime.filter { it.mimeType != null && it.data != null }.map { it.mimeType!! to it.data!! }.toList()

        // 1. PNG: In-memory pipeline, zero disk writes
        if (downloadType == DownloadType.PNG) {
            val bytes = compositeCombiner.generateComposite(traitsWithMime, wallet = wallet)
            return@withContext Pair(bytes, bytes.size.toLong())
        }

        // 2. GIF: Render in system temp directory (/tmp) instead of /dev/shm
        val sysTmpDir = Paths.get(System.getProperty("java.io.tmpdir")).resolve("mashi-temp")
        Files.createDirectories(sysTmpDir)

        val uniqueDir = Files.createTempDirectory(sysTmpDir, "anim-")

        try {
            traitsWithMime.forEachIndexed { index, (mime, bytes) ->
                val b64 = Base64.getEncoder().encodeToString(bytes)
                val filePath = uniqueDir.resolve(index.toString())
                val fileContent = "data:$mime;base64,$b64".toByteArray(Charsets.UTF_8)
                writeFile(filePath, fileContent)
            }

            val gifPath: Path = animCombiner.generateAnim(uniqueDir, wallet = wallet)

            // Read into byte array BEFORE rmDir destroys the file
            val bytes = readFile(gifPath)
            Pair(bytes, bytes.size.toLong())
        } finally {
            rmDir(uniqueDir)
        }
    }

    /**
     * Backward-compatible helper returning ByteArray.
     */
    suspend fun getImage(
        mashup: Mashup,
        downloadType: DownloadType = DownloadType.PNG,
    ): ByteArray? {
        return getImageData(mashup, downloadType)?.first
    }
}
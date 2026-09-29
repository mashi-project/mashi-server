package com.mashiverse.data.repos

import com.mashiverse.configs.LAYER_ORDER
import com.mashiverse.data.db.daos.ImageDao
import com.mashiverse.data.models.*
import com.mashiverse.data.remote.apis.IpfsApi
import com.mashiverse.images.helpers.SvgCorrector
import com.mashiverse.images.helpers.convertToWebp
import com.mashiverse.images.helpers.getImageType
import com.mashiverse.images.helpers.replaceColors
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
            try {
                val name = asset.name.lowercase()
                val url = asset.image
                val imageId = url.split("/").last()

                var data = imageDao.getImage(imageId)
                if (data == null) {
                    data = ipfsApi.getImageSrc(url) ?: return@withContext ImageDetails(imageType = ImageType.UNKNOWN)
                    val imageType = getImageType(data)

                    var newData: ByteArray? = null
                    var newImageType: ImageType? = null

                    if (imageType != ImageType.SVG && imageType != ImageType.UNKNOWN) {
                        newData = convertToWebp(data, imageType)
                        newImageType = ImageType.WEBP
                    }

                    if (imageType == ImageType.GIF) {
                        newData = SvgCorrector.processSvg(data)
                        newImageType = ImageType.GIF
                    }

                    if (newData != null && newImageType != null) {
                        imageDao.addImage(imageId, newData, newImageType)
                    }
                }

                val imageType = imageDao.getImageType(imageId) ?: throw Exception("Image type $imageId not found")
                if (imageType == ImageType.SVG) {
                    data = replaceColors(
                        data = data, body = colors.base, eyes = colors.eyes, hair = colors.hair
                    )
                }

                ImageDetails(name = name, data = data, imageType = imageType)
            } catch (e: Exception) {
                System.err.println("Failed to fetch asset: ${e.message}")
                ImageDetails(imageType = ImageType.UNKNOWN)
            }
        }
    }

    suspend fun getImageData(
        mashup: Mashup,
        downloadType: DownloadType = DownloadType.PNG
    ): Pair<ByteArray, Long>? = withContext(Dispatchers.IO) {
        val assets = mashup.traits
        val colors = mashup.colors

        if (assets.isEmpty()) return@withContext null

        val assetJobs = assets.map { asset -> async { getAsset(asset, colors) } }
        val images = assetJobs.awaitAll().filter { it.imageType != ImageType.UNKNOWN }

        val traits = LAYER_ORDER.map { name -> images.first { it.name == name }.data }.toMutableList()

        val imagesWithMime = traits.map { bytes ->
            val image = images.first { it.data.contentEquals(bytes) }
            image.copy(mimeType = image.mimeType)
        }

        val traitsWithMime =
            imagesWithMime.filter { it.mimeType != null && it.data != null }.map { it.mimeType!! to it.data!! }.toList()

        // 1. PNG: In-memory pipeline, zero disk writes
        if (downloadType == DownloadType.PNG) {
            val bytes = compositeCombiner.generateComposite(traitsWithMime)
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

            val isLowerRes = downloadType == DownloadType.SMALLER_GIF
            val gifPath: Path = animCombiner.generateAnim(uniqueDir, isLowerRes)

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
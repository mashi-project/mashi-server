package com.mashiverse.data.repos

import com.mashiverse.configs.LAYER_ORDER
import com.mashiverse.data.db.daos.ImageDao
import com.mashiverse.data.models.Asset
import com.mashiverse.data.models.Colors
import com.mashiverse.data.models.ImageType
import com.mashiverse.data.models.Mashup
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

    suspend fun getAsset(asset: Asset, colors: Colors): Pair<String, ByteArray>? {
        return withContext(Dispatchers.IO) {
            try {
                val name = asset.name.lowercase()
                val url = asset.image

                var data = imageDao.getImage(url)
                if (data == null) {
                    data = ipfsApi.getImageSrc(url) ?: return@withContext null
                    val imageType = getImageType(data)
                    if (imageType != ImageType.UNKNOWN) {
                        imageDao.addImage(url, data)
                    }
                }

                val imageType = getImageType(data)
                if (imageType == ImageType.SVG) {
                    data = replaceColors(
                        data = data, body = colors.base, eyes = colors.eyes, hair = colors.hair
                    )
                }
                Pair(name, data)
            } catch (e: Exception) {
                System.err.println("Failed to fetch asset: ${e.message}")
                null
            }
        }
    }

    /**
     * Prepares composite data directly.
     * PNG is rendered completely in-memory.
     * GIF reads final output into memory before rmDir deletes the temp workspace.
     */
    suspend fun getImageData(
        mashup: Mashup,
        downloadType: DownloadType = DownloadType.PNG,
        mintedName: String? = null
    ): Pair<ByteArray, Long>? = withContext(Dispatchers.IO) {
        val assets = mashup.traits
        val colors = mashup.colors

        if (assets.isEmpty()) return@withContext null

        val assetJobs = assets.map { asset -> async { getAsset(asset, colors) } }
        val srcs = assetJobs.awaitAll().filterNotNull().toMap()

        val traits = LAYER_ORDER.mapNotNull { name -> srcs[name] }.toMutableList()
        if (!mintedName.isNullOrEmpty()) {
            traits.add(getMintedTrait(mintedName))
        }

        val traitsWithMime = traits.map { bytes -> Pair(getMime(bytes), bytes) }

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
        mintedName: String? = null
    ): ByteArray? {
        return getImageData(mashup, downloadType, mintedName)?.first
    }
}
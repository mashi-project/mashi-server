package images.services

import com.mashiverse.configs.imageSemaphore
import com.mashiverse.data.models.Mashup
import com.mashiverse.data.remote.apis.MashitApi
import com.mashiverse.data.repos.ImageRepo
import data.models.DownloadType
import data.models.mappers.toMashup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ImageService : KoinComponent {
    private val imageRepo by inject<ImageRepo>()

    /**
     * Retrieves the composite image as a ByteArray along with its content length.
     * Prevents stream truncation and race conditions during upload.
     */
    suspend fun requestCompositeData(
        wallet: String? = null,
        mashup: Mashup? = null,
        downloadType: DownloadType,
    ): Pair<ByteArray, Long>? {
        return withContext(Dispatchers.IO) {
            try {
                val input = when {
                    wallet != null -> MashitApi().getMashup(wallet)?.toMashup()
                    mashup != null -> mashup
                    else -> return@withContext null
                } ?: return@withContext null

                imageSemaphore.withPermit {
                    imageRepo.getImageData(
                        mashup = input,
                        downloadType = downloadType,
                    )
                }
            } catch (e: Exception) {
                println("❌ Balancer Error: ${e.message}")
                null
            }
        }
    }

    /**
     * Backward-compatible helper returning raw ByteArray.
     */
    suspend fun requestComposite(
        wallet: String? = null,
        mashup: Mashup? = null,
        downloadType: DownloadType
    ): ByteArray? {
        return requestCompositeData(wallet, mashup, downloadType)?.first
    }
}
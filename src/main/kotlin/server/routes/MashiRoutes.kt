package com.mashiverse.server.routes

import com.mashiverse.data.db.daos.HistoryDao
import com.mashiverse.data.db.daos.ImageDao
import com.mashiverse.data.db.daos.UserDao
import com.mashiverse.data.models.ImageType
import com.mashiverse.data.remote.apis.IpfsApi
import com.mashiverse.images.helpers.SvgCorrector
import com.mashiverse.images.helpers.convertToWebp
import com.mashiverse.images.helpers.getImageType
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject
import java.util.*

@Serializable
data class HistoryItemResponse(
    val id: String,
    val wallet: String,
    val imageUrl: String,
    val timestamp: String
)

@Serializable
data class HistoryPageResponse(
    val items: List<HistoryItemResponse>,
    val hasNextPage: Boolean
)

fun Application.mashiRoutes() {
    val userDao by inject<UserDao>()
    val imageDao by inject<ImageDao>()
    val historyDao by inject<HistoryDao>()
    val ipfsApi by inject<IpfsApi>()

    routing {
        get("/api/mashi/app/wallet/{user_id}") {
            try {
                val userId = call.parameters["user_id"] ?: return@get call.respond(HttpStatusCode.BadRequest)

                val wallet = userDao.getWallet(userId = userId.toLong())

                if (wallet != null) {
                    call.respondText(wallet, ContentType.Text.Plain)
                } else {
                    call.respond(HttpStatusCode.NotFound, "Wallet not found")
                }
            } catch (e: Exception) {
                println(e.localizedMessage)
                call.respond(HttpStatusCode.InternalServerError)
            }
        }

        // Paginated history route returning image links and hasNextPage metadata
        get("/api/mashi/app/history/{wallet}") {
            try {
                val wallet = call.parameters["wallet"] ?: return@get call.respond(HttpStatusCode.BadRequest)

                val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                val offset = (page - 1) * limit

                // Fetch limit + 1 to efficiently check if a subsequent page exists
                val historyList = historyDao.getHistoryByWalletPaginated(wallet, limit + 1, offset)

                val hasNextPage = historyList.size > limit
                val actualList = if (hasNextPage) historyList.dropLast(1) else historyList

                // Map records to response objects
                val responseItems = actualList.map { record ->
                    HistoryItemResponse(
                        id = record.id.toString(),
                        wallet = record.wallet,
                        imageUrl = "https://katzemon.com/api/mashi/app/history/image/${record.id}",
                        timestamp = record.timestamp.toString()
                    )
                }

                val response = HistoryPageResponse(
                    items = responseItems,
                    hasNextPage = hasNextPage
                )

                call.respond(response)
            } catch (e: Exception) {
                println(e.localizedMessage)
                call.respond(HttpStatusCode.InternalServerError)
            }
        }

        // Route to serve the history image bytes by history record ID
        get("/api/mashi/app/history/image/{id}") {
            try {
                val idStr = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val id = UUID.fromString(idStr)

                val record = historyDao.getHistoryById(id)

                if (record?.image != null) {
                    val imageType = getImageType(record.image)
                    when (imageType) {
                        ImageType.SVG -> call.respondBytes(record.image, ContentType.Image.SVG)
                        ImageType.WEBP -> call.respondBytes(record.image, ContentType.Image.WEBP)
                        else -> call.respondBytes(record.image, ContentType.Image.WEBP)
                    }
                } else {
                    call.respond(HttpStatusCode.NotFound, "Image not found")
                }
            } catch (e: Exception) {
                println(e.localizedMessage)
                call.respond(HttpStatusCode.InternalServerError)
            }
        }

        get("/api/mashi/app/image/type/{image_id}") {
            try {
                val imageId = call.parameters["image_id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val imageUrl = "https://round-peach-hippopotamus.myfilebase.com/ipfs/${imageId}"

                var imageType = imageDao.getImageType(imageId)

                if (imageType == null) {
                    val data = ipfsApi.getImageSrc(imageUrl)

                    if (data != null) {
                        val detectedType = getImageType(data)
                        if (detectedType != ImageType.UNKNOWN) {
                            var storageData = data
                            var storageType = detectedType

                            if (detectedType == ImageType.SVG) {
                                try {
                                    storageData = SvgCorrector.processSvg(data)
                                } catch (e: Exception) {
                                    System.err.println("Failed to process SVG for $imageId: ${e.message}")
                                }
                            } else if (detectedType != ImageType.WEBP) {
                                try {
                                    storageData = convertToWebp(data, detectedType)
                                    storageType = ImageType.WEBP
                                } catch (e: Exception) {
                                    System.err.println("Failed to convert image to WebP for $imageId: ${e.message}")
                                }
                            }

                            imageDao.addImage(imageId, storageData, storageType)
                            imageType = storageType
                        }
                    }
                }

                if (imageType != null) {
                    call.respondText(imageType.name, ContentType.Text.Plain)
                } else {
                    call.respond(HttpStatusCode.NotFound, "Type not found")
                }
            } catch (e: Exception) {
                println(e.localizedMessage)
                call.respond(HttpStatusCode.InternalServerError)
            }
        }

        get("/api/mashi/app/image/{image_id}") {
            try {
                val imageId = call.parameters["image_id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val data = imageDao.getImage(imageId)

                if (data != null) {
                    val imageType = imageDao.getImageType(imageId)
                    when (imageType) {
                        ImageType.SVG -> call.respondBytes(data, ContentType.Image.SVG)
                        ImageType.WEBP -> call.respondBytes(data, ContentType.Image.WEBP)
                        else -> call.respond(HttpStatusCode.NotFound, "Image not found")
                    }
                } else {
                    call.respond(HttpStatusCode.NotFound, "Image not found")
                }
            } catch (e: Exception) {
                println(e.localizedMessage)
                call.respond(HttpStatusCode.InternalServerError)
            }
        }
    }
}
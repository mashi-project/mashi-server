package com.mashiverse.server.routes

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import com.mashiverse.data.db.daos.HistoryDao
import com.mashiverse.data.db.daos.ImageDao
import com.mashiverse.data.db.daos.UserDao
import com.mashiverse.data.models.ImageType
import com.mashiverse.data.remote.apis.IpfsApi
import com.mashiverse.discord.MashiBot
import com.mashiverse.images.helpers.SvgCorrector
import com.mashiverse.images.helpers.convertToWebp
import com.mashiverse.images.helpers.getImageType
import data.models.DownloadType
import images.services.ImageService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class HistoryItemResponse(
    val id: String,
    val wallet: String,
    val imageUrl: String,
    val timestamp: String,
    val imageType: String,
)

@Serializable
data class HistoryPageResponse(
    val items: List<HistoryItemResponse>,
    val hasNextPage: Boolean
)

// Background scope for slow mashup generation (GIFs), independent of the HTTP request lifecycle
private val generateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

// Tracks "wallet:type" jobs currently running so retries/duplicates are ignored
private val inFlight = ConcurrentHashMap.newKeySet<String>()

fun Application.mashiRoutes() {
    val userDao by inject<UserDao>()
    val imageDao by inject<ImageDao>()
    val historyDao by inject<HistoryDao>()
    val ipfsApi by inject<IpfsApi>()
    val imageService by inject<ImageService>()

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

        delete("/api/mashi/app/history/delete/{wallet}") {
            try {
                val wallet = call.parameters["wallet"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                historyDao.deleteHistoryByWallet(wallet)
                call.respond(HttpStatusCode.OK)
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

                val responseItems = actualList.map { record ->
                    val detectedType = if (record.image != null) getImageType(record.image) else ImageType.UNKNOWN

                    HistoryItemResponse(
                        id = record.id.toString(),
                        wallet = record.wallet,
                        imageUrl = "https://katzemon.com/api/mashi/app/history/image/${record.id}",
                        timestamp = record.timestamp.toString(),
                        imageType = detectedType.name
                    )
                }

                call.respond(
                    HistoryPageResponse(
                        items = responseItems,
                        hasNextPage = hasNextPage
                    )
                )
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

        delete("/api/mashi/app/history/image/delete/{id}") {
            try {
                val idStr = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                val id = UUID.fromString(idStr)
                historyDao.deleteHistory(id)
                call.respond(HttpStatusCode.OK)
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

        get("/api/mashi/app/generate/{wallet_id}") {
            try {
                val walletId = call.parameters["wallet_id"] ?: return@get call.respond(HttpStatusCode.BadRequest)

                val type = try {
                    DownloadType.valueOf(call.parameters["type"] ?: ImageType.PNG.name)
                } catch (e: IllegalArgumentException) {
                    return@get call.respond(HttpStatusCode.BadRequest, "Invalid type")
                }
                val discord = call.parameters["discord"].toBoolean()

                val key = "$walletId:${type.name}"

                // add() returns false if the key is already present -> duplicate/retry
                if (!inFlight.add(key)) {
                    return@get call.respond(HttpStatusCode.Accepted, "Already generating")
                }

                generateScope.launch {
                    try {
                        val data = imageService.requestCompositeData(wallet = walletId, downloadType = type)

                        if (discord) {
                            MashiBot.getInstance().sendMashup(data = data, downloadType = type, wallet = walletId)
                        }

                        // Ensure your mobile app subscribes to this topic (walletId)
                        runCatching {
                            val fcmMessage = Message.builder()
                                .setTopic(walletId)
                                .setNotification(
                                    Notification.builder()
                                        .setTitle("Mashup Ready! 🎉")
                                        .setBody("See it in history tab")
                                        .build()
                                )
                                .putData("walletId", walletId)
                                .putData("type", type.name)
                                .build()
                            FirebaseMessaging.getInstance().send(fcmMessage)
                        }.onFailure {
                            System.err.println("Failed to send FCM notification: ${it.message}")
                        }
                    } catch (e: Exception) {
                        println("Generate failed for $key: ${e.localizedMessage}")
                    } finally {
                        inFlight.remove(key)
                    }
                }

                // Respond right away; the app is notified via FCM when the mashup is ready
                call.respond(HttpStatusCode.Accepted)
            } catch (e: Exception) {
                println(e.localizedMessage)
                call.respond(HttpStatusCode.InternalServerError)
            }
        }
    }
}
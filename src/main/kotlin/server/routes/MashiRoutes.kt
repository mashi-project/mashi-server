package com.mashiverse.server.routes

import com.google.firebase.messaging.ApnsConfig
import com.google.firebase.messaging.Aps
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
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
import io.ktor.server.request.receive
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject
import java.util.concurrent.ConcurrentHashMap


// Background scope for slow mashup generation (GIFs), independent of the HTTP request lifecycle
private val generateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

// Tracks "wallet:type" jobs currently running so retries/duplicates are ignored
private val inFlight = ConcurrentHashMap.newKeySet<String>()

@Serializable
data class WalletConnectRequest(
    val address: String,
    val params: Map<String, String> = emptyMap(),
    val message: String? = null,
    val signature: String? = null,
)

private val WALLET_REGEX = Regex("^0x[a-fA-F0-9]{40}$")

fun Application.mashiRoutes() {
    val userDao by inject<UserDao>()
    val imageDao by inject<ImageDao>()
    val ipfsApi by inject<IpfsApi>()
    val imageService by inject<ImageService>()

    routing {
        post("/api/mashi/app/wallet/connect") {
            try {
                val body = call.receive<WalletConnectRequest>()

                val wallet = body.address.trim()
                if (!WALLET_REGEX.matches(wallet)) {
                    return@post call.respond(HttpStatusCode.BadRequest, "Invalid wallet")
                }

                val userId = body.params["userId"]?.toLongOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing or invalid userId")

                if (userDao.getWallet(userId) != null) {
                    return@post call.respond(HttpStatusCode.Conflict, "You already have wallet")
                }

                if (userDao.isExist(wallet.lowercase())) {
                    return@post call.respond(HttpStatusCode.Conflict, "Wallet already taken")
                }

                userDao.connectWallet(userId, wallet.lowercase())
                call.respond(HttpStatusCode.OK, "Wallet connected")
            } catch (e: Exception) {
                println(e.localizedMessage)
                call.respond(HttpStatusCode.InternalServerError, "Something went wrong")
            }
        }

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
                                .setTopic(walletId.lowercase())
                                .setNotification(
                                    Notification.builder()
                                        .setTitle("Mashup Ready! 🎉")
                                        .setBody("You can download/share it on history tab")
                                        .build()
                                )
                                .setApnsConfig(
                                    ApnsConfig.builder()
                                        .putHeader("apns-priority", "10")
                                        .setAps(Aps.builder().setSound("default").build())
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
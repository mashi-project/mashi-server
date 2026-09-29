package com.mashiverse.server.routes

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
import org.koin.ktor.ext.inject

fun Application.mashiRoutes() {
    val userDao by inject<UserDao>()
    val imageDao by inject<ImageDao>()
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

        get("/api/mashi/app/image/type/{image_id}") {
            try {
                val imageId = call.parameters["image_id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val imageUrl = "https://round-peach-hippopotamus.myfilebase.com/ipfs/${imageId}"

                var imageType = imageDao.getImageType(imageId)

                if (imageType == null) {
                    val data = ipfsApi.getImageSrc(imageUrl)

                    if (data != null) {
                        val tempImageType = getImageType(data)

                        var newData: ByteArray? = null
                        var newImageType: ImageType? = null

                        if (tempImageType != ImageType.SVG && tempImageType != ImageType.UNKNOWN) {
                            newData = convertToWebp(data, tempImageType)
                            newImageType = ImageType.WEBP
                        }

                        if (tempImageType == ImageType.GIF) {
                            newData = SvgCorrector.processSvg(data)
                            newImageType = ImageType.GIF
                        }

                        if (newData != null && newImageType != null) {
                            imageDao.addImage(imageId, newData, newImageType)
                        }

                        imageType = newImageType
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
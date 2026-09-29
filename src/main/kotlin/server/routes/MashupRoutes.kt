package com.mashiverse.server.routes

import com.mashiverse.data.models.Mashup
import data.models.DownloadType
import data.remote.dto.MashupDto
import images.services.ImageService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.jvm.javaio.*
import org.koin.ktor.ext.inject
import java.io.ByteArrayInputStream

fun Application.mashupRoutes() {
    val imageService by inject<ImageService>()

    routing {
        post("/api/mashi/app_mashup") {
            try {
                val downloadTypeParam = call.request.queryParameters["download_type"] ?: "png"
                val mintedName = call.request.queryParameters["minted_name"]

                val data = call.receive<MashupDto>()

                val mashup = Mashup(
                    colors = data.colors,
                    traits = data.assets
                )

                val mediaType = when (downloadTypeParam.lowercase()) {
                    "png" -> ContentType.Image.PNG
                    "gif" -> ContentType.Image.GIF
                    else -> {
                        call.respond(HttpStatusCode.BadRequest)
                        return@post
                    }
                }

                val downloadType = DownloadType.valueOf(downloadTypeParam.uppercase())

                val image: ByteArray? = imageService.requestComposite(
                    mashup = mashup,
                    downloadType = downloadType
                )

                if (image == null) {
                    call.respond(HttpStatusCode.InternalServerError)
                    return@post
                }

                val inputStream = ByteArrayInputStream(image)
                call.respondBytesWriter(contentType = mediaType) {
                    inputStream.copyTo(this.toOutputStream())
                }
            } catch (e: Exception) {
                println(e.localizedMessage)
                call.respond(HttpStatusCode.InternalServerError)
            }
        }

        get("/api/mashi/mashup/{wallet}") {
            try {
                val wallet =
                    call.parameters["wallet"]
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            "Wallet is required"
                        )

                val downloadTypeParam =
                    call.request.queryParameters["download_type"] ?: "png"

                val mediaType = when (downloadTypeParam.lowercase()) {
                    "png" -> ContentType.Image.PNG
                    "gif" -> ContentType.Image.GIF
                    else -> {
                        call.respond(
                            HttpStatusCode.BadRequest
                        )
                        return@get
                    }
                }

                val downloadType =
                    DownloadType.valueOf(downloadTypeParam.uppercase())

                val data: ByteArray? =
                    imageService.requestComposite(
                        wallet = wallet,
                        downloadType = downloadType
                    )

                if (data == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        "No mashup found for this wallet"
                    )
                    return@get
                }

                call.respondBytes(
                    bytes = data,
                    contentType = mediaType
                )

            } catch (e: Exception) {
                println(e.localizedMessage)

                call.respond(
                    HttpStatusCode.InternalServerError
                )
            }
        }
    }
}
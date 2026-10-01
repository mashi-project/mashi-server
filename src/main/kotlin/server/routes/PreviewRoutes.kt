package com.mashiverse.server.routes

import com.mashiverse.data.models.ImageType
import com.mashiverse.server.routes.preview.Preview2Webp
import com.mashiverse.server.routes.preview.PreviewWebp
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.previewRoutes() {
    routing {

        get("/api/mashi/app/preview/{index}") {
            try {
                val index = call.parameters["index"] ?: return@get call.respond(HttpStatusCode.BadRequest)

                when (index.toInt()) {
                    1 -> {
                        val data = PreviewWebp.bytes
                        call.respondBytes(data, ContentType.Image.WEBP)
                    }

                    2 -> {
                        val data =
                            """<svg xmlns="http://www.w3.org/2000/svg" width="380" height="600" viewBox="0 0 380 600"><circle cx="190" cy="150" r="35" fill="#FFFF00"/><circle cx="190" cy="300" r="35" fill="#00FF00"/><circle cx="190" cy="450" r="35" fill="#0000FF"/></svg>""".toByteArray()
                        call.respondBytes(data, ContentType.Image.SVG)
                    }

                    3 -> {
                        val data = Preview2Webp.bytes
                        call.respondBytes(data, ContentType.Image.WEBP)
                    }
                }
            } catch (e: Exception) {
                println(e.localizedMessage)
                call.respond(HttpStatusCode.InternalServerError)
            }
        }

        get("/api/mashi/app/preview/type/{index}") {
            try {
                val index = call.parameters["index"] ?: return@get call.respond(HttpStatusCode.BadRequest)

                when (index.toInt()) {
                    1 -> call.respondText(ImageType.WEBP.name, ContentType.Text.Plain)
                    2 -> call.respondText(ImageType.SVG.name, ContentType.Text.Plain)
                    3 -> call.respondText(ImageType.WEBP.name, ContentType.Text.Plain)
                }
            } catch (e: Exception) {
                println(e.localizedMessage)
                call.respond(HttpStatusCode.InternalServerError)
            }
        }
    }
}
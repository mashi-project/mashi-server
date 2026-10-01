package com.mashiverse.server.routes

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
                }
            } catch (e: Exception) {
                println(e.localizedMessage)
                call.respond(HttpStatusCode.InternalServerError)
            }
        }
    }
}
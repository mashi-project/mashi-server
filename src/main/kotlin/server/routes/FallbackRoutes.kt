package com.mashiverse.server.routes

import com.mashiverse.services.FallbackService
import data.models.DownloadType
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*


fun Application.fallbackRoutes() {
    routing {
        get("/api/mashi/fallback/{id}") {
            try {
                val idStr = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)

                val fallbackMashup = FallbackService.getMashup(idStr) ?: return@get call.respond(
                    HttpStatusCode.NotFound,
                    "Image not found"
                )
                val type = fallbackMashup.type
                val bytes = fallbackMashup.content ?: return@get call.respond(
                    HttpStatusCode.NotFound,
                    "Image not found"
                )

                when (type) {
                    DownloadType.GIF -> call.respondBytes(bytes, ContentType.Image.GIF)
                    DownloadType.PNG -> call.respondBytes(bytes, ContentType.Image.PNG)
                }
            } catch (e: Exception) {
                println(e.localizedMessage)
                call.respond(HttpStatusCode.InternalServerError)
            }
        }
    }
}
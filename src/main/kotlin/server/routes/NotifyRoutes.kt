package com.mashiverse.server.routes

import com.mashiverse.data.remote.dto.NotifyDto
import com.mashiverse.discord.MashiBot
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

fun Application.notifyRoutes() {

    routing {
        post("/api/mashi/release_notify") {
            try {
                val data = call.receive<NotifyDto>()
                print(data)

                val job = async { MashiBot.getInstance().notify(data) }
                awaitAll(job)

                call.respond(HttpStatusCode.OK)
            } catch (e: Exception) {
                e.printStackTrace()
                call.respond(HttpStatusCode.InternalServerError, mapOf("message" to (e.message ?: "Unknown error")))
            }
        }

        post("/api/mashi/approval_notify") {
            try {
                val data = call.receive<NotifyDto>()
                print(data)

                val job = async { MashiBot.getInstance().notify(data, isRelease = false) }
                awaitAll(job)

                call.respond(HttpStatusCode.OK)
            } catch (e: Exception) {
                e.printStackTrace()
                call.respond(HttpStatusCode.InternalServerError)
            }
        }
    }
}
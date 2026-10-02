package com.mashiverse.server.routes

import com.mashiverse.data.remote.dto.NotifyDto
import com.mashiverse.discord.MashiBot
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException

fun Application.notifyRoutes() {
    routing {
        post("/api/mashi/release_notify") {
            handleNotify(call, isRelease = true)
        }

        post("/api/mashi/approval_notify") {
            handleNotify(call, isRelease = false)
        }
    }
}

private suspend fun handleNotify(call: ApplicationCall, isRelease: Boolean) {
    try {
        val data = call.receive<NotifyDto>()
        println(data)

        // Direct call: no need for async/awaitAll around a single suspend function
        MashiBot.getInstance().notify(data, isRelease)

        call.respond(HttpStatusCode.OK)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.printStackTrace()
        call.respond(
            HttpStatusCode.InternalServerError,
            mapOf("message" to (e.message ?: e::class.simpleName ?: "Unknown error"))
        )
    }
}
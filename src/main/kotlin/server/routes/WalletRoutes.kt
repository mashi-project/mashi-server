package com.mashiverse.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

private val walletConnectHtml: String by lazy {
    object {}.javaClass.classLoader
        .getResourceAsStream("wallet-connect.html")!!
        .bufferedReader()
        .use { it.readText() }
}

fun Application.walletRoutes() {
    routing {
        get("/wallet/connect/{discord_id}") {
            val discordId = call.parameters["discord_id"]
            if (discordId?.toLongOrNull() == null) {
                return@get call.respond(HttpStatusCode.BadRequest, "Invalid id")
            }
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respondText(walletConnectHtml, ContentType.Text.Html)
        }
    }
}
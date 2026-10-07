package com.mashiverse.utils.other

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*

fun Application.configureHttp() {
    install(CORS) {
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)

        allowHeaders { true }
        allowCredentials = true
        allowSameOrigin = true
        allowNonSimpleContentTypes = true

        val prodSchemes = listOf("https")
        allowHost("katzemon.com", schemes = prodSchemes)
        allowHost("mash-it.io", schemes = prodSchemes)
        allowHost("www.mash-it.io", schemes = prodSchemes)
        allowHost("avatar-artists-guild-dev.web.app", schemes = prodSchemes)

        val devSchemes = listOf("http")
        allowHost("localhost:3000", schemes = devSchemes)
        allowHost("localhost:5173", schemes = devSchemes)
    }
}
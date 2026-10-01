package com.mashiverse.data.remote

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

object KtorClient {
    val jsonDecoder = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        isLenient = true
    }

    fun client() = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(jsonDecoder)
        }
    }
}
package com.mashiverse.services

import data.models.DownloadType
import io.ktor.server.engine.DefaultUncaughtExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

data class FallbackMashup(
    val type: DownloadType,
    val content: ByteArray?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FallbackMashup

        if (type != other.type) return false
        if (!content.contentEquals(other.content)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + content.contentHashCode()
        return result
    }
}

object FallbackService {
    private val mashups: MutableMap<String, FallbackMashup> = mutableMapOf()

    fun getMashup(id: String): FallbackMashup? = mashups[id]

    fun addMashup(id: String, mashup: FallbackMashup) {
        mashups[id] = mashup

        CoroutineScope(Dispatchers.IO).launch {
            delay(30.minutes)
            removeMashup(id)
        }
    }

    private fun removeMashup(id: String) = mashups.remove(id)
}
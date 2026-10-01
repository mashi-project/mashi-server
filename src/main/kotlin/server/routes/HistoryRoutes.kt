package com.mashiverse.server.routes

import com.mashiverse.data.db.daos.HistoryDao
import com.mashiverse.data.models.ImageType
import com.mashiverse.images.helpers.getImageType
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject
import java.util.*

@Serializable
data class HistoryItemResponse(
    val id: String,
    val wallet: String,
    val imageUrl: String,
    val timestamp: String,
    val imageType: String,
)

@Serializable
data class HistoryPageResponse(
    val items: List<HistoryItemResponse>,
    val hasNextPage: Boolean
)


fun Application.historyRoutes() {
    val historyDao by inject<HistoryDao>()

    routing {
        delete("/api/mashi/app/history/delete/{wallet}") {
            try {
                val wallet = call.parameters["wallet"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                historyDao.deleteHistoryByWallet(wallet)
                call.respond(HttpStatusCode.OK)
            } catch (e: Exception) {
                println(e.localizedMessage)
                call.respond(HttpStatusCode.InternalServerError)
            }
        }

        get("/api/mashi/app/history/{wallet}") {
            try {
                val wallet = call.parameters["wallet"] ?: return@get call.respond(HttpStatusCode.BadRequest)

                val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                val offset = (page - 1) * limit

                // Fetch limit + 1 to efficiently check if a subsequent page exists
                val historyList = historyDao.getHistoryByWalletPaginated(wallet, limit + 1, offset)

                val hasNextPage = historyList.size > limit
                val actualList = if (hasNextPage) historyList.dropLast(1) else historyList

                val responseItems = actualList.map { record ->
                    val detectedType = if (record.image != null) getImageType(record.image) else ImageType.UNKNOWN

                    HistoryItemResponse(
                        id = record.id.toString(),
                        wallet = record.wallet,
                        imageUrl = "https://katzemon.com/api/mashi/app/history/image/${record.id}",
                        timestamp = record.timestamp.toString(),
                        imageType = detectedType.name
                    )
                }

                call.respond(
                    HistoryPageResponse(
                        items = responseItems,
                        hasNextPage = hasNextPage
                    )
                )
            } catch (e: Exception) {
                println(e.localizedMessage)
                call.respond(HttpStatusCode.InternalServerError)
            }
        }

        // Route to serve the history image bytes by history record ID
        get("/api/mashi/app/history/image/{id}") {
            try {
                val idStr = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val id = UUID.fromString(idStr)

                val record = historyDao.getHistoryById(id)

                if (record?.image != null) {
                    val imageType = getImageType(record.image)
                    when (imageType) {
                        ImageType.SVG -> call.respondBytes(record.image, ContentType.Image.SVG)
                        ImageType.WEBP -> call.respondBytes(record.image, ContentType.Image.WEBP)
                        else -> call.respondBytes(record.image, ContentType.Image.WEBP)
                    }
                } else {
                    call.respond(HttpStatusCode.NotFound, "Image not found")
                }
            } catch (e: Exception) {
                println(e.localizedMessage)
                call.respond(HttpStatusCode.InternalServerError)
            }
        }

        delete("/api/mashi/app/history/image/delete/{id}") {
            try {
                val idStr = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                val id = UUID.fromString(idStr)
                historyDao.deleteHistory(id)
                call.respond(HttpStatusCode.OK)
            } catch (e: Exception) {
                println(e.localizedMessage)
                call.respond(HttpStatusCode.InternalServerError)
            }
        }
    }
}
package com.mashiverse.discord.modules

import com.mashiverse.configs.CHANNELS
import com.mashiverse.data.db.daos.EventDao
import dev.kord.core.Kord
import dev.kord.core.entity.Message
import dev.kord.core.entity.ReactionEmoji
import dev.kord.core.event.message.ReactionAddEvent
import dev.kord.core.on
import kotlin.coroutines.cancellation.CancellationException

val GHOST = ReactionEmoji.Unicode("👻")

fun registerReactionTracking(kord: Kord, eventDao: EventDao) {
    kord.on<ReactionAddEvent> {
        try {
            if (!CHANNELS.contains(channelId.value.toLong())) return@on
            if (emoji != GHOST) return@on
            if (userId == kord.selfId) return@on

            val reactor = getUser()
            if (reactor.isBot) return@on

            val message = getMessage()

            // Only act on the bot's own mashup posts
            if (message.author?.id != kord.selfId) return@on

            val title = message.embeds.firstOrNull()?.title ?: return@on
            val index = title.substringAfter("#").substringBefore(":").toIntOrNull()
                ?: return@on

            onGhostReaction(
                message = message,
                index = index,
                user = userId.value.toLong(),
                eventDao = eventDao
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Ghost reaction handler failed: ${e::class.simpleName}: ${e.message}")
        }
    }
}

private suspend fun onGhostReaction(
    message: Message,
    index: Int,
    user: Long,
    eventDao: EventDao
) {
    if (eventDao.existsByUserId(user)) return

    eventDao.addIfAbsent(index, user)
    message.delete()
}
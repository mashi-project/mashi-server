package com.mashiverse.discord.modules

import dev.kord.common.entity.Permission
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.respondEphemeral
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent
import dev.kord.core.on
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds

class RebootModule(private val kord: Kord) {
    init {
        registerCommands()
        listenToInteractions()
    }

    private fun registerCommands() {
        kord.launch {
            kord.createGlobalChatInputCommand("reboot", "Restarts the bot container") {
                dmPermission = false
            }
        }
    }

    private fun listenToInteractions() {
        kord.on<ChatInputCommandInteractionCreateEvent> {
            when (interaction.command.rootName) {
                "reboot" -> handleReboot(this)
            }
        }
    }

    private suspend fun handleReboot(event: ChatInputCommandInteractionCreateEvent) {
        val user = event.interaction.user
        val isAuthorizedUser = user.id == Snowflake("1167694222120468553")

        val guildId = event.interaction.data.guildId.value
        val guild = guildId?.let { kord.getGuildOrNull(it) }
        val member = guildId?.let { user.asMemberOrNull(it) }

        val isOwner = guild?.ownerId == user.id
        val isAdmin = member?.getPermissions()?.contains(Permission.Administrator) == true

        if (!(isOwner || isAdmin || isAuthorizedUser)) {
            event.interaction.respondEphemeral {
                content = "❌ You do not have permission to restart the container."
            }
            return
        }

        val response = event.interaction.deferEphemeralResponse()
        response.respond {
            content = "🔄 Restarting container `mashi`..."
        }

        // Slight delay to ensure the Discord response finishes sending before process termination
        delay(1000.milliseconds)

        withContext(Dispatchers.IO) {
            exitProcess(0)
        }
    }
}
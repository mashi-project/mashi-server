package com.mashiverse.discord.modules

import com.mashiverse.data.db.daos.UserDao
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent
import dev.kord.core.on
import dev.kord.rest.builder.interaction.string
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class WalletModule(private val kord: Kord) : KoinComponent {
    private val userDao by inject<UserDao>()

    init {
        registerCommands()
        listenToInteractions()
    }

    private fun registerCommands() {
        kord.launch {
            kord.createGlobalChatInputCommand("connect_wallet", "Connect wallet") {
                dmPermission = true
                string("wallet", "Wallet") { required = true }
            }
            kord.createGlobalChatInputCommand("disconnect_wallet", "Disconnect wallet") {
                dmPermission = true
            }
            kord.createGlobalChatInputCommand("resolve_wallet", "Resolves wallet by Discord ID") {
                dmPermission = true
                string("discord_id", "Discord id on user right click") { required = true }
            }
        }
    }

    private fun listenToInteractions() {
        kord.on<ChatInputCommandInteractionCreateEvent> {
            when (interaction.command.rootName) {
                "connect_wallet" -> handleConnectWallet(this)
                "disconnect_wallet" -> handleDisconnectWallet(this)
                "resolve_wallet" -> handleResolveWallet(this)
            }
        }
    }

    private suspend fun handleResolveWallet(event: ChatInputCommandInteractionCreateEvent) = coroutineScope {
        try {
            val interaction = event.interaction
            val discordId = interaction.command.options["discord_id"]!!.value.toString()
            val response = interaction.deferEphemeralResponse()

            val wallet = userDao.getWallet(userId = discordId.toLong())
            if (wallet != null) {
                response.respond { content = wallet }
            } else {
                response.respond { content = "Wallet not found" }
            }
        } catch (e: Exception) {
            print(e.message)
        }
    }

    private suspend fun handleConnectWallet(event: ChatInputCommandInteractionCreateEvent) {
        val interaction = event.interaction
        val wallet = interaction.command.options["wallet"]!!.value.toString()
        val response = interaction.deferEphemeralResponse()

        try {
            if (wallet.length != 42) {
                response.respond { content = "Invalid wallet" }
                return
            }

            val userId = interaction.user.id.value.toLong()
            val hasWallet = userDao.getWallet(userId) != null
            if (hasWallet) {
                response.respond { content = "You already have wallet" }
                return
            }

            val isAnotherUserWallet = userDao.isExist(wallet)
            if (isAnotherUserWallet) {
                response.respond { content = "Wallet already taken" }
                return
            }

            userDao.connectWallet(userId, wallet.lowercase())
            response.respond { content = "Wallet connected" }

        } catch (e: Exception) {
            println(e)
            response.respond { content = "Something went wrong" }
        }
    }

    private suspend fun handleDisconnectWallet(event: ChatInputCommandInteractionCreateEvent) {
        val interaction = event.interaction
        val response = interaction.deferEphemeralResponse()

        try {
            userDao.disconnectWallet(interaction.user.id.value.toLong())
            response.respond { content = "Wallet disconnected" }
        } catch (e: Exception) {
            println(e)
            response.respond { content = "Something went wrong" }
        }
    }
}
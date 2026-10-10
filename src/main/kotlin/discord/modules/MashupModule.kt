package com.mashiverse.discord.modules

// Mocking imports for illustration
import com.mashiverse.configs.TEST_CHANNEL_ID
import com.mashiverse.data.db.daos.UserDao
import data.models.DownloadType
import dev.kord.common.Color
import dev.kord.common.entity.Permission
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.ReactionEmoji
import dev.kord.core.entity.channel.TextChannel
import dev.kord.core.entity.interaction.GuildChatInputCommandInteraction
import dev.kord.core.entity.interaction.response.PublicMessageInteractionResponse
import dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent
import dev.kord.core.on
import dev.kord.rest.builder.interaction.string
import dev.kord.rest.builder.message.embed
import dev.kord.rest.request.KtorRequestException
import images.services.ImageService
import io.ktor.client.request.forms.*
import io.ktor.utils.io.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.random.Random

class MashupModule(private val kord: Kord) : KoinComponent {
    private val userDao by inject<UserDao>()
    private val imageService by inject<ImageService>()

    init {
        registerCommands()
        listenToInteractions()
    }

    private fun registerCommands() {
        kord.launch {
            kord.createGlobalChatInputCommand("mashi", "Generates mashup") {
                dmPermission = true
                string("image", "Image type") {
                    choice("PNG", "PNG")
                    choice("GIF", "GIF")
                }
            }
            kord.createGlobalChatInputCommand("delete_mashup", "Deletes mashup") {
                dmPermission = true
                string("msg_id", "Message id on right click") { required = true }
            }
        }
    }

    private fun listenToInteractions() {
        kord.on<ChatInputCommandInteractionCreateEvent> {
            val command = interaction.command
            when (command.rootName) {
                "mashi" -> handleMashi(this)
                "delete_mashup" -> handleDeleteMashup(this)
            }
        }
    }

    private suspend fun handleMashi(event: ChatInputCommandInteractionCreateEvent) = coroutineScope {
        val interaction = event.interaction
        val imageOpt = interaction.command.options["image"]?.value?.toString() ?: "PNG"
        val userId = interaction.user.id.value.toLong()

        val wallet = userDao.getWallet(userId)

        if (wallet == null) {
            interaction.deferEphemeralResponse().respond {
                content = "Please use /connect_wallet command"
            }
            return@coroutineScope
        }

        val response = interaction.deferPublicResponse()

        try {
            val downloadType = DownloadType.valueOf(imageOpt)
            val ext = if (downloadType == DownloadType.PNG) ".png" else ".gif"
            val filename = "composite$ext"
            val embedTitle = "${interaction.user.globalName}'s mashup"

            suspend fun send(isSmall: Boolean): PublicMessageInteractionResponse {
                val (bytes, size) = imageService.requestCompositeData(
                    wallet,
                    downloadType = downloadType,
                    isSmall = isSmall
                ) ?: throw IllegalStateException("Failed to generate composite image data")

                val channelProvider = ChannelProvider(size) { ByteReadChannel(bytes) }

                return response.respond {
                    addFile(filename, channelProvider)
                    embed {
                        title = embedTitle

                        if (bannedUsers.contains(userId)) {
                            description = "```ansi\n\u001b[31mBanned user!\u001b[0m\n```"
                        }

                        color = Color(Random.nextInt(0xFFFFFF))
                        image = "attachment://$filename"
                        footer { text = "© 2026 mash-it" }
                    }
                }
            }

            val interactionResponse = try {
                send(isSmall = false)
            } catch (e: KtorRequestException) {
                if (e.status.code == 413) {
                    send(isSmall = true)
                } else {
                    throw e
                }
            }

            launch {
                runCatching {
                    interactionResponse.message.addReaction(ReactionEmoji.Unicode("🔥"))
                }
            }

        } catch (e: Exception) {
            val channel = kord.getChannelOf<TextChannel>(Snowflake(TEST_CHANNEL_ID))
            channel?.createMessage("/mashi: ${e.message}")

            runCatching {
                interaction.kord.rest.interaction.createFollowupMessage(
                    interaction.applicationId,
                    interaction.token,
                    ephemeral = true
                ) {
                    content = "Something went wrong"
                }
            }
        }
    }

    private suspend fun handleDeleteMashup(event: ChatInputCommandInteractionCreateEvent) {
        val interaction = event.interaction
        val msgIdStr = interaction.command.options["msg_id"]!!.value.toString()
        val response = interaction.deferEphemeralResponse()

        try {
            val channel = interaction.channel.asChannel() as TextChannel
            val message = channel.getMessage(Snowflake(msgIdStr.toLong()))

            // Checking interaction metadata
            val metadataUser = message.interaction?.user
            val originalPosterId = metadataUser?.id

            // guildId is null in a DM — only check guild staff permissions when one exists.
            val isStaff = when (val i = interaction) {
                is GuildChatInputCommandInteraction -> {
                    val member = i.user.asMember(i.guildId)
                    val permissions = member.getPermissions()
                    permissions.contains(Permission.Administrator) ||
                            permissions.contains(Permission.ManageMessages) ||
                            i.user.id == i.getGuild().ownerId
                }

                else -> false
            }

            // In a DM, channel.getMessage already implicitly restricts this to
            // messages the bot can see in that DM, so the "original poster" check
            // alone is sufficient — there's no staff concept without a guild.
            if (originalPosterId == interaction.user.id || isStaff) {
                message.delete()
                response.respond { content = "Mashup was deleted" }
                return
            }

            response.respond { content = "You are not allowed to delete that mashup" }
        } catch (e: Exception) {
            println(e)
            response.respond { content = "Something went wrong" }
        }
    }

    companion object {
        val bannedUsers = listOf<Long>()
    }
}
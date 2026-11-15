package com.mashiverse.discord.modules

// Mocking imports for illustration
import com.mashiverse.configs.TEST_CHANNEL_ID
import com.mashiverse.data.db.daos.UserDao
import com.mashiverse.data.models.GifRes
import com.mashiverse.services.FallbackMashup
import com.mashiverse.services.FallbackService
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
import java.util.*
import kotlin.random.Random

const val MAX_UPLOAD_BYTES = (24.5 * 1024 * 1024).toLong() // 24.5 MB

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
        val userId = interaction.user.id.value.toLong()
        val downloadType = runCatching {
            DownloadType.valueOf(interaction.command.options["image"]?.value?.toString() ?: "PNG")
        }.getOrDefault(DownloadType.PNG)

        // 1) Acknowledge FIRST. Discord gives only 3 seconds; anything slow (DB, HTTP) must come after this.
        val response = try {
            interaction.deferPublicResponse()
        } catch (e: KtorRequestException) {
            // 404 "Unknown interaction": the 3s window was missed or another instance already acked it.
            println("/mashi: could not defer (status ${e.status.code}): ${e.message}")
            return@coroutineScope
        }

        // Safe error reporting: never throws, never touches a dead interaction.
        suspend fun fail(e: Throwable) {
            runCatching {
                kord.getChannelOf<TextChannel>(Snowflake(TEST_CHANNEL_ID))
                    ?.createMessage("/mashi: ${e::class.simpleName}: ${e.message}")
            }
            runCatching { response.respond { content = "Something went wrong" } }
        }

        try {
            val wallet = userDao.getWallet(userId)

            if (wallet == null) {
                // The defer was public, so replace the loading message with an ephemeral hint.
                runCatching {
                    interaction.kord.rest.interaction.deleteOriginalInteractionResponse(
                        interaction.applicationId,
                        interaction.token
                    )
                }
                runCatching {
                    interaction.kord.rest.interaction.createFollowupMessage(
                        interaction.applicationId,
                        interaction.token,
                        ephemeral = true
                    ) {
                        content = "Please use /connect_wallet command"
                    }
                }
                return@coroutineScope
            }

            val ext = if (downloadType == DownloadType.PNG) ".png" else ".gif"
            val filename = "composite$ext"
            val embedTitle = "${interaction.user.globalName}'s mashup"

            // Best quality first. PNGs have no GIF resolution, so only try once.
            val resolutions = if (downloadType == DownloadType.PNG) {
                listOf(GifRes.NORMAL)
            } else {
                listOf(GifRes.NORMAL, GifRes.SMALL, GifRes.XS)
            }

            suspend fun send(bytes: ByteArray, size: Long): PublicMessageInteractionResponse {
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

            var sent: PublicMessageInteractionResponse? = null

            for ((index, res) in resolutions.withIndex()) {
                val isLast = index == resolutions.lastIndex

                val (bytes, size) = imageService.requestCompositeData(
                    wallet,
                    downloadType = downloadType,
                    gifRes = res
                ) ?: throw IllegalStateException("Failed to generate composite image data")

                // Too big -> try the next resolution (unless this is the last one)
                if (size > MAX_UPLOAD_BYTES && !isLast) continue

                try {
                    sent = send(bytes, size)
                    break
                } catch (e: KtorRequestException) {
                    // Safety net: Discord still said it's too large. Anything else is a real error.
                    if (e.status.code != 413 || isLast) throw e
                }
            }

            val message = sent ?: throw IllegalStateException("Could not produce an uploadable image")

            launch {
                runCatching { message.message.addReaction(ReactionEmoji.Unicode("🔥")) }
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val status = (e as? KtorRequestException)?.status?.code

            when (status) {
                // Interaction expired or token invalid: there is nothing left to respond to.
                404 -> println("/mashi: interaction expired: ${e.message}")

                // Still too large at the smallest resolution -> hand out a download link.
                413 -> try {
                    val mashup = imageService.requestCompositeData(
                        userDao.getWallet(userId) ?: throw IllegalStateException("Wallet disappeared"),
                        downloadType = downloadType,
                        gifRes = GifRes.SMALL
                    )

                    if (mashup != null) {
                        val uuidStr = UUID.randomUUID().toString()
                        FallbackService.addMashup(
                            uuidStr,
                            FallbackMashup(type = downloadType, content = mashup.first)
                        )
                        response.respond {
                            content = "Here's mashup download link: https://katzemon.com/api/mashi/fallback/$uuidStr"
                        }
                    } else {
                        response.respond { content = "Please let us know if you have that issue" }
                    }
                } catch (inner: CancellationException) {
                    throw inner
                } catch (inner: Exception) {
                    fail(inner)
                }

                else -> fail(e)
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
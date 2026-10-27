package com.mashiverse.discord

import com.mashiverse.configs.*
import com.mashiverse.data.db.daos.EventDao
import com.mashiverse.data.db.daos.UserDao
import com.mashiverse.data.models.GifRes
import com.mashiverse.data.remote.apis.IpfsApi
import com.mashiverse.data.remote.dto.NotifyDto
import com.mashiverse.discord.modules.GHOST
import com.mashiverse.discord.modules.MAX_UPLOAD_BYTES
import com.mashiverse.discord.modules.MashupModule
import com.mashiverse.discord.modules.RebootModule
import com.mashiverse.discord.modules.WalletModule
import com.mashiverse.discord.modules.getNotifyEmbed
import com.mashiverse.discord.modules.registerReactionTracking
import com.mashiverse.services.AnimService
import data.models.DownloadType
import dev.kord.common.Color
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.entity.Message
import dev.kord.core.entity.ReactionEmoji
import dev.kord.core.entity.channel.TextChannel
import dev.kord.rest.builder.message.allowedMentions
import dev.kord.rest.builder.message.embed
import dev.kord.rest.request.KtorRequestException
import images.services.ImageService
import io.ktor.client.request.forms.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.Base64
import kotlin.random.Random

class MashiBot private constructor(val kord: Kord) : KoinComponent {
    private val animService by inject<AnimService>()
    private val ipfsApi by inject<IpfsApi>()
    private val userDao by inject<UserDao>()
    private val imageService by inject<ImageService>()

    private val eventDao by inject<EventDao>()

    companion object {
        @Volatile
        private var INSTANCE: MashiBot? = null

        fun initialize(kord: Kord): MashiBot {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MashiBot(kord).also { INSTANCE = it }
            }
        }

        fun getInstance(): MashiBot {
            return INSTANCE ?: throw IllegalStateException(
                "MashiBot has not been initialized. Call initialize(kord) first."
            )
        }
    }

    fun setup() {
        MashupModule(kord)
        WalletModule(kord)
        RebootModule(kord)
        registerReactionTracking(kord, eventDao)
    }

    /** Converts ipfs://CID/path into a public gateway URL. */
    private fun ipfsToGatewayUrl(
        ipfsUrl: String,
        gateway: String = "https://round-peach-hippopotamus.myfilebase.com/ipfs/"
    ): String =
        gateway + ipfsUrl.removePrefix("ipfs://")

    private suspend fun reportToTestChannel(message: String) {
        try {
            kord.getChannelOf<TextChannel>(Snowflake(TEST_CHANNEL_ID))?.createMessage(message.take(1900))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Failed to report to test channel: ${e::class.simpleName}: ${e.message}")
        }
    }

    suspend fun notify(data: NotifyDto, isRelease: Boolean = true) {
        try {
            val channelId = Snowflake(if (isRelease) RELEASES_CHANNEL_ID else APPROVALS_CHANNEL_ID)
            val channel = kord.getChannelOf<TextChannel>(channelId) ?: run {
                reportToTestChannel("Notify: channel $channelId not found for ${data.docId}")
                return
            }
            val roleId = Snowflake(if (isRelease) RELEASES_ROLE_ID else APPROVALS_ROLE_ID)

            // 1. Try to get the image bytes (generated GIF or static composite)
            var bytes: ByteArray? = null
            var fileName = "embed_image.png"

            try {
                val isAnyAnimated = animService.checkIfAnyAnimated(data)

                if (isAnyAnimated) {
                    bytes = animService.generateAnim(data)
                    fileName = "embed_image.gif"
                }

                // Static case, or the animation failed: fall back to the composite
                if (bytes == null) {
                    bytes = ipfsApi.getImageSrc(imageUrl = data.assets.composite, maxRetries = 5)
                    fileName = "embed_image.png"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("Error generating media for ${data.docId}: ${e::class.simpleName}: ${e.message}")
                e.printStackTrace()
            }

            // 2. Send the message (attachment if we have bytes, otherwise gateway URL)
            val imageBytes = bytes
            if (imageBytes == null) {
                println("No image bytes for docId=${data.docId}, falling back to gateway URL")
                reportToTestChannel(
                    "Notify: image fetch failed for ${data.docId} (${data.title}), used gateway fallback"
                )
            }

            channel.createMessage {
                content = "<@&$roleId>"

                if (imageBytes != null) {
                    addFile(
                        name = fileName,
                        contentProvider = ChannelProvider(size = imageBytes.size.toLong()) {
                            ByteReadChannel(imageBytes)
                        }
                    )
                }

                embed {
                    val builtEmbed = getNotifyEmbed(data, isRelease = isRelease)
                    title = builtEmbed.title
                    url = builtEmbed.url
                    color = builtEmbed.color
                    image = if (imageBytes != null) {
                        "attachment://$fileName"
                    } else {
                        ipfsToGatewayUrl(data.assets.composite)
                    }
                    footer = builtEmbed.footer
                    fields = builtEmbed.fields
                }

                allowedMentions {
                    roles.add(roleId)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Notify failed for ${data.docId}: ${e::class.simpleName}: ${e.message}")
            e.printStackTrace()
            reportToTestChannel("Notify: ${e::class.simpleName}: ${e.message} for $data")
        }
    }

    suspend fun sendMashup(data: Pair<ByteArray, Long>?, downloadType: DownloadType, wallet: String) {
        try {
            val channelId = Snowflake(MASHUPS_CHANNEL_ID)
            val channel = kord.getChannelOf<TextChannel>(channelId) ?: return

            val userIdLong = userDao.getIdByWallet(wallet) ?: return
            val userId = Snowflake(userIdLong)
            val user = kord.getUser(userId) ?: return

            val ext = if (downloadType == DownloadType.PNG) ".png" else ".gif"
            val filename = "composite$ext"

            suspend fun post(bytes: ByteArray, size: Long): Message {
                val channelProvider = ChannelProvider(size) { ByteReadChannel(bytes) }

                return channel.createMessage {
                    addFile(filename, channelProvider)
                    embed {
                        title = "${user.globalName ?: user.username}'s mashup"
                        color = Color(Random.nextInt(0xFFFFFF))
                        image = "attachment://$filename"
                        footer { text = "© 2026 mash-it" }
                    }
                }
            }

            // Best quality first. PNGs have no GIF resolution, so only one attempt.
            val resolutions = if (downloadType == DownloadType.PNG) {
                listOf(GifRes.NORMAL)
            } else {
                listOf(GifRes.NORMAL, GifRes.SMALL, GifRes.XS)
            }

            var message: Message? = null

            for ((index, res) in resolutions.withIndex()) {
                val isLast = index == resolutions.lastIndex

                // Reuse the pre-generated data for the first attempt, generate the rest on demand
                val payload = (if (index == 0) data else null)
                    ?: imageService.requestCompositeData(
                        wallet = wallet,
                        downloadType = downloadType,
                        gifRes = res
                    )
                    ?: throw IllegalStateException("Failed to generate $res composite image data")

                val (bytes, size) = payload

                // Too big -> next resolution (unless it's the last one)
                if (size > MAX_UPLOAD_BYTES && !isLast) continue

                try {
                    message = post(bytes, size)
                    break
                } catch (e: KtorRequestException) {
                    // Safety net: Discord still said 413
                    if (e.status.code != 413 || isLast) throw e
                }
            }

            val sent = message
                ?: throw IllegalStateException("Could not produce an uploadable image")

            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    sent.addReaction(ReactionEmoji.Unicode("🔥"))
                }
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("sendMashup failed for $wallet: ${e::class.simpleName}: ${e.message}")
            reportToTestChannel("Mashup: ${e::class.simpleName}: ${e.message} for wallet $wallet")
        }
    }

    suspend fun sendEventEmbed(index: Int): Message? {
        return try {
            val randomChannelId = CHANNELS.random()
            val channel = kord.getChannelOf<TextChannel>(Snowflake(randomChannelId)) ?: return null

            val mashi: ByteArray by lazy {
                Base64.getDecoder().decode(base64)
            }
            val fileName = "image.png"

            val message = channel.createMessage {
                addFile(
                    name = fileName,
                    contentProvider = ChannelProvider(size = mashi.size.toLong()) {
                        ByteReadChannel(mashi)
                    }
                )
                embed {
                    this.title = "#$index: Spider-Boo event"
                    color = Color(Random.nextInt(0xFFFFFF))
                    image = "attachment://$fileName"
                }
            }

            message.addReaction(GHOST)
            message
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("sendGhostEmbed failed: ${e::class.simpleName}: ${e.message}")
            null
        }
    }
}
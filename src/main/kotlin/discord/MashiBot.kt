package com.mashiverse.discord

import com.mashiverse.configs.*
import com.mashiverse.data.remote.apis.IpfsApi
import com.mashiverse.data.remote.dto.NotifyDto
import com.mashiverse.discord.modules.MashupModule
import com.mashiverse.discord.modules.RebootModule
import com.mashiverse.discord.modules.WalletModule
import com.mashiverse.discord.modules.getNotifyEmbed
import com.mashiverse.services.AnimService
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.entity.channel.TextChannel
import dev.kord.rest.builder.message.allowedMentions
import dev.kord.rest.builder.message.embed
import io.ktor.client.request.forms.*
import io.ktor.utils.io.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class MashiBot private constructor(val kord: Kord) : KoinComponent {
    private val animService by inject<AnimService>()
    private val ipfsApi by inject<IpfsApi>()

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
    }

    suspend fun notify(data: NotifyDto, isRelease: Boolean = true) {
        try {
            val channelId = Snowflake(if (isRelease) RELEASES_CHANNEL_ID else APPROVALS_CHANNEL_ID)
            val channel = kord.getChannelOf<TextChannel>(channelId) ?: return
            val roleId = Snowflake(if (isRelease) RELEASES_ROLE_ID else APPROVALS_ROLE_ID)

            try {
                val isAnyAnimated = animService.checkIfAnyAnimated(data)

                // 1. Fetch file byte data and assign the correct filename extension
                val (bytes, fileName) = if (!isAnyAnimated) {
                    // Direct suspend call; no need for coroutineScope/async for a single request
                    val composite = ipfsApi.getImageSrc(imageUrl = data.assets.composite, maxRetries = 5)
                    composite to "embed_image.png"
                } else {
                    val anim = animService.generateAnim(data)
                    anim to "embed_image.gif"
                }

                // 2. Send message if image payload was successfully retrieved
                if (bytes != null) {
                    channel.createMessage {
                        content = "<@&$roleId>"

                        addFile(
                            name = fileName,
                            contentProvider = ChannelProvider(size = bytes.size.toLong()) {
                                ByteReadChannel(bytes)
                            }
                        )

                        embed {
                            val builtEmbed = getNotifyEmbed(data, isRelease = isRelease)
                            title = builtEmbed.title
                            url = builtEmbed.url
                            color = builtEmbed.color
                            image = "attachment://$fileName"
                            footer = builtEmbed.footer
                            fields = builtEmbed.fields
                        }

                        allowedMentions {
                            roles.add(roleId)
                        }
                    }
                }
            } catch (e: Exception) {
                // Log inner block exceptions or report them to your monitoring service
                println("Error generating media for notification: ${e.message}")
            }
        } catch (e: Exception) {
            println(e)
            val testChannel = kord.getChannelOf<TextChannel>(Snowflake(TEST_CHANNEL_ID))
            testChannel?.createMessage("Notify: ${e.message} for $data")
        }
    }
}
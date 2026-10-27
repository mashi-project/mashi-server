package com.mashiverse.utils.other

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.mashiverse.configs.DISCORD_TOKEN
import com.mashiverse.discord.MashiBot
import dev.kord.core.Kord
import dev.kord.gateway.Intent
import dev.kord.gateway.Intents
import dev.kord.gateway.PrivilegedIntent
import io.ktor.server.application.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@OptIn(PrivilegedIntent::class)
fun Application.bot() {
    initFirebase()
    val token = DISCORD_TOKEN
    launch {
        val kord = Kord(token)
        val bot = MashiBot.initialize(kord)
        bot.setup()

       // launch { launchEventService() }

        kord.login {
            intents = Intents {
                +Intent.Guilds
                +Intent.GuildMembers
                +Intent.GuildMessageReactions
            }
        }

    }
}

fun initFirebase() {
    if (FirebaseApp.getApps().isEmpty()) {
        val serviceAccountFile = File("mashis-firebase-adminsdk-fbsvc-2bfb99f3d9.json")
        val options = FirebaseOptions.builder()
            .setCredentials(GoogleCredentials.fromStream(serviceAccountFile.inputStream()))
            .build()
        FirebaseApp.initializeApp(options)
    }
}

suspend fun launchEventService() {
    delay(2.minutes)

    for (i in 1..13) {
        MashiBot.getInstance().sendEventEmbed(i)
        delay(1.hours)
    }
}
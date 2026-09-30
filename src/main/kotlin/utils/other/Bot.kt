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
import kotlinx.coroutines.launch
import java.io.File

@OptIn(PrivilegedIntent::class)
fun Application.bot() {
    initFirebase()
    val token = DISCORD_TOKEN
    launch {
        val kord = Kord(token)
        val bot = MashiBot.initialize(kord)
        bot.setup()

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
        val serviceAccountFile = File("mashis-firebase-adminsdk-fbsvc-83530f89e4.json")
        val options = FirebaseOptions.builder()
            .setCredentials(GoogleCredentials.fromStream(serviceAccountFile.inputStream()))
            .build()
        FirebaseApp.initializeApp(options)
    }
}
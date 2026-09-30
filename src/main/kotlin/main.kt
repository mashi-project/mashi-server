package com.mashiverse

import dev.kord.gateway.PrivilegedIntent

@OptIn(PrivilegedIntent::class)
fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

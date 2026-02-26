package com.rivi.arwastewarrior

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
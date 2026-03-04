package com.rivi.arwastewarrior.detection

actual fun platformGarbageAiTipService(): GarbageAiTipService {
    return RuleBasedGarbageAiTipService()
}

package com.rivi.arwastewarrior.speech

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.rivi.arwastewarrior.detection.AppLanguage

private class NoOpHindiSpeechPlayer : HindiSpeechPlayer {
    override fun speak(text: String, language: AppLanguage) = Unit
    override fun stop() = Unit
}

@Composable
actual fun rememberHindiSpeechPlayer(): HindiSpeechPlayer {
    return remember { NoOpHindiSpeechPlayer() }
}

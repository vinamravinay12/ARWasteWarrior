package com.rivi.arwastewarrior.speech

import androidx.compose.runtime.Composable
import com.rivi.arwastewarrior.detection.AppLanguage

interface HindiSpeechPlayer {
    fun speak(text: String, language: AppLanguage = AppLanguage.HINDI)
    fun stop()
}

@Composable
expect fun rememberHindiSpeechPlayer(): HindiSpeechPlayer

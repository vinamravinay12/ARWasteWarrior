package com.rivi.arwastewarrior.speech

import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.rivi.arwastewarrior.detection.AppLanguage
import java.util.Locale

private class AndroidHindiSpeechPlayer(
    private val tts: TextToSpeech
) : HindiSpeechPlayer {
    override fun speak(text: String, language: AppLanguage) {
        if (text.isBlank()) return
        tts.language = when (language) {
            AppLanguage.HINDI -> Locale("hi", "IN")
            AppLanguage.ENGLISH -> Locale.US
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "arww_hindi_tts")
    }

    override fun stop() {
        tts.stop()
    }
}

@Composable
actual fun rememberHindiSpeechPlayer(): HindiSpeechPlayer {
    val context = LocalContext.current
    val holder = remember { arrayOfNulls<TextToSpeech>(1) }
    val tts = remember {
        TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                holder[0]?.language = Locale("hi", "IN")
                holder[0]?.setSpeechRate(0.92f)
            }
        }.also { created ->
            holder[0] = created
        }
    }

    DisposableEffect(tts) {
        onDispose {
            tts.stop()
            tts.shutdown()
        }
    }

    return remember(tts) { AndroidHindiSpeechPlayer(tts) }
}

package com.aitermux.app

import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: AppVM by viewModels()
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { ttsReady = it == TextToSpeech.SUCCESS }
        setContent {
            AppTheme {
                Root(vm, onSpeak = { speak(it) }, onStopSpeak = { tts?.stop() })
            }
        }
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        tts?.language = Locale(vm.lang)
        tts?.stop()
        text.chunked(3500).forEachIndexed { i, s ->
            tts?.speak(s, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "m$i")
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}

package net.familyross.intercom

import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import net.familyross.intercom.ui.IntercomRoot
import net.familyross.intercom.ui.IntercomTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // Calls play on the voice-call stream, so that is what the volume keys should move.
        volumeControlStream = AudioManager.STREAM_VOICE_CALL
        setContent {
            IntercomTheme {
                IntercomRoot()
            }
        }
    }
}

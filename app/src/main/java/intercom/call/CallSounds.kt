package intercom.call

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import intercom.R

/** The web client's connect / disconnect cues. */
class CallSounds(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val enter = pool.load(context, R.raw.start_connection, 1)
    private val exit = pool.load(context, R.raw.stop_connection, 1)

    fun playEnter() = play(enter)

    fun playExit() = play(exit)

    private fun play(sound: Int) {
        pool.play(sound, 1f, 1f, 1, 0, 1f)
    }
}

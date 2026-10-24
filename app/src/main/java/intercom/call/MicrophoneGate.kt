package intercom.call

/**
 * The switch that decides whether microphone audio leaves the device. It is
 * open only while at least one line is un-muted; otherwise the audio device
 * module replaces everything it captures with silence.
 *
 * Muting a WebRTC track is a blocking call into WebRTC's threads and can lag
 * when the device is busy. Flipping this gate is a plain flag write, so
 * releasing push-to-talk silences the microphone immediately and the track
 * state merely catches up. (With several lines joined, muting one line while
 * another stays live still relies on the track state.)
 */
class MicrophoneGate {
    private val liveCalls = HashSet<String>()
    private var setCaptureSilenced: ((Boolean) -> Unit)? = null

    val isOpen: Boolean
        @Synchronized get() = liveCalls.isNotEmpty()

    @Synchronized
    fun attach(setCaptureSilenced: (Boolean) -> Unit) {
        this.setCaptureSilenced = setCaptureSilenced
        setCaptureSilenced(liveCalls.isEmpty())
    }

    /**
     * [isLive] is evaluated under the gate's lock, so updates racing in from
     * different threads always settle on the call's latest state.
     */
    @Synchronized
    fun update(callKey: String, isLive: () -> Boolean) {
        val wasOpen = liveCalls.isNotEmpty()
        if (isLive()) liveCalls.add(callKey) else liveCalls.remove(callKey)
        val open = liveCalls.isNotEmpty()
        if (open != wasOpen) setCaptureSilenced?.invoke(!open)
    }
}

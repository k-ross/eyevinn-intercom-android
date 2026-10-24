package intercom.call

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import intercom.data.CallUrl
import intercom.data.CallUrlResult
import intercom.data.SettingsStore
import intercom.net.IntercomApi

/** Owns the calls that are currently joined. Lives for the whole process. */
class CallManager(
    private val context: Context,
    private val settings: SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val microphoneGate = MicrophoneGate()
    private val rtc by lazy { RtcEngine(context).also { microphoneGate.attach(it::setCaptureSilenced) } }
    private val audioRouter = AudioRouter(context)
    private val sounds = CallSounds(context)

    private val _calls = MutableStateFlow<List<LineCall>>(emptyList())
    val calls: StateFlow<List<LineCall>> = _calls.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /**
     * Joins every line of the configured call URL that is not joined already.
     * Returns false when the settings are incomplete.
     */
    @Synchronized
    fun join(): Boolean {
        val current = settings.settings.value
        val target = (CallUrl.parse(current.callUrl) as? CallUrlResult.Ok)?.target ?: return false
        val username = current.username.trim()
        if (username.isEmpty()) return false

        _notice.value = null
        val api = IntercomApi(target.origin, current.authUsername, current.authPassword, current.apiUrl)
        val joined = _calls.value.map { it.key }.toSet()
        val newCalls = target.calls.filter { it.key !in joined }.map { ref ->
            LineCall(
                ref = ref,
                username = username,
                api = api,
                rtc = { rtc },
                microphoneGate = microphoneGate,
                initialVolume = current.volume,
                parentScope = scope,
                onConnected = sounds::playEnter,
                onKicked = { call -> remove(call, "You were removed from the call") },
            )
        }
        if (newCalls.isEmpty()) return true

        _calls.value += newCalls
        audioRouter.start()
        newCalls.forEach { it.start() }
        return true
    }

    fun leave(call: LineCall) = remove(call, notice = null)

    @Synchronized
    fun leaveAll() {
        _calls.value.forEach { remove(it, notice = null) }
    }

    fun setVolume(call: LineCall, volume: Float) {
        call.setVolume(volume)
        settings.setVolume(volume)
    }

    fun dismissNotice() {
        _notice.value = null
    }

    @Synchronized
    private fun remove(call: LineCall, notice: String?) {
        if (call !in _calls.value) return
        _calls.value -= call
        call.close()
        sounds.playExit()
        if (notice != null) _notice.value = notice
        if (_calls.value.isEmpty()) audioRouter.stop()
    }
}

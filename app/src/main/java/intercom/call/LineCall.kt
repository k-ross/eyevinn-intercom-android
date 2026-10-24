package intercom.call

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import intercom.data.CallRef
import intercom.data.LineRole
import intercom.net.ApiException
import intercom.net.IntercomApi
import intercom.net.Participant
import org.json.JSONException
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RTCStatsReport
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

enum class ConnectionStatus { CONNECTING, CONNECTED, RECONNECTING, FAILED }

data class CallState(
    val productionName: String? = null,
    val lineName: String? = null,
    /** A program ("audio feed") line: one program user feeds it, everyone else only listens. */
    val isProgramLine: Boolean,
    val isProgramUser: Boolean,
    val status: ConnectionStatus = ConnectionStatus.CONNECTING,
    val error: String? = null,
    val sessionId: String? = null,
    val participants: List<Participant> = emptyList(),
    /** Endpoint id of the loudest participant, as reported by the media server. */
    val dominantSpeaker: String? = null,
    /** Audio is currently above the speaking threshold. */
    val audioActive: Boolean = false,
    /** [audioActive], debounced so the card highlight does not flicker. */
    val someoneSpeaking: Boolean = false,
    val isInputMuted: Boolean = true,
    val isOutputMuted: Boolean = false,
    val isTalking: Boolean = false,
    val volume: Float,
) {
    val canTalk: Boolean get() = if (isProgramLine) isProgramUser else true
    val isMicrophoneLive: Boolean get() = canTalk && !isInputMuted
}

/**
 * One joined line: the manager session, its WebRTC connection to the media
 * server, and the participant list. Follows the web client's
 * `use-establish-session` / `use-rtc-connection` / `use-heartbeat` /
 * `use-line-polling` hooks, and additionally rejoins by itself when the
 * connection drops (a phone changes networks far more often than a desktop).
 */
class LineCall(
    private val ref: CallRef,
    private val username: String,
    private val api: IntercomApi,
    private val rtc: () -> RtcEngine,
    private val microphoneGate: MicrophoneGate,
    initialVolume: Float,
    parentScope: CoroutineScope,
    private val onConnected: () -> Unit,
    private val onKicked: (LineCall) -> Unit,
) {
    val key: String = ref.key
    val lineId: String = ref.lineId

    private val _state = MutableStateFlow(
        CallState(
            isProgramLine = ref.role != null,
            isProgramUser = ref.role == LineRole.PROGRAM_USER,
            isOutputMuted = ref.role == LineRole.PROGRAM_USER,
            volume = initialVolume,
        )
    )
    val state: StateFlow<CallState> = _state.asStateFlow()

    // Every WebRTC object below is only touched from this single-threaded
    // dispatcher. WebRTC's own callbacks arrive on its signaling thread and
    // must hop here rather than call back into the peer connection.
    private val scope = CoroutineScope(
        SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.Default.limitedParallelism(1)
    )
    private var sessionJob: Job? = null
    private var everConnected = false
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var microphoneTrack: AudioTrack? = null
    private var appliedMicrophoneEnabled: Boolean? = null
    private var appliedOutput: Pair<Boolean, Float>? = null

    // Mute and volume changes update [state] and the microphone gate at once,
    // then raise this signal; the session applies the latest state to WebRTC
    // when it gets to it. Being conflated, a burst of changes costs one round
    // of (blocking) WebRTC calls.
    private val audioStateChanged = Channel<Unit>(Channel.CONFLATED)
    private val remoteTracksChanged = AtomicBoolean()

    // Counts push-to-talk presses and releases, so a delayed "start talking"
    // can tell whether its press is still being held.
    private val pushToTalkPress = AtomicInteger()
    private val armedPress = AtomicInteger(NOT_ARMED)

    @Volatile
    private var closed = false

    fun start() {
        scope.launch { participantsLoop() }
        startSession()
    }

    /** Tries again after a failure that automatic reconnection gave up on. */
    fun retry() = startSession()

    fun close() {
        closed = true
        scope.cancel()
        syncMicrophoneGate()
    }

    fun toggleInputMute() = setInputMuted(!_state.value.isInputMuted)

    fun toggleOutputMute() = updateAudioState { it.copy(isOutputMuted = !it.isOutputMuted) }

    // Raising the volume un-mutes, like the web client.
    fun setVolume(volume: Float) = updateAudioState {
        it.copy(volume = volume, isOutputMuted = it.isOutputMuted && volume <= 0f)
    }

    fun pushToTalkDown() {
        val press = pushToTalkPress.incrementAndGet()
        // Enabling the track is a blocking WebRTC call, and WebRTC may have
        // stopped capturing while every track was muted. Start now, still
        // silenced by the gate, so the hold-off below absorbs that delay
        // instead of clipping the first words.
        if (!microphoneGate.isOpen) {
            armedPress.set(press)
            audioStateChanged.trySend(Unit)
        }
        // Not on the WebRTC dispatcher: opening the microphone must not wait for it.
        scope.launch(Dispatchers.Default) {
            // Short hold-off so a stray tap does not open the microphone.
            delay(PUSH_TO_TALK_DELAY_MS)
            updateAudioState {
                // Checked inside the update so a release that races with this always wins.
                if (pushToTalkPress.get() == press) it.copy(isInputMuted = false, isTalking = true) else it
            }
        }
    }

    fun pushToTalkUp() {
        pushToTalkPress.incrementAndGet()
        updateAudioState { it.copy(isInputMuted = true, isTalking = false) }
    }

    /** Asks the media server to mute another participant for everyone on the line. */
    fun muteParticipant(endpointId: String) {
        scope.launch {
            val channel = dataChannel ?: return@launch
            if (channel.state() != DataChannel.State.OPEN) return@launch
            val message = JSONObject()
                .put("type", "EndpointMessage")
                .put("to", endpointId)
                .put("payload", JSONObject().put("muteParticipant", "mute"))
            channel.send(DataChannel.Buffer(ByteBuffer.wrap(message.toString().toByteArray()), false))
        }
    }

    private fun setInputMuted(muted: Boolean) = updateAudioState {
        it.copy(isInputMuted = muted, isTalking = it.isTalking && !muted)
    }

    /** Safe from any thread. */
    private inline fun updateAudioState(change: (CallState) -> CallState) {
        _state.update(change)
        syncMicrophoneGate()
        audioStateChanged.trySend(Unit)
    }

    private fun syncMicrophoneGate() {
        microphoneGate.update(key) { !closed && _state.value.isMicrophoneLive }
    }

    private fun applyAudioState() {
        val current = _state.value
        // Microphone first, and only what changed: these calls block on WebRTC's threads.
        val armed = armedPress.get() == pushToTalkPress.get()
        val microphoneEnabled = current.isMicrophoneLive || (armed && current.canTalk)
        microphoneTrack?.let { track ->
            if (microphoneEnabled != appliedMicrophoneEnabled) {
                track.setEnabled(microphoneEnabled)
                appliedMicrophoneEnabled = microphoneEnabled
            }
        }

        val pc = peerConnection ?: return
        val output = current.isOutputMuted to current.volume
        if (remoteTracksChanged.getAndSet(false) || output != appliedOutput) {
            val volume = if (current.isOutputMuted) 0.0 else current.volume.toDouble()
            // Receivers are re-read every time: getReceivers() invalidates the
            // track objects handed out by the previous call.
            pc.receivers.forEach { receiver ->
                (receiver.track() as? AudioTrack)?.let { track ->
                    track.setEnabled(!current.isOutputMuted)
                    track.setVolume(volume)
                }
            }
            appliedOutput = output
        }
    }

    @Synchronized
    private fun startSession() {
        if (sessionJob?.isActive == true) return
        _state.update { it.copy(status = ConnectionStatus.CONNECTING, error = null) }
        sessionJob = scope.launch { sessionLoop() }
    }

    private suspend fun sessionLoop() {
        var failures = 0
        while (true) {
            val end = try {
                runSession(onConnected = {
                    failures = 0
                    everConnected = true
                    onConnected()
                })
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                SessionEnd.Lost(e.message.orEmpty(), fatal = e.isFatal)
            } catch (e: IOException) {
                SessionEnd.Lost("Could not reach the server: ${e.message ?: e.javaClass.simpleName}")
            } catch (e: Exception) {
                SessionEnd.Lost(e.message ?: e.javaClass.simpleName)
            }

            when (end) {
                SessionEnd.Kicked -> {
                    onKicked(this)
                    return
                }
                is SessionEnd.Lost -> {
                    if (end.fatal) {
                        _state.update { it.copy(status = ConnectionStatus.FAILED, error = end.message, sessionId = null) }
                        return
                    }
                    failures++
                    _state.update {
                        it.copy(
                            status = if (everConnected) ConnectionStatus.RECONNECTING else ConnectionStatus.CONNECTING,
                            error = end.message,
                            sessionId = null,
                        )
                    }
                    delay(backoffMs(failures, baseMs = REJOIN_BASE_DELAY_MS))
                }
            }
        }
    }

    /** Runs one manager session from offer to teardown; returns why it ended. */
    private suspend fun runSession(onConnected: () -> Unit): SessionEnd {
        val offer = api.offerSession(ref.productionId, ref.lineId, username)
        try {
            _state.update { it.copy(sessionId = offer.sessionId) }
            val ended = CompletableDeferred<SessionEnd>()
            val connected = CompletableDeferred<Unit>()
            val pc = rtc().createPeerConnection(PeerObserver(ended, connected))
                ?: throw IOException("Could not create a WebRTC connection.")
            peerConnection = pc
            try {
                // The manager reads our audio SSRC from the answer, so the
                // microphone track has to be attached before answering.
                pc.addTrack(rtc().createMicrophoneTrack("mic-$key").also { microphoneTrack = it })
                dataChannel = pc.createDataChannel("conference-data-channel", DataChannel.Init().apply { ordered = true })
                    .also { it.registerObserver(ChannelObserver(ended)) }
                applyAudioState()

                pc.awaitSetRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, offer.sdp))
                val answer = pc.awaitCreateAnswer()
                pc.awaitSetLocalDescription(answer)
                applyAudioState()
                // The media server is the controlling ICE agent with reachable
                // candidates of its own, so (like the web client) the answer is
                // sent without waiting for our candidates.
                api.patchSession(offer.sessionId, answer.description)

                return coroutineScope {
                    launch { for (change in audioStateChanged) applyAudioState() }
                    launch { heartbeatLoop(offer.sessionId, ended) }
                    launch { audioLevelLoop(pc) }
                    launch {
                        if (withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connected.await() } == null) {
                            ended.complete(SessionEnd.Lost("Timed out connecting to the media server."))
                        } else {
                            onConnected()
                        }
                    }
                    ended.await().also { coroutineContext.cancelChildren() }
                }
            } finally {
                closePeerConnection()
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    api.deleteSession(offer.sessionId)
                } catch (e: IOException) {
                    // The manager expires sessions that stop sending heartbeats.
                }
            }
        }
    }

    private fun closePeerConnection() {
        dataChannel?.let {
            it.unregisterObserver()
            it.dispose()
        }
        dataChannel = null
        peerConnection?.dispose()
        peerConnection = null
        microphoneTrack?.dispose()
        microphoneTrack = null
        appliedMicrophoneEnabled = null
        appliedOutput = null
        _state.update { it.copy(dominantSpeaker = null, audioActive = false, someoneSpeaking = false) }
    }

    private suspend fun heartbeatLoop(sessionId: String, ended: CompletableDeferred<SessionEnd>) {
        var failures = 0
        while (true) {
            delay(if (failures == 0) HEARTBEAT_INTERVAL_MS else backoffMs(failures, baseMs = HEARTBEAT_INTERVAL_MS))
            try {
                api.heartbeat(sessionId)
                failures = 0
            } catch (e: ApiException) {
                if (e.status == 410 || e.status == 404) {
                    ended.complete(SessionEnd.Lost("The session expired."))
                    return
                }
                failures++
            } catch (e: IOException) {
                failures++
            }
        }
    }

    private suspend fun audioLevelLoop(pc: PeerConnection) {
        var active = false
        var activeChangedAt = SystemClock.elapsedRealtime()
        var speaking = false
        while (true) {
            delay(AUDIO_LEVEL_INTERVAL_MS)
            val level = suspendCancellableCoroutine<Double> { continuation ->
                pc.getStats { report -> continuation.resume(maxAudioLevel(report)) }
            }
            val now = SystemClock.elapsedRealtime()
            if ((level > AUDIO_LEVEL_THRESHOLD) != active) {
                active = !active
                activeChangedAt = now
            }
            if (active && now - activeChangedAt >= SPEAKING_START_MS) speaking = true
            if (!active && now - activeChangedAt >= SPEAKING_STOP_MS) speaking = false
            _state.update {
                if (it.audioActive == active && it.someoneSpeaking == speaking) it
                else it.copy(audioActive = active, someoneSpeaking = speaking)
            }
        }
    }

    private fun maxAudioLevel(report: RTCStatsReport): Double =
        report.statsMap.values
            .filter { it.type == "inbound-rtp" || it.type == "media-source" }
            .maxOfOrNull { (it.members["audioLevel"] as? Number)?.toDouble() ?: 0.0 }
            ?: 0.0

    /**
     * Fetches the line once for its metadata, then keeps the participant list
     * current through the manager's long-poll endpoint.
     */
    private suspend fun participantsLoop() {
        var failures = 0
        var seeded = false
        var longPoll = true
        while (true) {
            try {
                if (!seeded) {
                    val line = api.fetchLine(ref.productionId, ref.lineId)
                    updateAudioState {
                        it.copy(
                            lineName = line.name,
                            isProgramLine = line.programOutputLine,
                            participants = line.participants,
                            isOutputMuted = it.isOutputMuted || (line.programOutputLine && it.isProgramUser),
                        )
                    }
                    seeded = true
                    scope.launch { fetchProductionName() }
                } else {
                    val participants = if (longPoll) {
                        api.longPollParticipants(ref.productionId, ref.lineId)
                    } else {
                        delay(LINE_POLL_INTERVAL_MS)
                        api.fetchLine(ref.productionId, ref.lineId).participants
                    }
                    _state.update { it.copy(participants = participants) }
                }
                failures = 0
            } catch (e: IOException) {
                if (seeded && longPoll && e is ApiException && (e.status == 404 || e.status == 405)) {
                    // A manager without the long-poll endpoint: poll the line instead.
                    longPoll = false
                    continue
                }
                failures++
                delay(backoffMs(failures))
            }
        }
    }

    private suspend fun fetchProductionName() {
        repeat(3) { attempt ->
            try {
                val name = api.fetchProductionName(ref.productionId)
                _state.update { it.copy(productionName = name) }
                return
            } catch (e: IOException) {
                delay(backoffMs(attempt + 1))
            }
        }
    }

    private fun handleDataChannelMessage(text: String, ended: CompletableDeferred<SessionEnd>) {
        val message = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return
        }
        when (message.optString("type")) {
            "DominantSpeaker" -> {
                val endpoint = message.optString("endpoint")
                if (endpoint.isNotEmpty()) _state.update { it.copy(dominantSpeaker = endpoint) }
            }
            "EndpointMessage" -> {
                val payload = message.optJSONObject("payload") ?: return
                // Ignore the echo of a command this client sent.
                if (message.optString("to") == message.optString("from")) return
                when {
                    payload.optString("forceDisconnect") == "disconnect" -> ended.complete(SessionEnd.Kicked)
                    payload.optString("muteParticipant") == "mute" ->
                        if (!_state.value.isProgramLine) setInputMuted(true)
                }
            }
        }
    }

    private sealed interface SessionEnd {
        data class Lost(val message: String, val fatal: Boolean = false) : SessionEnd
        data object Kicked : SessionEnd
    }

    private inner class PeerObserver(
        private val ended: CompletableDeferred<SessionEnd>,
        private val connected: CompletableDeferred<Unit>,
    ) : PeerConnection.Observer {
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    _state.update { it.copy(status = ConnectionStatus.CONNECTED, error = null) }
                    connected.complete(Unit)
                }
                // Usually recovers by itself; if not, FAILED follows.
                PeerConnection.PeerConnectionState.DISCONNECTED -> _state.update {
                    if (it.status == ConnectionStatus.CONNECTED) it.copy(status = ConnectionStatus.RECONNECTING) else it
                }
                PeerConnection.PeerConnectionState.FAILED ->
                    ended.complete(SessionEnd.Lost("The connection to the media server was lost."))
                else -> Unit
            }
        }

        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
            remoteTracksChanged.set(true)
            audioStateChanged.trySend(Unit)
        }

        override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidate(candidate: IceCandidate?) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    private inner class ChannelObserver(
        private val ended: CompletableDeferred<SessionEnd>,
    ) : DataChannel.Observer {
        override fun onMessage(buffer: DataChannel.Buffer) {
            val bytes = ByteArray(buffer.data.remaining())
            buffer.data.get(bytes)
            handleDataChannelMessage(String(bytes, Charsets.UTF_8), ended)
        }

        override fun onBufferedAmountChange(previousAmount: Long) = Unit
        override fun onStateChange() = Unit
    }

    private companion object {
        const val HEARTBEAT_INTERVAL_MS = 10_000L
        const val LINE_POLL_INTERVAL_MS = 3_000L
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val REJOIN_BASE_DELAY_MS = 2_000L
        const val PUSH_TO_TALK_DELAY_MS = 300L
        const val AUDIO_LEVEL_INTERVAL_MS = 200L
        const val AUDIO_LEVEL_THRESHOLD = 0.02
        const val SPEAKING_START_MS = 500L
        const val SPEAKING_STOP_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val NOT_ARMED = -1

        /** Exponential backoff with ±20% jitter; [failures] is 1 after the first failure. */
        fun backoffMs(failures: Int, baseMs: Long = 1_000L): Long {
            val exponential = (baseMs shl (failures - 1).coerceIn(0, 10)).coerceAtMost(MAX_BACKOFF_MS)
            return (exponential * Random.nextDouble(0.8, 1.2)).toLong()
        }
    }
}

private abstract class SdpResult : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription?) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String?) = Unit
    override fun onSetFailure(error: String?) = Unit
}

private suspend fun PeerConnection.awaitSetRemoteDescription(description: SessionDescription) =
    suspendCancellableCoroutine<Unit> { continuation ->
        setRemoteDescription(object : SdpResult() {
            override fun onSetSuccess() = continuation.resume(Unit)
            override fun onSetFailure(error: String?) =
                continuation.resumeWithException(IOException("The media server's offer was rejected: $error"))
        }, description)
    }

private suspend fun PeerConnection.awaitSetLocalDescription(description: SessionDescription) =
    suspendCancellableCoroutine<Unit> { continuation ->
        setLocalDescription(object : SdpResult() {
            override fun onSetSuccess() = continuation.resume(Unit)
            override fun onSetFailure(error: String?) =
                continuation.resumeWithException(IOException("Could not apply the local answer: $error"))
        }, description)
    }

private suspend fun PeerConnection.awaitCreateAnswer(): SessionDescription =
    suspendCancellableCoroutine { continuation ->
        createAnswer(object : SdpResult() {
            override fun onCreateSuccess(description: SessionDescription?) {
                if (description != null) continuation.resume(description)
                else continuation.resumeWithException(IOException("No SDP in answer."))
            }

            override fun onCreateFailure(error: String?) =
                continuation.resumeWithException(IOException("Could not create an answer: $error"))
        }, MediaConstraints())
    }

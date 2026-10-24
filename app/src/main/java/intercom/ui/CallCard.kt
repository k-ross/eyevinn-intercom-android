package intercom.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColor
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import intercom.call.CallState
import intercom.call.ConnectionStatus
import intercom.call.LineCall
import intercom.net.Participant
import kotlin.math.roundToInt

private val CardShape = RoundedCornerShape(10.dp)
private val ControlShape = RoundedCornerShape(8.dp)
private val ButtonShape = RoundedCornerShape(6.dp)
private val RowButtonShape = RoundedCornerShape(4.dp)

/** One joined line, laid out like the web client's call card. */
@Composable
fun CallCard(
    call: LineCall,
    onVolumeChange: (Float) -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by call.state.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(true) }
    var confirmLeave by remember { mutableStateOf(false) }
    var muteTarget by remember { mutableStateOf<Participant?>(null) }

    val connected = state.status == ConnectionStatus.CONNECTED
    val selfEndpoint = state.participants.firstOrNull { it.sessionId == state.sessionId }?.endpointId
    val whipOnLine = state.participants.any { it.isWhip }
    val selfIsSpeaker = selfEndpoint != null && selfEndpoint == state.dominantSpeaker
    // Red outline while somebody else is talking on the line.
    val highlighted = connected && state.someoneSpeaking && !state.isProgramLine && !whipOnLine && !selfIsSpeaker
    val outline by animateColorAsState(
        targetValue = if (highlighted) IntercomColors.Danger else Color.Transparent,
        animationSpec = tween(300),
        label = "speaking outline",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(3.dp, outline, CardShape)
            .padding(3.dp)
            .clip(CardShape)
            .background(if (state.isProgramLine) IntercomColors.ProgramCard else IntercomColors.Card)
            .border(1.dp, IntercomColors.CardBorder, CardShape),
    ) {
        CallHeader(state = state, lineId = call.lineId, open = open, onToggle = { open = !open })
        when {
            !connected -> ConnectionBody(state = state, onRetry = call::retry, onLeave = onLeave)
            open -> Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
                if (state.isProgramLine) AudioFeedLabel()
                CallControls(state = state, call = call, onVolumeChange = onVolumeChange)
                ParticipantsSection(
                    state = state,
                    onMute = { muteTarget = it },
                )
                LeaveButton(onClick = { confirmLeave = true }, modifier = Modifier.padding(top = 10.dp))
            }
            else -> MinifiedControls(state = state, call = call)
        }
    }

    if (confirmLeave) {
        ConfirmDialog(
            text = "Are you sure you want to leave ${state.lineName ?: "this call"}?",
            onConfirm = {
                confirmLeave = false
                onLeave()
            },
            onDismiss = { confirmLeave = false },
        )
    }
    muteTarget?.let { target ->
        ConfirmDialog(
            text = "Are you sure you want to mute ${target.name}?",
            detail = "This will mute ${target.name} for everyone in the line.",
            onConfirm = {
                target.endpointId?.let(call::muteParticipant)
                muteTarget = null
            },
            onDismiss = { muteTarget = null },
        )
    }
}

@Composable
private fun CallHeader(state: CallState, lineId: String, open: Boolean, onToggle: () -> Unit) {
    val lineName = state.lineName ?: "Line $lineId"
    val title = state.productionName?.let { "$it / $lineName" } ?: lineName
    val users = state.participants.count { !it.isWhip }
    val whipSessions = state.participants.count { it.isWhip }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = if (open) "Collapse line details" else "Expand line details",
                onClick = onToggle,
            )
            .padding(start = 20.dp, top = 20.dp, bottom = 20.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (whipSessions > 0) {
            ParticipantCount(IntercomIcons.Whip, whipSessions, IntercomColors.Accent, "WHIP sessions")
            Spacer(Modifier.width(8.dp))
        }
        ParticipantCount(
            IntercomIcons.Users,
            users,
            if (users > 0) IntercomColors.CountActive else IntercomColors.Count,
            "Participants",
        )
        Icon(
            imageVector = if (open) IntercomIcons.ChevronUp else IntercomIcons.ChevronDown,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.padding(start = 6.dp).size(30.dp),
        )
    }
}

@Composable
private fun ParticipantCount(icon: ImageVector, count: Int, tint: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$label: $count" },
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text("$count", color = IntercomColors.Count, fontSize = 15.sp, modifier = Modifier.padding(start = 5.dp))
    }
}

@Composable
private fun AudioFeedLabel() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
        Icon(IntercomIcons.Tv, contentDescription = null, tint = IntercomColors.Accent, modifier = Modifier.size(15.dp))
        Text("Audio feed", color = IntercomColors.Accent, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp))
    }
}

/** Shown instead of the controls until the media connection is up. */
@Composable
private fun ConnectionBody(state: CallState, onRetry: () -> Unit, onLeave: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.status == ConnectionStatus.FAILED) {
            Text("Connection failed", color = IntercomColors.Danger, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        } else {
            CircularProgressIndicator(color = IntercomColors.Accent, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            Text(
                text = if (state.status == ConnectionStatus.RECONNECTING) "Reconnecting…" else "Connecting…",
                color = IntercomColors.Text,
                fontSize = 16.sp,
            )
        }
        state.error?.let { BodyText(it) }
        if (state.status == ConnectionStatus.FAILED) {
            ActionButton("Try again", onClick = onRetry, modifier = Modifier.fillMaxWidth())
        }
        LeaveButton(onClick = onLeave)
    }
}

@Composable
private fun CallControls(state: CallState, call: LineCall, onVolumeChange: (Float) -> Unit) {
    // The program user feeds the line and does not listen to it.
    val listens = !(state.isProgramLine && state.isProgramUser)
    if (listens) {
        VolumeSlider(value = state.volume, onValueChange = onVolumeChange)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (listens) SpeakerButton(state, call, Modifier.weight(1f))
        if (state.showsMicButton) MicButton(state, call, Modifier.weight(1f))
    }
    if (!state.isProgramLine) {
        PushToTalkButton(
            isTalking = state.isTalking,
            onPress = call::pushToTalkDown,
            onRelease = call::pushToTalkUp,
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        )
    }
}

/** The collapsed card keeps the essential controls on one row. */
@Composable
private fun MinifiedControls(state: CallState, call: LineCall) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!(state.isProgramLine && state.isProgramUser)) SpeakerButton(state, call, Modifier.width(64.dp))
        if (state.showsMicButton) MicButton(state, call, Modifier.width(64.dp))
        if (!state.isProgramLine) {
            PushToTalkButton(
                isTalking = state.isTalking,
                onPress = call::pushToTalkDown,
                onRelease = call::pushToTalkUp,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private val CallState.showsMicButton: Boolean
    get() = if (isProgramLine) isProgramUser else !isProgramUser

@Composable
private fun SpeakerButton(state: CallState, call: LineCall, modifier: Modifier) {
    val silent = state.isOutputMuted || state.volume <= 0f
    ControlButton(
        icon = if (silent) IntercomIcons.SpeakerOff else IntercomIcons.SpeakerOn,
        description = if (silent) "Speaker is muted. Unmute speaker" else "Speaker is on. Mute speaker",
        on = !silent,
        enabled = state.volume > 0f,
        onClick = call::toggleOutputMute,
        modifier = modifier,
    )
}

@Composable
private fun MicButton(state: CallState, call: LineCall, modifier: Modifier) {
    ControlButton(
        icon = if (state.isInputMuted) IntercomIcons.MicOff else IntercomIcons.MicOn,
        description = if (state.isInputMuted) "Microphone is muted. Unmute microphone" else "Microphone is on. Mute microphone",
        on = !state.isInputMuted,
        onClick = call::toggleInputMute,
        modifier = modifier,
    )
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    description: String,
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(ControlShape)
            .background(if (enabled) IntercomColors.Control else IntercomColors.Control.copy(alpha = 0.5f))
            .border(2.dp, IntercomColors.ControlBorder, ControlShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (on) IntercomColors.On else IntercomColors.Danger,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun VolumeSlider(value: Float, onValueChange: (Float) -> Unit) {
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(IntercomIcons.NoSound, contentDescription = null, tint = IntercomColors.Accent, modifier = Modifier.size(40.dp))
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .semantics {
                    contentDescription = "Volume"
                    progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f, steps = VOLUME_STEPS - 1)
                    setProgress { target ->
                        currentOnValueChange(target.coerceIn(0f, 1f))
                        true
                    }
                }
                .pointerInput(Unit) {
                    val inset = SLIDER_INSET.toPx()
                    fun report(x: Float) {
                        val fraction = ((x - inset) / (size.width - 2 * inset)).coerceIn(0f, 1f)
                        currentOnValueChange((fraction * VOLUME_STEPS).roundToInt() / VOLUME_STEPS.toFloat())
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        report(down.position.x)
                        drag(down.id) { change ->
                            change.consume()
                            report(change.position.x)
                        }
                    }
                },
        ) {
            val inset = SLIDER_INSET.toPx()
            val y = size.height / 2
            drawLine(
                color = IntercomColors.SliderTrack,
                start = Offset(inset, y),
                end = Offset(size.width - inset, y),
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = IntercomColors.Accent,
                radius = 8.dp.toPx(),
                center = Offset(inset + (size.width - 2 * inset) * value.coerceIn(0f, 1f), y),
            )
        }
        Icon(IntercomIcons.SpeakerOn, contentDescription = null, tint = IntercomColors.Accent, modifier = Modifier.size(40.dp).padding(3.dp))
    }
}

/** Hold to talk; the microphone closes again on release. */
@Composable
private fun PushToTalkButton(
    isTalking: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnPress by rememberUpdatedState(onPress)
    val currentOnRelease by rememberUpdatedState(onRelease)
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(isTalking) {
        if (isTalking) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    val background = if (isTalking) {
        val pulse by rememberInfiniteTransition(label = "talking").animateColor(
            initialValue = IntercomColors.TalkPulseStart,
            targetValue = IntercomColors.TalkPulseEnd,
            animationSpec = infiniteRepeatable(tween(700, easing = EaseInOut), RepeatMode.Reverse),
            label = "talking pulse",
        )
        pulse
    } else {
        IntercomColors.Control
    }
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(ButtonShape)
            .background(background)
            .border(2.dp, IntercomColors.ControlBorder, ButtonShape)
            .semantics {
                role = Role.Button
                contentDescription = if (isTalking) "Talking. Release to stop" else "Push to talk. Hold to speak"
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    currentOnPress()
                    try {
                        // Swallow every move so a drifting finger does not turn into a scroll.
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    } finally {
                        currentOnRelease()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (!isTalking) {
            Text("Push To Talk", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ParticipantsSection(state: CallState, onMute: (Participant) -> Unit) {
    var open by rememberSaveable { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(IntercomColors.CardBorder))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = if (open) "Hide participants" else "Show participants") { open = !open }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "PARTICIPANTS",
                color = IntercomColors.TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (open) IntercomIcons.ChevronUp else IntercomIcons.ChevronDown,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(30.dp),
            )
        }
        AnimatedVisibility(visible = open) {
            ParticipantList(state, onMute)
        }
    }
}

@Composable
private fun ParticipantList(state: CallState, onMute: (Participant) -> Unit) {
    val whipOnLine = state.participants.any { it.isWhip }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .border(2.dp, IntercomColors.ListBorder, CardShape),
    ) {
        state.participants.forEachIndexed { index, participant ->
            val isYou = participant.sessionId == state.sessionId
            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(IntercomColors.ListDivider))
            ParticipantRow(
                participant = participant,
                isYou = isYou,
                isTalking = !whipOnLine && state.audioActive && participant.endpointId != null &&
                    participant.endpointId == state.dominantSpeaker,
                onMute = if (!isYou && participant.isActive && !participant.isWhip &&
                    !state.isProgramLine && participant.endpointId != null
                ) {
                    { onMute(participant) }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun ParticipantRow(participant: Participant, isYou: Boolean, isTalking: Boolean, onMute: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isYou) IntercomColors.RowSelf else IntercomColors.Row)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // White ring while this participant is the one talking.
        Box(
            modifier = Modifier
                .size(40.dp)
                .border(3.dp, if (isTalking) IntercomColors.RowText else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(
                        when {
                            !participant.isActive -> IntercomColors.Inactive
                            participant.isWhip -> IntercomColors.Accent
                            else -> IntercomColors.Online
                        },
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (participant.isWhip) IntercomIcons.Whip else IntercomIcons.User,
                    contentDescription = null,
                    tint = IntercomColors.AvatarGlyph,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Text(
            text = if (participant.isActive) participant.name else "${participant.name} (inactive)",
            color = IntercomColors.RowText,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 10.dp),
        )
        if (onMute != null) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RowButtonShape)
                    .background(IntercomColors.RowButton)
                    .border(1.dp, IntercomColors.RowButtonBorder, RowButtonShape)
                    .clickable(role = Role.Button, onClick = onMute),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    IntercomIcons.MicOff,
                    contentDescription = "Mute ${participant.name}",
                    tint = IntercomColors.Danger,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun LeaveButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(ButtonShape)
            .border(2.dp, IntercomColors.Danger.copy(alpha = 0.5f), ButtonShape)
            .clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Leave Call", color = IntercomColors.Danger, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Icon(
            IntercomIcons.Logout,
            contentDescription = null,
            tint = IntercomColors.Danger,
            modifier = Modifier.padding(start = 10.dp).size(25.dp),
        )
    }
}

private val SLIDER_INSET = 12.dp
private const val VOLUME_STEPS = 20

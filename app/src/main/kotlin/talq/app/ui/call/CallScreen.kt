package talq.app.ui.call

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import talq.app.appContainer
import talq.app.call.AudioOutput
import talq.app.call.CallActivity
import talq.app.call.CallController
import talq.app.call.WebRtcEngine
import talq.app.theme.TalqTheme
import talq.app.ui.common.Avatar
import talq.core.call.CallEndReason
import talq.core.call.CallState
import talq.core.call.MediaConnection
import talq.core.call.StreamKind
import talq.core.call.SubscriberKey

/** Everything the call activity shows, by what it was opened for. It is drawn in the dark Vivid theme. */
@Composable
fun CallRoot(request: CallActivity.Request, onRequest: (CallActivity.Request) -> Unit, onDone: () -> Unit) {
    val container = LocalContext.current.appContainer
    val current by container.calls.current.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(TalqTheme.colors.bgPrimary)) {
        when (request) {
            is CallActivity.Request.Ring -> RingScreen(
                request,
                onAnswer = { onRequest(CallActivity.Request.Answer(request.token, request.nid, System.nanoTime())) },
                onDone = onDone,
            )
            is CallActivity.Request.Outgoing, is CallActivity.Request.Answer -> Starter(request, onRequest, onDone)
            CallActivity.Request.Unlocking -> CenterText("Unlock the phone to call")
            CallActivity.Request.Show -> {
                val call = current
                val starting by container.calls.starting.collectAsStateWithLifecycle()
                when {
                    call != null -> InCallScreen(call, onDone)
                    // Back to a call that is still being set up (from its notification).
                    starting -> Connecting(onCancel = { container.calls.cancelStart(); onDone() })
                    else -> LaunchedEffect(Unit) { onDone() }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- ringing

@Composable
private fun RingScreen(request: CallActivity.Request.Ring, onAnswer: () -> Unit, onDone: () -> Unit) {
    val container = LocalContext.current.appContainer
    val colors = TalqTheme.colors
    val ringing by container.ringer.ringing.collectAsStateWithLifecycle()
    val ring = ringing?.takeIf { it.nid == request.nid }
    KeepScreenOn()
    // Stopped ringing (the caller gave up, answered elsewhere, declined from the shade): close.
    LaunchedEffect(ring == null) { if (ring == null) onDone() }
    if (ring == null) return
    val session = container.session
    val compact = landscape()
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(if (compact) 8.dp else 72.dp))
        val picture = when {
            session == null -> null
            ring.callerUserId.isNotEmpty() -> session.client.userAvatarUrl(ring.callerUserId, 256)
            // A group: the conversation's picture, as the call screen draws it.
            ring.oneToOne == false -> session.client.roomAvatarUrl(ring.token, session.rooms.room(ring.token)?.avatarVersion.orEmpty(), !colors.isLight)
            else -> null
        }
        if (session != null && picture != null) {
            Avatar(picture, session.client.authorizationHeader, ring.callerName, size = if (compact) 72.dp else 120.dp)
            Spacer(Modifier.height(if (compact) 8.dp else 20.dp))
        }
        Text(ring.callerName, style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(if (ring.oneToOne == false) "Group call" else "Incoming call", style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary)
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RoundButton(Icons.Filled.CallEnd, "Decline", colors.danger, size = 72) {
                container.ringer.decline(ring.nid)
                onDone()
            }
            RoundButton(Icons.Filled.Call, "Answer", colors.online, size = 72, onClick = onAnswer)
        }
        Spacer(Modifier.height(if (compact) 8.dp else 40.dp))
    }
}

// ---------------------------------------------------------------- starting

private sealed interface StartPhase {
    data object Asking : StartPhase
    data object Consent : StartPhase
    data object Starting : StartPhase
    data object Running : StartPhase

    /** Nothing was started: [message] for a moment; [settings] offers the app's settings (a denied permission). */
    data class Stopped(val message: String, val settings: Boolean = false, val showRunning: Boolean = false) : StartPhase
}

/**
 * The account, the permissions, recording consent where the server wants it, then the call:
 * asked of the controller, which runs the start in its own scope; this screen only shows how it
 * goes. Cancel and Back tell the controller not to place it.
 */
@Composable
private fun Starter(request: CallActivity.Request, onRequest: (CallActivity.Request) -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val container = context.appContainer
    val calls = container.calls
    val (token, launch) = when (request) {
        is CallActivity.Request.Outgoing -> request.token to request.launch
        is CallActivity.Request.Answer -> request.token to request.launch
        else -> return
    }
    val wantsVideo = request is CallActivity.Request.Outgoing && request.video
    val nid = (request as? CallActivity.Request.Answer)?.nid ?: 0L
    var phase by remember(request) { mutableStateOf<StartPhase>(StartPhase.Asking) }
    var video by remember(request) { mutableStateOf(wantsVideo) }
    var notice by remember(request) { mutableStateOf<String?>(null) }
    // Given up here: nothing is asked of the controller any more (the screen goes a moment later).
    var cancelled by remember(request) { mutableStateOf(false) }

    fun cancel() {
        cancelled = true
        if (phase == StartPhase.Starting) calls.cancelStart()
        // Asked for and placed just now (begin() answers Running for a call already there, so a
        // call in this conversation while this screen is Starting is this screen's own): hang up.
        if (phase == StartPhase.Starting && calls.current.value?.token == token) calls.hangUp()
        // An answer given up before it was asked for is a missed call.
        if (request is CallActivity.Request.Answer && phase != StartPhase.Starting) container.ringer.answerSettled(nid, answered = false)
        onDone()
    }

    BackHandler(enabled = phase !is StartPhase.Running && phase !is StartPhase.Stopped) { cancel() }

    fun begin(consent: Boolean) {
        if (cancelled) return
        phase = StartPhase.Starting
        calls.begin(
            CallController.StartRequest(
                launch, token, video, incoming = request is CallActivity.Request.Answer, nid, consent,
                join = (request as? CallActivity.Request.Outgoing)?.join == true,
            ),
        )
    }

    fun stop(message: String, settings: Boolean = false) {
        if (request is CallActivity.Request.Answer) container.ringer.answerSettled(nid, answered = false)
        phase = StartPhase.Stopped(message, settings = settings)
    }

    fun afterPermissions() {
        if (cancelled) return
        if (!CallActivity.granted(context, Manifest.permission.RECORD_AUDIO)) {
            stop("Microphone permission is needed for calls.", settings = true)
            return
        }
        if (video && !CallActivity.granted(context, Manifest.permission.CAMERA)) {
            video = false
            notice = "No camera permission: the call goes on without video."
        }
        val session = container.session
        if (session != null && calls.recordingConsentNeeded(session, token)) phase = StartPhase.Consent else begin(consent = false)
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { afterPermissions() }
    LaunchedEffect(request) {
        if (request is CallActivity.Request.Answer) container.ringer.answered(request.nid)
        // Opened from a notification, the app may still be signing in.
        if (calls.awaitAccount() == null) {
            stop("You are not signed in, or the server can't be reached right now.")
            return@LaunchedEffect
        }
        val missing = CallActivity.permissionsFor(wantsVideo).filterNot { CallActivity.granted(context, it) }
        if (missing.isEmpty()) afterPermissions() else launcher.launch(missing.toTypedArray())
    }
    // The controller's answer to this request.
    val last by calls.lastStart.collectAsStateWithLifecycle()
    LaunchedEffect(last) {
        val (id, result) = last ?: return@LaunchedEffect
        if (id != launch || phase != StartPhase.Starting) return@LaunchedEffect
        phase = when (result) {
            CallController.StartResult.Started, CallController.StartResult.Running -> StartPhase.Running
            CallController.StartResult.Busy -> StartPhase.Stopped("You are in another call.", showRunning = true)
            is CallController.StartResult.Gone ->
                StartPhase.Stopped(if (result.answeredElsewhere) "Answered on another device." else "The call has ended.")
            CallController.StartResult.Cancelled -> StartPhase.Stopped("")
            is CallController.StartResult.Failed -> StartPhase.Stopped(result.message)
        }
    }

    when (val p = phase) {
        StartPhase.Running -> {
            val call by calls.current.collectAsStateWithLifecycle()
            val starting by calls.starting.collectAsStateWithLifecycle()
            val running = call
            when {
                running != null && running.token == token -> InCallScreen(running, onDone, notice)
                starting -> Connecting(onCancel = { calls.cancelStart(); onDone() })
                // The call is over (ended while this screen was away): nothing left to show.
                else -> LaunchedEffect(Unit) { onDone() }
            }
        }
        StartPhase.Consent -> AlertDialog(
            onDismissRequest = { cancel() },
            title = { Text("This call may be recorded") },
            text = { Text("The server asks everyone to agree to a possible recording before joining a call.") },
            confirmButton = { TextButton(onClick = { begin(consent = true) }) { Text("Join") } },
            dismissButton = { TextButton(onClick = { cancel() }) { Text("Cancel") } },
        )
        is StartPhase.Stopped -> {
            if (p.message.isEmpty()) {
                LaunchedEffect(p) { onDone() }
                return
            }
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(p.message, style = MaterialTheme.typography.titleMedium, color = TalqTheme.colors.textPrimary, textAlign = TextAlign.Center)
                if (p.settings) {
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        onDone()
                    }) { Text("Open settings") }
                }
            }
            LaunchedEffect(p) {
                delay(if (p.settings) SETTINGS_SHOWN_MS else ENDED_SHOWN_MS)
                // "In another call": back to that call rather than closing its screen.
                if (p.showRunning && (calls.current.value != null || calls.starting.value)) onRequest(CallActivity.Request.Show) else onDone()
            }
        }
        StartPhase.Asking, StartPhase.Starting -> Connecting(onCancel = { cancel() })
    }
}

/** "Connecting…" with a button to give up; Back gives up too. */
@Composable
private fun Connecting(onCancel: () -> Unit) {
    BackHandler { onCancel() }
    Column(
        Modifier.fillMaxSize().navigationBarsPadding().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Text("Connecting…", style = MaterialTheme.typography.titleMedium, color = TalqTheme.colors.textPrimary)
        Spacer(Modifier.weight(1f))
        RoundButton(Icons.Filled.CallEnd, "Cancel", TalqTheme.colors.danger, size = 64, onClick = onCancel)
        Spacer(Modifier.height(32.dp))
    }
}

// ---------------------------------------------------------------- the call

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InCallScreen(call: CallController.ActiveCall, onDone: () -> Unit, startNotice: String? = null) {
    val context = LocalContext.current
    val container = context.appContainer
    val calls = container.calls
    val colors = TalqTheme.colors
    val state by call.session.state.collectAsStateWithLifecycle()
    val participants by call.session.participants.collectAsStateWithLifecycle()
    val sessionNotice by call.session.notice.collectAsStateWithLifecycle()
    val mic by call.session.microphone.collectAsStateWithLifecycle()
    val camera by call.session.camera.collectAsStateWithLifecycle()
    val videos by call.engine.remoteVideos.collectAsStateWithLifecycle()
    val front by call.engine.frontCamera.collectAsStateWithLifecycle()
    val hears by call.engine.audioArrives.collectAsStateWithLifecycle()
    val cameraFailed by call.engine.cameraFailed.collectAsStateWithLifecycle()
    // One other person (or nobody yet): the one-to-one layout. More, or a shared screen: the stage.
    var pickedScreen by remember { mutableStateOf<String?>(null) }
    val sharer = shownScreen(participants, videos, pickedScreen)
    val peer = participants.singleOrNull()
    val remote = peer?.let { videos[SubscriberKey(it.sid, StreamKind.Camera)] }
    val receiving = peer?.camera ?: MediaConnection.New
    val audioProblem by call.engine.audioProblem.collectAsStateWithLifecycle()
    val outputs by calls.endpoints.collectAsStateWithLifecycle()
    val output by calls.endpoint.collectAsStateWithLifecycle()
    var choosingOutput by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf(startNotice) }
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            notice = null
            calls.setCamera(true)
        } else {
            notice = "The camera permission is needed for video."
        }
    }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    // Since when, in a running call, nothing is received (null while receiving or not running yet).
    val isActive = state is CallState.Active
    var receivingDownSince by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(receiving, isActive) {
        receivingDownSince = if (receiving == MediaConnection.Connected || !isActive) null else System.currentTimeMillis()
    }

    val ended = state is CallState.Ended
    val staged = !ended && (sharer != null || participants.size >= 2)
    LaunchedEffect(peer?.sid, staged) {
        val p = peer
        if (p != null && !staged) call.session.setSubstream(p.sid, 2)
    }
    val remoteShown = !ended && !staged && peer?.video == true && remote != null
    val previewShown = !ended && camera
    if (remoteShown || camera || staged) KeepScreenOn()
    BackHandler(enabled = ended) { onDone() }
    LaunchedEffect(ended) {
        if (ended) {
            delay(ENDED_SHOWN_MS)
            onDone()
        }
    }
    val peerSends = peer?.let { it.audio || it.video } == true
    val downSince = receivingDownSince
    val cantHear = !call.isGroup && state is CallState.Active && peerSends &&
        ((downSince != null && now - downSince > if (receiving == MediaConnection.New) NOTHING_OFFERED_MS else NOTHING_ARRIVES_MS) ||
            (receiving == MediaConnection.Connected && peer?.audio == true && !hears))
    val compact = landscape()

    Box(Modifier.fillMaxSize()) {
        if (remoteShown) {
            VideoView(remote, egl = call.engine, mirror = false, fill = true, overlay = false, modifier = Modifier.fillMaxSize())
        }
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val people = participants.size + 1
            // Answered, their sound or picture not here yet (about a second): said, not a running 0:00.
            val connecting = !call.isGroup && state is CallState.Active && peerSends &&
                receiving != MediaConnection.Connected && !cantHear
            val line = when {
                connecting -> if (call.incoming) "Connecting…" else "Answered · connecting…"
                else -> stateLine(state, peer?.displayName ?: call.title, now, call.isGroup) +
                    if (call.isGroup && state is CallState.Active) " · $people people" else ""
            }
            if (staged) {
                Spacer(Modifier.height(8.dp))
                Text(call.title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, maxLines = 1)
                Text(line, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                Spacer(Modifier.height(8.dp))
            } else {
                Spacer(Modifier.height(if (remoteShown || compact) 16.dp else 72.dp))
                if (!remoteShown) {
                    when {
                        peer != null && call.isGroup -> PersonPicture(call, peer, size = if (compact) 72 else 120)
                        call.isGroup -> Avatar(
                            call.account.client.roomAvatarUrl(call.token, call.account.rooms.room(call.token)?.avatarVersion.orEmpty(), !colors.isLight),
                            call.account.client.authorizationHeader,
                            call.title,
                            size = if (compact) 72.dp else 120.dp,
                        )
                        else -> Avatar(
                            call.account.client.userAvatarUrl(call.peerUserId.orEmpty(), 256),
                            call.account.client.authorizationHeader,
                            call.title,
                            size = if (compact) 72.dp else 120.dp,
                        )
                    }
                    Spacer(Modifier.height(if (compact) 8.dp else 20.dp))
                }
                val name = if (call.isGroup) call.title else peer?.displayName?.takeIf { it.isNotBlank() } ?: call.title
                Text(name, style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
                if (call.isGroup && peer != null) {
                    Text("with ${peer.displayName}", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                }
                Spacer(Modifier.height(6.dp))
                Text(line, style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary)
            }
            val lines = buildList {
                if (cantHear) add("Can't hear them" to colors.amber)
                audioProblem?.takeIf { !ended }?.let { add(it to colors.amber) }
                if (camera && cameraFailed && !ended) add("The camera could not be used" to colors.amber)
                if (!staged && peer?.audio == false && !ended) add("Their microphone is off" to colors.textSecondary)
                sessionNotice?.takeIf { !ended }?.let { add(it to colors.textSecondary) }
                notice?.let { add(it to colors.textSecondary) }
            }
            for ((text, ink) in lines) {
                Spacer(Modifier.height(4.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, color = ink, textAlign = TextAlign.Center)
            }
            if (staged) {
                Spacer(Modifier.height(8.dp))
                val s = sharer
                if (s != null) {
                    ScreenStage(call, s, participants, videos, compact, onPick = { pickedScreen = it }, modifier = Modifier.weight(1f).fillMaxWidth())
                } else {
                    PeopleGrid(call, participants, videos, Modifier.weight(1f).fillMaxWidth())
                }
                Spacer(Modifier.height(12.dp))
            } else {
                Spacer(Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                RoundButton(
                    if (mic) Icons.Filled.Mic else Icons.Filled.MicOff, if (mic) "Mute" else "Unmute",
                    fill = if (mic) colors.bgSecondary else colors.textPrimary, iconTint = if (mic) colors.textPrimary else colors.bgPrimary,
                ) { calls.setMicrophone(!mic) }
                if (hasCamera) {
                    RoundButton(
                        if (camera) Icons.Filled.Videocam else Icons.Filled.VideocamOff, if (camera) "Camera off" else "Camera on",
                        fill = if (camera) colors.textPrimary else colors.bgSecondary, iconTint = if (camera) colors.bgPrimary else colors.textPrimary,
                    ) {
                        when {
                            camera -> calls.setCamera(false)
                            CallActivity.granted(context, Manifest.permission.CAMERA) -> calls.setCamera(true)
                            else -> cameraPermission.launch(Manifest.permission.CAMERA)
                        }
                    }
                }
                if (camera) RoundButton(Icons.Filled.Cameraswitch, "Switch camera", colors.bgSecondary) { call.engine.switchCamera() }
                if (outputs.size > 1) RoundButton(outputIcon(output), "Audio output", colors.bgSecondary) { choosingOutput = true }
                RoundButton(Icons.Filled.CallEnd, if (call.isGroup) "Leave" else "Hang up", colors.danger, size = 64) { calls.hangUp() }
            }
            Spacer(Modifier.height(if (compact) 12.dp else 32.dp))
        }
        if (previewShown) {
            VideoView(
                call.engine.localVideo, egl = call.engine, mirror = front, fill = true, overlay = true,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp).width(96.dp).height(128.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        }
    }
    if (choosingOutput) {
        ModalBottomSheet(onDismissRequest = { choosingOutput = false }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                for (o in outputs) {
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            calls.selectEndpoint(o)
                            choosingOutput = false
                        }.padding(horizontal = 24.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(outputIcon(o), contentDescription = null)
                        Spacer(Modifier.width(16.dp))
                        Text(outputName(o), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        if (o.id == output?.id) Text("In use", style = MaterialTheme.typography.labelMedium, color = colors.accentText)
                    }
                }
            }
        }
    }
}

private fun stateLine(state: CallState, peerName: String, now: Long, group: Boolean): String = when (state) {
    CallState.Connecting -> "Connecting…"
    CallState.WaitingForPeer -> if (group) "Waiting for others…" else "Calling…"
    is CallState.Active -> duration(now - state.since)
    CallState.Reconnecting -> "Reconnecting…"
    is CallState.Ended -> when (state.reason) {
        CallEndReason.HungUp -> "Call ended"
        CallEndReason.PeerHungUp -> "$peerName hung up"
        CallEndReason.NoAnswer -> "No answer"
        CallEndReason.Failed -> "The call failed"
        CallEndReason.Declined -> "Declined"
        CallEndReason.NoHpb -> "This server cannot make calls"
        CallEndReason.PermissionDenied -> "Microphone permission is needed for calls"
        CallEndReason.EndedForEveryone -> "The call was ended"
        CallEndReason.Alone -> "Nobody else is in the call"
    }
}

private fun duration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

private fun outputIcon(o: AudioOutput?): ImageVector = when (o?.kind) {
    AudioOutput.Kind.Speaker -> Icons.AutoMirrored.Filled.VolumeUp
    AudioOutput.Kind.Bluetooth -> Icons.Filled.BluetoothAudio
    AudioOutput.Kind.Wired -> Icons.Filled.Headset
    else -> Icons.Filled.PhoneInTalk
}

private fun outputName(o: AudioOutput): String = when (o.kind) {
    AudioOutput.Kind.Earpiece -> "Phone"
    AudioOutput.Kind.Speaker -> "Speaker"
    AudioOutput.Kind.Wired -> o.name.ifBlank { "Headset" }
    AudioOutput.Kind.Bluetooth -> o.name.ifBlank { "Bluetooth" }
    AudioOutput.Kind.Other -> o.name.ifBlank { "Other" }
}

// ---------------------------------------------------------------- parts

@Composable
private fun landscape(): Boolean = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

@Composable
private fun CenterText(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = TalqTheme.colors.textPrimary, textAlign = TextAlign.Center)
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    label: String,
    fill: Color,
    size: Int = 56,
    iconTint: Color = TalqTheme.colors.textPrimary,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(fill).clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = iconTint, modifier = Modifier.size((size * 0.45f).dp))
    }
}

/** Keeps the screen on while this is shown (ringing, video). */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * A libwebrtc video track in a SurfaceView. [overlay] puts it above another video view (the
 * own preview over the other person's picture). The engine disposes a track only a moment
 * after it stops offering it, so this view lets go of it first.
 */
@Composable
private fun VideoView(track: VideoTrack?, egl: WebRtcEngine, mirror: Boolean, fill: Boolean, overlay: Boolean, modifier: Modifier) {
    var view by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(egl.eglBase.eglBaseContext, null)
                setEnableHardwareScaler(true)
                setScalingType(if (fill) RendererCommon.ScalingType.SCALE_ASPECT_FILL else RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                if (overlay) setZOrderMediaOverlay(true)
                view = this
            }
        },
        update = { it.setMirror(mirror) },
        onRelease = { it.release() },
        modifier = modifier,
    )
    val v = view
    DisposableEffect(track, v) {
        if (track != null && v != null) runCatching { track.addSink(v) }
        onDispose {
            if (track != null && v != null) runCatching { track.removeSink(v) }
        }
    }
}

private const val ENDED_SHOWN_MS = 2_000L
private const val SETTINGS_SHOWN_MS = 6_000L
private const val NOTHING_ARRIVES_MS = 5_000L

/** Answered, their media not offered yet: a desktop callee needs 3-4 s to publish (measured 2026-11-28). */
private const val NOTHING_OFFERED_MS = 12_000L

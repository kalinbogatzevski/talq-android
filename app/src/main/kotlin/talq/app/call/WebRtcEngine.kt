package talq.app.call

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.JavaI420Buffer
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import talq.core.call.IceServer
import talq.core.call.LocalCandidate
import talq.core.call.MediaConnection
import talq.core.call.MediaEngine
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [MediaEngine] on libwebrtc: the media of one call. Everything goes through the call backend's
 * media server, so there are two kinds of connection:
 *
 * - the PUBLISHER: what we send. Unified plan, max-bundle, rtcp-mux, no ICE over TCP (the media
 *   server has it off; TURN over TCP/TLS still works). Send-only audio and ALWAYS a send-only
 *   video section, whether the camera is on or not: other TalQ clients only take a stream that
 *   has video. A data channel "status" carries audioOn/audioOff/videoOn/videoOff. Video codecs
 *   are offered as H264 Constrained Baseline first, then VP8 (desktop decodes only those two).
 *   The SDP is never edited.
 * - one SUBSCRIBER: what we receive from the other person, built from the media server's offer.
 *
 * The camera (front first, 1280x720 at 30 fps where it can, else 640x480) runs only while it
 * is switched on; switching it off stops the device. While it is off, black frames go out for
 * 5 s (as TalQ desktop does) and then nothing. Echo cancellation and noise suppression are the
 * phone's own where it has them (libwebrtc then switches its own off), else libwebrtc's.
 *
 * Threads: libwebrtc runs its peer connections on its own signaling thread, and several of its
 * methods (creating a connection, adding a candidate) wait for that thread. Its callbacks run
 * on that thread too. So no libwebrtc method is ever called while [lock] is held, and the
 * callbacks never take [lock]: the fields they read are volatile. Nothing is disposed inside a
 * callback. Camera work, black frames, statistics and disposal run on one thread of our own.
 * Nothing here logs an SDP, a candidate or a TURN credential.
 *
 * [eglBase] is shared with the call screen's renderers.
 */
class WebRtcEngine(context: Context, val eglBase: EglBase, video: Boolean) : MediaEngine {
    private val appContext = context.applicationContext
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "talq-media") }
    private val lock = Any()

    private val adm: JavaAudioDeviceModule
    private val factory: PeerConnectionFactory
    private val audioSource: AudioSource
    private val audioTrack: AudioTrack
    private val videoSource: VideoSource

    /** Our camera picture (also while it is off: then it carries nothing). For the preview. */
    val localVideo: VideoTrack

    private val _remoteVideo = MutableStateFlow<VideoTrack?>(null)

    /** The other person's video track while a subscriber has one. */
    val remoteVideo: StateFlow<VideoTrack?> = _remoteVideo.asStateFlow()

    private val _audioArrives = MutableStateFlow(true)

    /** False when the subscriber is connected but no audio packet came in the last 5 s. */
    val audioArrives: StateFlow<Boolean> = _audioArrives.asStateFlow()

    private val _frontCamera = MutableStateFlow(true)

    /** The camera in use faces the user (the preview is mirrored then). */
    val frontCamera: StateFlow<Boolean> = _frontCamera.asStateFlow()

    private val _cameraFailed = MutableStateFlow(false)

    /** The camera is switched on but could not be opened, or was taken away. */
    val cameraFailed: StateFlow<Boolean> = _cameraFailed.asStateFlow()

    private val _audioProblem = MutableStateFlow<String?>(null)

    /** The phone's microphone or speaker failed (what to tell the user), or null. */
    val audioProblem: StateFlow<String?> = _audioProblem.asStateFlow()

    private val candidates = Channel<LocalCandidate>(Channel.UNLIMITED)
    override val localCandidates: Flow<LocalCandidate> = candidates.receiveAsFlow()

    private val _publisher = MutableStateFlow(MediaConnection.New)
    override val publisher: StateFlow<MediaConnection> = _publisher.asStateFlow()

    private val _subscriber = MutableStateFlow(MediaConnection.New)
    override val subscriber: StateFlow<MediaConnection> = _subscriber.asStateFlow()

    // Written under lock; read anywhere (volatile), in particular by the callbacks.
    @Volatile private var closed = false
    @Volatile private var pub: PeerConnection? = null
    @Volatile private var pubChannel: DataChannel? = null
    @Volatile private var sub: PeerConnection? = null
    @Volatile private var subPeer: String? = null
    @Volatile private var remoteAudio: AudioTrack? = null
    @Volatile private var subChannel: DataChannel? = null
    @Volatile private var playRemote = true

    // Under lock.
    private var pubRemoteSet = false
    private val pubPending = ArrayList<IceCandidate>()
    private var subRemoteSet = false
    private val subPending = ArrayList<IceCandidate>()

    // Worker thread only.
    private var capturer: CameraVideoCapturer? = null
    private var captureHelper: SurfaceTextureHelper? = null
    private var capturing = false
    private var captureFormat = Triple(640, 480, 30)
    private var cameraWanted = video
    private var cameraApply: ScheduledFuture<*>? = null
    private var black: ScheduledFuture<*>? = null
    private var blackBuffer: JavaI420Buffer? = null
    private var stats: ScheduledFuture<*>? = null
    private var lastAudioPackets = -1L
    private var lastAudioGrowth = 0L

    init {
        initialize(appContext)
        adm = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
            .setUseHardwareNoiseSuppressor(JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(message: String?) = microphoneFailed("init")

                override fun onWebRtcAudioRecordStartError(code: JavaAudioDeviceModule.AudioRecordStartErrorCode?, message: String?) =
                    microphoneFailed("start $code")

                override fun onWebRtcAudioRecordError(message: String?) = microphoneFailed("record")
            })
            .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(message: String?) = playbackFailed("init")

                override fun onWebRtcAudioTrackStartError(code: JavaAudioDeviceModule.AudioTrackStartErrorCode?, message: String?) =
                    playbackFailed("start $code")

                override fun onWebRtcAudioTrackError(message: String?) = playbackFailed("play")
            })
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, false))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        audioSource = factory.createAudioSource(MediaConstraints())
        audioTrack = factory.createAudioTrack("talq-audio", audioSource)
        videoSource = factory.createVideoSource(false)
        localVideo = factory.createVideoTrack("talq-video", videoSource)
        localVideo.setEnabled(video)
        if (video) onWorker { applyCamera() }
    }

    private fun microphoneFailed(what: String) {
        Log.w(TAG, "microphone: $what error")
        _audioProblem.value = "The microphone could not be used"
    }

    private fun playbackFailed(what: String) {
        Log.w(TAG, "playback: $what error")
        _audioProblem.value = "The sound could not be played"
    }

    // ---------------------------------------------------------------- publisher

    override suspend fun createPublisherOffer(iceServers: List<IceServer>, video: Boolean): String {
        check(!closed) { "media closed" }
        // Built outside the lock: creating a connection waits for libwebrtc's signaling thread.
        val observer = Observer(peer = null)
        val pc = factory.createPeerConnection(rtcConfig(iceServers), observer)
            ?: throw IllegalStateException("no peer connection")
        observer.pc = pc
        val old: PeerConnection?
        val oldChannel: DataChannel?
        synchronized(lock) {
            if (closed) {
                onWorker { runCatching { pc.dispose() } }
                throw IllegalStateException("media closed")
            }
            old = pub
            oldChannel = pubChannel
            _publisher.value = MediaConnection.New
            pub = pc
            pubChannel = null
            pubRemoteSet = false
            pubPending.clear()
        }
        retire(old, oldChannel, delayMs = 0)
        val streams = listOf(STREAM_ID)
        pc.addTransceiver(audioTrack, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, streams))
        val videoTransceiver = pc.addTransceiver(
            localVideo,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, streams),
        )
        preferVideoCodecs(videoTransceiver)
        val channel = pc.createDataChannel("status", DataChannel.Init())
        synchronized(lock) { if (pub === pc) pubChannel = channel }
        val offer = pc.awaitCreate(offer = true)
        pc.awaitSetLocal(offer)
        return offer.description
    }

    override suspend fun setPublisherAnswer(sdp: String) {
        val pc = pub ?: return
        pc.awaitSetRemote(SessionDescription(SessionDescription.Type.ANSWER, sdp))
        val pending = synchronized(lock) {
            if (pub !== pc) return
            pubRemoteSet = true
            pubPending.toList().also { pubPending.clear() }
        }
        pending.forEach { pc.addIceCandidate(it) }
    }

    // ---------------------------------------------------------------- subscriber

    override suspend fun answerSubscriber(peerSessionId: String, offerSdp: String, iceServers: List<IceServer>): String {
        check(!closed) { "media closed" }
        val observer = Observer(peer = peerSessionId)
        val pc = factory.createPeerConnection(rtcConfig(iceServers), observer)
            ?: throw IllegalStateException("no peer connection")
        observer.pc = pc
        val old: PeerConnection?
        val oldSubChannel: DataChannel?
        synchronized(lock) {
            if (closed) {
                onWorker { runCatching { pc.dispose() } }
                throw IllegalStateException("media closed")
            }
            old = sub
            oldSubChannel = subChannel
            subChannel = null
            _remoteVideo.value = null
            remoteAudio = null
            _subscriber.value = MediaConnection.New
            sub = pc
            subPeer = peerSessionId
            subRemoteSet = false
            subPending.clear()
        }
        // Later than a publisher: the call screen first lets go of the old video track.
        retire(old, oldSubChannel, delayMs = DISPOSE_DELAY_MS)
        pc.awaitSetRemote(SessionDescription(SessionDescription.Type.OFFER, offerSdp))
        val pending = synchronized(lock) {
            if (sub !== pc) emptyList() else {
                subRemoteSet = true
                subPending.toList().also { subPending.clear() }
            }
        }
        pending.forEach { pc.addIceCandidate(it) }
        val answer = pc.awaitCreate(offer = false)
        pc.awaitSetLocal(answer)
        return answer.description
    }

    override fun addRemoteCandidate(peerSessionId: String?, candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        val ice = IceCandidate(sdpMid ?: "", sdpMLineIndex, candidate)
        // Decided under the lock, added after it (adding waits for libwebrtc's thread).
        val target: PeerConnection? = synchronized(lock) {
            if (peerSessionId == null) {
                val pc = pub
                when {
                    pc == null -> null
                    pubRemoteSet -> pc
                    else -> {
                        pubPending += ice
                        null
                    }
                }
            } else {
                val pc = sub
                when {
                    peerSessionId != subPeer || pc == null -> null
                    subRemoteSet -> pc
                    else -> {
                        if (subPending.size < MAX_PENDING) subPending += ice
                        null
                    }
                }
            }
        }
        target?.addIceCandidate(ice)
    }

    // ---------------------------------------------------------------- local media

    override fun setLocalAudio(enabled: Boolean) {
        audioTrack.setEnabled(enabled)
    }

    /** Plays (true) or silences (false) what the other person sends, e.g. while on hold. */
    fun setRemoteAudio(enabled: Boolean) {
        playRemote = enabled
        runCatching { remoteAudio?.setEnabled(enabled) }
    }

    /** Coalesced: only the last state 250 ms after the last switch reaches the camera. */
    override fun setLocalVideo(enabled: Boolean) {
        onWorker {
            cameraWanted = enabled
            cameraApply?.cancel(false)
            cameraApply = worker.schedule({ applyCamera() }, CAMERA_COALESCE_MS, TimeUnit.MILLISECONDS)
        }
    }

    override fun sendStatus(type: String) {
        val channel = pubChannel ?: return
        if (channel.state() != DataChannel.State.OPEN) return
        val bytes = "{\"type\":\"$type\"}".encodeToByteArray()
        channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
    }

    /** Front to back camera and back (the opposite-facing one, not just the next one listed). */
    fun switchCamera() {
        onWorker {
            val c = capturer ?: return@onWorker
            val handler = object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                    _frontCamera.value = isFrontCamera
                }

                override fun onCameraSwitchError(errorDescription: String?) {
                    Log.w(TAG, "switching the camera failed")
                }
            }
            val enumerator = Camera2Enumerator(appContext)
            val other = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) != _frontCamera.value }
            if (other != null) c.switchCamera(handler, other) else c.switchCamera(handler)
        }
    }

    /** Worker thread. Starts or stops the camera to match [cameraWanted]. */
    private fun applyCamera() {
        if (worker.isShutdown) return
        if (cameraWanted) {
            stopBlack()
            localVideo.setEnabled(true)
            startCamera()
        } else {
            _cameraFailed.value = false
            localVideo.setEnabled(false)
            stopCamera()
            startBlack()
        }
    }

    private fun startCamera() {
        if (capturing) return
        try {
            if (capturer == null) {
                val enumerator = Camera2Enumerator(appContext)
                val names = enumerator.deviceNames
                val name = names.firstOrNull { enumerator.isFrontFacing(it) } ?: names.firstOrNull() ?: run {
                    Log.w(TAG, "no camera")
                    _cameraFailed.value = true
                    return
                }
                _frontCamera.value = enumerator.isFrontFacing(name)
                captureFormat = pickFormat(enumerator.getSupportedFormats(name))
                val helper = SurfaceTextureHelper.create("talq-camera", eglBase.eglBaseContext) ?: run {
                    Log.w(TAG, "no camera surface")
                    _cameraFailed.value = true
                    return
                }
                val c = enumerator.createCapturer(name, CameraEvents())
                c.initialize(helper, appContext, videoSource.capturerObserver)
                capturer = c
                captureHelper = helper
            }
            val (w, h, fps) = captureFormat
            capturer?.startCapture(w, h, fps)
            capturing = true
            _cameraFailed.value = false
        } catch (e: Exception) {
            Log.w(TAG, "the camera did not start (${e::class.simpleName})")
            _cameraFailed.value = true
        }
    }

    private fun stopCamera() {
        if (!capturing) return
        capturing = false
        try {
            capturer?.stopCapture()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.w(TAG, "the camera did not stop (${e::class.simpleName})")
        }
    }

    /** 1280x720 at 30 fps where the camera has it, else 640x480 at 30, else its first format. */
    private fun pickFormat(formats: List<org.webrtc.CameraEnumerationAndroid.CaptureFormat>?): Triple<Int, Int, Int> {
        if (formats.isNullOrEmpty()) return Triple(640, 480, 30)
        for ((w, h) in listOf(1280 to 720, 640 to 480)) {
            if (formats.any { it.width == w && it.height == h && it.framerate.max >= 30_000 }) return Triple(w, h, 30)
        }
        val f = formats.first()
        return Triple(f.width, f.height, (f.framerate.max / 1000).coerceIn(15, 30))
    }

    /** Worker thread. Black 640x480 frames at 10 fps for 5 s, then nothing. */
    private fun startBlack() {
        stopBlack()
        val buffer = blackBuffer ?: JavaI420Buffer.allocate(640, 480).also { b ->
            fill(b.dataY, 16)
            fill(b.dataU, 128)
            fill(b.dataV, 128)
            blackBuffer = b
        }
        val until = SystemClock.elapsedRealtime() + BLACK_GRACE_MS
        val observer = videoSource.capturerObserver
        black = worker.scheduleWithFixedDelay({
            if (SystemClock.elapsedRealtime() > until) {
                black?.cancel(false)
                return@scheduleWithFixedDelay
            }
            buffer.retain()
            val frame = VideoFrame(buffer, 0, System.nanoTime())
            observer.onFrameCaptured(frame)
            frame.release()
        }, 0, 100, TimeUnit.MILLISECONDS)
    }

    private fun stopBlack() {
        black?.cancel(false)
        black = null
    }

    private fun fill(b: ByteBuffer, value: Int) {
        val v = value.toByte()
        for (i in 0 until b.capacity()) b.put(i, v)
    }

    // ---------------------------------------------------------------- statistics

    /** Worker thread. Every 2 s while a subscriber is connected: did audio packets come in? */
    private fun startStats() {
        stats?.cancel(false)
        lastAudioPackets = -1L
        lastAudioGrowth = SystemClock.elapsedRealtime()
        _audioArrives.value = true
        stats = worker.scheduleWithFixedDelay({
            val pc = sub ?: return@scheduleWithFixedDelay
            pc.getStats { report ->
                var packets = 0L
                for (s in report.statsMap.values) {
                    if (s.type == "inbound-rtp" && s.members["kind"] == "audio") {
                        packets += (s.members["packetsReceived"] as? Number)?.toLong() ?: 0L
                    }
                }
                onWorker {
                    val now = SystemClock.elapsedRealtime()
                    if (packets > lastAudioPackets) lastAudioGrowth = now
                    lastAudioPackets = packets
                    _audioArrives.value = now - lastAudioGrowth < AUDIO_SILENCE_MS
                }
            }
        }, STATS_MS, STATS_MS, TimeUnit.MILLISECONDS)
    }

    private fun stopStats() {
        stats?.cancel(false)
        stats = null
        _audioArrives.value = true
    }

    // ---------------------------------------------------------------- rebuild and close

    override fun rebuild() {
        val oldPub: PeerConnection?
        val oldChannel: DataChannel?
        val oldSub: PeerConnection?
        val oldSubChannel: DataChannel?
        synchronized(lock) {
            _remoteVideo.value = null
            remoteAudio = null
            oldPub = pub
            oldChannel = pubChannel
            oldSub = sub
            oldSubChannel = subChannel
            pub = null
            sub = null
            subPeer = null
            pubChannel = null
            subChannel = null
            pubPending.clear()
            subPending.clear()
            _publisher.value = MediaConnection.New
            _subscriber.value = MediaConnection.New
        }
        retire(oldPub, oldChannel, delayMs = 0)
        retire(oldSub, oldSubChannel, delayMs = DISPOSE_DELAY_MS)
    }

    override fun close() {
        val oldPub: PeerConnection?
        val oldChannel: DataChannel?
        val oldSub: PeerConnection?
        val oldSubChannel: DataChannel?
        synchronized(lock) {
            if (closed) return
            closed = true
            _remoteVideo.value = null
            oldPub = pub
            oldChannel = pubChannel
            oldSub = sub
            oldSubChannel = subChannel
            pub = null
            sub = null
            pubChannel = null
            subChannel = null
        }
        candidates.close()
        // Silent and dark at once; the objects themselves go a moment later.
        runCatching { audioTrack.setEnabled(false) }
        setRemoteAudio(false)
        onWorker {
            cameraWanted = false
            stopBlack()
            stopCamera()
        }
        val done = try {
            // A moment later: the call screen first lets go of the video tracks it shows.
            worker.schedule({
                cameraApply?.cancel(false)
                stopStats()
                stopBlack()
                runCatching { oldChannel?.dispose() }
                runCatching { oldSubChannel?.dispose() }
                runCatching { oldPub?.dispose() }
                runCatching { oldSub?.dispose() }
                remoteAudio = null
                stopCamera()
                // The camera stops on its own thread: wait for that before its thread goes.
                captureHelper?.let { h ->
                    val stopped = CountDownLatch(1)
                    if (h.handler.post { stopped.countDown() }) stopped.await(CAMERA_STOP_WAIT_MS, TimeUnit.MILLISECONDS)
                }
                runCatching { capturer?.dispose() }
                runCatching { captureHelper?.dispose() }
                runCatching { audioTrack.dispose() }
                runCatching { localVideo.dispose() }
                runCatching { audioSource.dispose() }
                runCatching { videoSource.dispose() }
                runCatching { blackBuffer?.release() }
                runCatching { factory.dispose() }
                runCatching { adm.release() }
            }, DISPOSE_DELAY_MS, TimeUnit.MILLISECONDS)
        } catch (e: RejectedExecutionException) {
            null
        }
        try {
            done?.get(CLOSE_WAIT_S, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "closing the media took too long (${e::class.simpleName})")
        }
        worker.shutdown()
        _publisher.value = MediaConnection.New
        _subscriber.value = MediaConnection.New
    }

    /** Runs [block] on the worker thread; after [close] (a late tap, a late callback) it is dropped. */
    private fun onWorker(block: () -> Unit) {
        try {
            worker.execute(block)
        } catch (e: RejectedExecutionException) {
            // Closed already.
        }
    }

    /** Disposes [pc] (and its data channel) on the worker thread, never inside its own callbacks. */
    private fun retire(pc: PeerConnection?, channel: DataChannel?, delayMs: Long) {
        if (pc == null) return
        try {
            worker.schedule({
                if (pc === sub) return@schedule
                runCatching { channel?.dispose() }
                runCatching { pc.dispose() }
            }, delayMs, TimeUnit.MILLISECONDS)
        } catch (e: RejectedExecutionException) {
            // Closed already: close() disposes what was current.
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun rtcConfig(iceServers: List<IceServer>): PeerConnection.RTCConfiguration {
        val servers = iceServers.map { s ->
            PeerConnection.IceServer.builder(s.urls).apply {
                if (s.username.isNotEmpty()) setUsername(s.username)
                if (s.credential.isNotEmpty()) setPassword(s.credential)
            }.createIceServer()
        }
        return PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            // ICE over TCP is off on the media server; TURN over TCP/TLS is not affected.
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            // Gathering ends once, so endOfCandidates can be sent.
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            keyType = PeerConnection.KeyType.ECDSA
        }
    }

    /** H264 Constrained Baseline (42e01f first), then VP8; other codecs are not offered. */
    private fun preferVideoCodecs(transceiver: RtpTransceiver) {
        val codecs = factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)?.codecs ?: return
        val h264 = codecs.filter { c ->
            c.name.equals("H264", ignoreCase = true) &&
                c.parameters["packetization-mode"] == "1" &&
                c.parameters["profile-level-id"]?.lowercase()?.startsWith("42e0") == true
        }.sortedByDescending { it.parameters["profile-level-id"].equals("42e01f", ignoreCase = true) }
        val vp8 = codecs.filter { it.name.equals("VP8", ignoreCase = true) }
        if (h264.isEmpty() && vp8.isEmpty()) return
        val helpers = codecs.filter { it.name.uppercase() in setOf("RTX", "RED", "ULPFEC") }
        val result = transceiver.setCodecPreferences(h264 + vp8 + helpers)
        if (result.isError) Log.w(TAG, "the video codec order was refused")
        Log.i(TAG, "video codecs offered: h264=${h264.size} vp8=${vp8.size}")
    }

    /** Callbacks on libwebrtc's signaling thread: they read volatile fields only, never [lock]. */
    private inner class Observer(private val peer: String?) : PeerConnection.Observer {
        @Volatile var pc: PeerConnection? = null

        private fun current(): Boolean {
            val p = pc ?: return false
            return if (peer == null) pub === p else sub === p
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            if (!current()) return
            candidates.trySend(LocalCandidate.Candidate(peer, candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex))
        }

        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
            if (newState == PeerConnection.IceGatheringState.COMPLETE && current()) {
                candidates.trySend(LocalCandidate.End(peer))
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            if (newState == PeerConnection.PeerConnectionState.CLOSED || !current()) return
            val c = when (newState) {
                PeerConnection.PeerConnectionState.NEW -> MediaConnection.New
                PeerConnection.PeerConnectionState.CONNECTING -> MediaConnection.Connecting
                PeerConnection.PeerConnectionState.CONNECTED -> MediaConnection.Connected
                // A short loss of the path; ICE may come back by itself, FAILED follows if not.
                PeerConnection.PeerConnectionState.DISCONNECTED -> MediaConnection.Connecting
                else -> MediaConnection.Failed
            }
            Log.i(TAG, "${if (peer == null) "sending" else "receiving"}: $newState")
            if (peer == null) {
                _publisher.value = c
                // The video section carries something at once, even with the camera off.
                if (c == MediaConnection.Connected) onWorker { if (!cameraWanted) startBlack() }
            } else {
                _subscriber.value = c
                if (c == MediaConnection.Connected) onWorker { startStats() }
            }
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            if (peer == null || !current()) return
            when (val track = transceiver.receiver.track()) {
                is VideoTrack -> _remoteVideo.value = track
                is AudioTrack -> {
                    remoteAudio = track
                    runCatching { track.setEnabled(playRemote) }
                }
                else -> Unit
            }
        }

        override fun onSignalingChange(newState: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        // The media server may open one towards us; it is kept only to be disposed with its connection.
        override fun onDataChannel(dataChannel: DataChannel) {
            if (peer != null && current()) subChannel = dataChannel
        }
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {}
    }

    /** The camera stopped by itself (it failed, or another app took it): say so, send black. */
    private inner class CameraEvents : CameraVideoCapturer.CameraEventsHandler {
        override fun onCameraError(errorDescription: String?) {
            Log.w(TAG, "camera error")
            lost()
        }

        override fun onCameraDisconnected() {
            Log.w(TAG, "camera disconnected")
            lost()
        }

        override fun onCameraFreezed(errorDescription: String?) {
            Log.w(TAG, "camera froze")
        }

        override fun onCameraOpening(cameraName: String?) {}
        override fun onFirstFrameAvailable() {}
        override fun onCameraClosed() {}

        private fun lost() {
            onWorker {
                capturing = false
                if (cameraWanted) {
                    _cameraFailed.value = true
                    startBlack()
                }
            }
        }
    }

    companion object {
        private const val TAG = "TalqCall"
        private const val STREAM_ID = "talq"
        private const val CAMERA_COALESCE_MS = 250L
        private const val BLACK_GRACE_MS = 5_000L
        private const val STATS_MS = 2_000L
        private const val AUDIO_SILENCE_MS = 5_000L
        private const val MAX_PENDING = 64
        private const val CLOSE_WAIT_S = 6L
        private const val DISPOSE_DELAY_MS = 500L
        private const val CAMERA_STOP_WAIT_MS = 2_000L

        @Volatile private var initialized = false

        /** libwebrtc's process-wide set-up; once, before the first factory. */
        fun initialize(context: Context) {
            if (initialized) return
            synchronized(this) {
                if (initialized) return
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions(),
                )
                initialized = true
            }
        }

        @Volatile private var sharedEgl: EglBase? = null

        /** One EGL context for the whole process: the engine's codecs and the call screen share it. */
        fun egl(): EglBase = sharedEgl ?: synchronized(this) { sharedEgl ?: EglBase.create().also { sharedEgl = it } }
    }
}

internal suspend fun PeerConnection.awaitCreate(offer: Boolean): SessionDescription =
    suspendCancellableCoroutine { cont ->
        val observer = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                if (cont.isActive) cont.resume(sdp)
            }

            override fun onCreateFailure(error: String?) {
                if (cont.isActive) cont.resumeWithException(IllegalStateException("creating the ${if (offer) "offer" else "answer"} failed"))
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }
        if (offer) createOffer(observer, MediaConstraints()) else createAnswer(observer, MediaConstraints())
    }

internal suspend fun PeerConnection.awaitSetLocal(sdp: SessionDescription) = awaitSet { setLocalDescription(it, sdp) }

private suspend fun PeerConnection.awaitSetRemote(sdp: SessionDescription) = awaitSet { setRemoteDescription(it, sdp) }

private suspend fun awaitSet(call: (SdpObserver) -> Unit) = suspendCancellableCoroutine { cont ->
    call(object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetSuccess() {
            if (cont.isActive) cont.resume(Unit)
        }

        override fun onSetFailure(error: String?) {
            if (cont.isActive) cont.resumeWithException(IllegalStateException("setting a description failed"))
        }
    })
}

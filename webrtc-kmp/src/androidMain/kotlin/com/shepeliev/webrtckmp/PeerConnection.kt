package com.shepeliev.webrtckmp

import com.shepeliev.webrtckmp.PeerConnectionEvent.ConnectionStateChange
import com.shepeliev.webrtckmp.PeerConnectionEvent.IceConnectionStateChange
import com.shepeliev.webrtckmp.PeerConnectionEvent.IceGatheringStateChange
import com.shepeliev.webrtckmp.PeerConnectionEvent.NegotiationNeeded
import com.shepeliev.webrtckmp.PeerConnectionEvent.NewDataChannel
import com.shepeliev.webrtckmp.PeerConnectionEvent.NewIceCandidate
import com.shepeliev.webrtckmp.PeerConnectionEvent.RemoveTrack
import com.shepeliev.webrtckmp.PeerConnectionEvent.RemovedIceCandidates
import com.shepeliev.webrtckmp.PeerConnectionEvent.SignalingStateChange
import com.shepeliev.webrtckmp.PeerConnectionEvent.StandardizedIceConnectionChange
import com.shepeliev.webrtckmp.PeerConnectionEvent.Track
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.MediaConstraints
import org.webrtc.SdpObserver
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import org.webrtc.AudioTrack as AndroidAudioTrack
import org.webrtc.DataChannel as AndroidDataChannel
import org.webrtc.IceCandidate as AndroidIceCandidate
import org.webrtc.MediaStream as AndroidMediaStream
import org.webrtc.MediaStreamTrack as AndroidMediaStreamTrack
import org.webrtc.PeerConnection as AndroidPeerConnection
import org.webrtc.RtpReceiver as AndroidRtpReceiver
import org.webrtc.RtpTransceiver as AndroidRtpTransceiver
import org.webrtc.SessionDescription as AndroidSessionDescription
import org.webrtc.VideoTrack as AndroidVideoTrack

actual class PeerConnection actual constructor(rtcConfiguration: RtcConfiguration) {

    val android: AndroidPeerConnection = peerConnectionFactory.createPeerConnection(
        rtcConfiguration.android,
        AndroidPeerConnectionObserver()
    ) ?: error("Creating PeerConnection failed")

    actual val localDescription: SessionDescription?
        get() = android.localDescription?.asCommon()

    actual val remoteDescription: SessionDescription?
        get() = android.remoteDescription?.asCommon()

    actual val signalingState: SignalingState
        get() = android.signalingState().asCommon()

    actual val iceConnectionState: IceConnectionState
        get() = android.iceConnectionState().asCommon()

    actual val connectionState: PeerConnectionState
        get() = android.connectionState().asCommon()

    actual val iceGatheringState: IceGatheringState
        get() = android.iceGatheringState().asCommon()

    private val peerConnectionObserverProxy = PeerConnectionObserverProxy()

    internal actual val peerConnectionEvent: Flow<PeerConnectionEvent> = callbackFlow {
        val observer = object : PeerConnectionObserver {
            override fun onSignalingStateChange(state: SignalingState) {
                trySendBlocking(SignalingStateChange(state))
            }

            override fun onIceConnectionStateChange(state: IceConnectionState) {
                trySendBlocking(IceConnectionStateChange(state))
            }

            override fun onStandardizedIceConnectionChange(state: IceConnectionState) {
                trySendBlocking(StandardizedIceConnectionChange(state))
            }

            override fun onConnectionStateChange(state: PeerConnectionState) {
                trySendBlocking(ConnectionStateChange(state))
            }

            override fun onIceGatheringStateChange(state: IceGatheringState) {
                trySendBlocking(IceGatheringStateChange(state))
            }

            override fun onIceCandidate(candidate: IceCandidate) {
                trySendBlocking(NewIceCandidate(candidate))
            }

            override fun onRemovedIceCandidates(candidates: List<IceCandidate>) {
                trySendBlocking(RemovedIceCandidates(candidates))
            }

            override fun onDataChannel(dataChannel: DataChannel) {
                trySendBlocking(NewDataChannel(dataChannel))
            }

            override fun onRemoveTrack(rtpReceiver: RtpReceiver) {
                trySendBlocking(RemoveTrack(rtpReceiver))
            }

            override fun onNegotiationNeeded() {
                trySendBlocking(NegotiationNeeded)
            }

            override fun onTrack(trackEvent: TrackEvent) {
                trySendBlocking(Track(trackEvent))
            }
        }

        peerConnectionObserverProxy.addObserver(observer)

        awaitClose { peerConnectionObserverProxy.removeObserver(observer) }
    }

    actual fun createDataChannel(
        label: String,
        id: Int,
        ordered: Boolean,
        maxRetransmitTimeMs: Int,
        maxRetransmits: Int,
        protocol: String,
        negotiated: Boolean
    ): DataChannel? {
        val init = AndroidDataChannel.Init().also {
            it.id = id
            it.ordered = ordered
            it.maxRetransmitTimeMs = maxRetransmitTimeMs
            it.maxRetransmits = maxRetransmits
            it.protocol = protocol
            it.negotiated = negotiated
        }
        return android.createDataChannel(label, init)?.let { DataChannel(it) }
    }

    actual suspend fun createOffer(options: OfferAnswerOptions): SessionDescription {
        return suspendCoroutine { cont ->
            android.createOffer(createSdpObserver(cont), options.toMediaConstraints())
        }
    }

    actual suspend fun createAnswer(options: OfferAnswerOptions): SessionDescription {
        return suspendCoroutine { cont ->
            android.createAnswer(createSdpObserver(cont), options.toMediaConstraints())
        }
    }

    private fun OfferAnswerOptions.toMediaConstraints(): MediaConstraints {
        return MediaConstraints().apply {
            iceRestart?.let { mandatory += MediaConstraints.KeyValuePair("IceRestart", "$it") }
            offerToReceiveAudio?.let {
                mandatory += MediaConstraints.KeyValuePair("OfferToReceiveAudio", "$it")
            }
            offerToReceiveVideo?.let {
                mandatory += MediaConstraints.KeyValuePair("OfferToReceiveVideo", "$it")
            }
            voiceActivityDetection?.let {
                mandatory += MediaConstraints.KeyValuePair("VoiceActivityDetection", "$it")
            }
        }
    }

    private fun createSdpObserver(continuation: Continuation<SessionDescription>): SdpObserver {
        return object : SdpObserver {
            override fun onCreateSuccess(description: AndroidSessionDescription) {
                continuation.resume(description.asCommon())
            }

            override fun onSetSuccess() {
                // not applicable for creating SDP
            }

            override fun onCreateFailure(error: String?) {
                continuation.resumeWithException(RuntimeException("Creating SDP failed: $error"))
            }

            override fun onSetFailure(error: String?) {
                // not applicable for creating SDP
            }
        }
    }

    actual suspend fun setLocalDescription(description: SessionDescription) {
        return suspendCoroutine {
            android.setLocalDescription(setSdpObserver(it), description.asAndroid())
        }
    }

    actual suspend fun setRemoteDescription(description: SessionDescription) {
        return suspendCoroutine {
            android.setRemoteDescription(setSdpObserver(it), description.asAndroid())
        }
    }

    private fun setSdpObserver(continuation: Continuation<Unit>): SdpObserver {
        return object : SdpObserver {
            override fun onCreateSuccess(description: AndroidSessionDescription) {
                // not applicable for setting SDP
            }

            override fun onSetSuccess() {
                continuation.resume(Unit)
            }

            override fun onCreateFailure(error: String?) {
                // not applicable for setting SDP
            }

            override fun onSetFailure(error: String?) {
                continuation.resumeWithException(RuntimeException("Setting SDP failed: $error"))
            }
        }
    }

    actual fun setConfiguration(configuration: RtcConfiguration): Boolean {
        return android.setConfiguration(configuration.android)
    }

    actual fun addIceCandidate(candidate: IceCandidate): Boolean {
        return android.addIceCandidate(candidate.native)
    }

    actual fun removeIceCandidates(candidates: List<IceCandidate>): Boolean {
        return android.removeIceCandidates(candidates.map { it.native }.toTypedArray())
    }

    actual fun getSenders(): List<RtpSender> = android.senders.map { RtpSender(it) }

    actual fun getReceivers(): List<RtpReceiver> = android.receivers.map { RtpReceiver(it) }

    actual fun getTransceivers(): List<RtpTransceiver> =
        android.transceivers.map { RtpTransceiver(it) }

    actual fun addTrack(track: MediaStreamTrack, vararg streams: MediaStream): RtpSender {
        val streamIds = streams.map { it.id }
        return RtpSender(android.addTrack(track.android, streamIds))
    }

    actual fun removeTrack(sender: RtpSender): Boolean {
        return android.removeTrack(sender.native)
    }

    actual suspend fun getStats(): RtcStatsReport? {
        return suspendCoroutine { cont ->
            android.getStats { cont.resume(RtcStatsReport(it)) }
        }
    }

    actual fun close() {
        android.dispose()
    }

    internal inner class AndroidPeerConnectionObserver : AndroidPeerConnection.Observer {
        override fun onSignalingChange(newState: AndroidPeerConnection.SignalingState) {
            peerConnectionObserverProxy.onSignalingStateChange(newState.asCommon())
        }

        override fun onIceConnectionChange(newState: AndroidPeerConnection.IceConnectionState) {
            peerConnectionObserverProxy.onIceConnectionStateChange(newState.asCommon())
        }

        override fun onStandardizedIceConnectionChange(newState: AndroidPeerConnection.IceConnectionState) {
            peerConnectionObserverProxy.onStandardizedIceConnectionChange(newState.asCommon())
        }

        override fun onConnectionChange(newState: AndroidPeerConnection.PeerConnectionState) {
            peerConnectionObserverProxy.onConnectionStateChange(newState.asCommon())
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {}

        override fun onIceGatheringChange(newState: AndroidPeerConnection.IceGatheringState) {
            peerConnectionObserverProxy.onIceGatheringStateChange(newState.asCommon())
        }

        override fun onIceCandidate(candidate: AndroidIceCandidate) {
            peerConnectionObserverProxy.onIceCandidate(IceCandidate(candidate))
        }

        override fun onIceCandidatesRemoved(candidates: Array<out AndroidIceCandidate>) {
            peerConnectionObserverProxy.onRemovedIceCandidates(candidates.map { IceCandidate(it) })
        }

        override fun onAddStream(nativeStream: AndroidMediaStream) {
            // this deprecated API should not longer be used
            // https://developer.mozilla.org/en-US/docs/Web/API/RTCPeerConnection/onaddstream
        }

        override fun onRemoveStream(nativeStream: AndroidMediaStream) {
            // The removestream event has been removed from the WebRTC specification in favor of
            // the existing removetrack event on the remote MediaStream and the corresponding
            // MediaStream.onremovetrack event handler property of the remote MediaStream.
            // The RTCPeerConnection API is now track-based, so having zero tracks in the remote
            // stream is equivalent to the remote stream being removed and the old removestream event.
            // https://developer.mozilla.org/en-US/docs/Web/API/RTCPeerConnection/onremovestream
        }

        override fun onDataChannel(dataChannel: AndroidDataChannel) {
            peerConnectionObserverProxy.onDataChannel(DataChannel(dataChannel))
        }

        override fun onRenegotiationNeeded() {
            peerConnectionObserverProxy.onNegotiationNeeded()
        }

        override fun onAddTrack(
            receiver: AndroidRtpReceiver,
            nativeStreams: Array<out AndroidMediaStream>
        ) {
            // replaced by onTrack
        }

        override fun onTrack(transceiver: AndroidRtpTransceiver) {
            val sender = transceiver.sender

            val track = when (transceiver.mediaType) {
                AndroidMediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO -> {
                    AudioStreamTrack(transceiver.receiver.track() as AndroidAudioTrack)
                }

                AndroidMediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO -> {
                    VideoStreamTrack(transceiver.receiver.track() as AndroidVideoTrack)
                }

                else -> null
            }

            val tracks = track?.let { listOf(it) } ?: emptyList()

            val streams = sender.streams
                .takeIf { it.isNotEmpty() }
                ?.map { id -> MediaStream(android = null, id, tracks) }
                ?: listOf(MediaStream(tracks))

            val trackEvent = TrackEvent(
                receiver = RtpReceiver(transceiver.receiver),
                streams = streams,
                track = track,
                transceiver = RtpTransceiver(transceiver)
            )

            peerConnectionObserverProxy.onTrack(trackEvent)
        }

        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {
            // not implemented
        }
    }
}

private fun AndroidPeerConnection.SignalingState.asCommon(): SignalingState {
    return when (this) {
        AndroidPeerConnection.SignalingState.STABLE -> SignalingState.Stable
        AndroidPeerConnection.SignalingState.HAVE_LOCAL_OFFER -> SignalingState.HaveLocalOffer
        AndroidPeerConnection.SignalingState.HAVE_LOCAL_PRANSWER -> SignalingState.HaveLocalPranswer
        AndroidPeerConnection.SignalingState.HAVE_REMOTE_OFFER -> SignalingState.HaveRemoteOffer
        AndroidPeerConnection.SignalingState.HAVE_REMOTE_PRANSWER -> SignalingState.HaveRemotePranswer
        AndroidPeerConnection.SignalingState.CLOSED -> SignalingState.Closed
    }
}

private fun AndroidPeerConnection.IceConnectionState.asCommon(): IceConnectionState {
    return when (this) {
        AndroidPeerConnection.IceConnectionState.NEW -> IceConnectionState.New
        AndroidPeerConnection.IceConnectionState.CHECKING -> IceConnectionState.Checking
        AndroidPeerConnection.IceConnectionState.CONNECTED -> IceConnectionState.Connected
        AndroidPeerConnection.IceConnectionState.COMPLETED -> IceConnectionState.Completed
        AndroidPeerConnection.IceConnectionState.FAILED -> IceConnectionState.Failed
        AndroidPeerConnection.IceConnectionState.DISCONNECTED -> IceConnectionState.Disconnected
        AndroidPeerConnection.IceConnectionState.CLOSED -> IceConnectionState.Closed
    }
}

private fun AndroidPeerConnection.PeerConnectionState.asCommon(): PeerConnectionState {
    return when (this) {
        AndroidPeerConnection.PeerConnectionState.NEW -> PeerConnectionState.New
        AndroidPeerConnection.PeerConnectionState.CONNECTING -> PeerConnectionState.Connecting
        AndroidPeerConnection.PeerConnectionState.CONNECTED -> PeerConnectionState.Connected
        AndroidPeerConnection.PeerConnectionState.DISCONNECTED -> PeerConnectionState.Disconnected
        AndroidPeerConnection.PeerConnectionState.FAILED -> PeerConnectionState.Failed
        AndroidPeerConnection.PeerConnectionState.CLOSED -> PeerConnectionState.Closed
    }
}

private fun AndroidPeerConnection.IceGatheringState.asCommon(): IceGatheringState {
    return when (this) {
        AndroidPeerConnection.IceGatheringState.NEW -> IceGatheringState.New
        AndroidPeerConnection.IceGatheringState.GATHERING -> IceGatheringState.Gathering
        AndroidPeerConnection.IceGatheringState.COMPLETE -> IceGatheringState.Complete
    }
}

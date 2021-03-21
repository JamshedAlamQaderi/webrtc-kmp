package com.shepeliev.webrtckmm

import com.shepeliev.webrtckmm.utils.uuid
import kotlin.coroutines.cancellation.CancellationException
import kotlin.native.concurrent.ThreadLocal

@ThreadLocal
object MediaDevices {
    private var audioSource: AudioSource? = null
    private var videoSource: VideoSource? = null
    private var cameraCapturer: CameraVideoCapturer? = null
    private val audioTracks = mutableMapOf<String, Unit>()
    private val videoTracks = mutableMapOf<String, Unit>()

    // TODO implement video constraints
    @Throws(CameraVideoCapturerException::class, CancellationException::class)
    suspend fun getUserMedia(audio: Boolean, video: Boolean): MediaStream {
        val factory = peerConnectionFactory
        var audioTrack: AudioTrack? = null
        if (audio) {
            val source = audioSource ?: factory.createAudioSource(mediaConstraints())
            audioSource = source
            audioTrack = factory.createAudioTrack(uuid(), source)
            audioTracks += audioTrack.id to Unit
        }

        var videoTrack: VideoTrack? = null
        if (video) {
            val source = videoSource ?: factory.createVideoSource(
                isScreencast = false,
                alignTimestamps = true
            )
            videoSource = source
            if (cameraCapturer == null) {
                cameraCapturer = CameraEnumerator.createCameraVideoCapturer(source).apply {
                    startCapture(VideoConstraints())
                }
            }
            videoTrack = factory.createVideoTrack(uuid(), source)
            videoTracks += videoTrack.id to Unit
        }

        return factory.createLocalMediaStream(uuid()).apply {
            audioTrack?.let { addTrack(it) }
            videoTrack?.let { addTrack(it) }
        }
    }

    suspend fun enumerateDevices(): List<MediaDeviceInfo> {
        return CameraEnumerator.enumerateDevices()
    }

    @Throws(CameraVideoCapturerException::class, CancellationException::class)
    suspend fun switchCamera(): MediaDeviceInfo {
        val capturer = cameraCapturer ?: throw CameraVideoCapturerException.capturerStopped()
        return capturer.switchCamera()
    }

    @Throws(CameraVideoCapturerException::class, CancellationException::class)
    suspend fun switchCamera(cameraId: String): MediaDeviceInfo {
        val capturer = cameraCapturer ?: throw CameraVideoCapturerException.capturerStopped()
        return capturer.switchCamera(cameraId)
    }

    internal fun onAudioTrackStopped(trackId: String) {
        audioTracks.remove(trackId)
        if (audioTracks.isEmpty()) {
            audioSource = null
        }
    }

    internal fun onVideoTrackStopped(trackId: String) {
        videoTracks.remove(trackId)
        if (videoTracks.isEmpty()) {
            cameraCapturer?.stopCapture()
            cameraCapturer = null
            videoSource = null
        }
    }
}

data class MediaDeviceInfo(
    val deviceId: String,
    val label: String,
    val kind: MediaDeviceKind,
    val isFrontFacing: Boolean
)

enum class MediaDeviceKind { VideoInput, AudioInput }

data class MediaDeviceConstraints(
    val audio: Boolean? = true,
    val video: VideoConstraints? = VideoConstraints()
)

const val DEFAULT_VIDEO_WIDTH = 1280
const val DEFAULT_VIDEO_HEIGHT = 720
const val DEFAULT_FRAME_RATE = 30

data class VideoConstraints(
    val deviceId: String? = null,
    val isFrontFacing: Boolean? = null,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Int? = null,
)
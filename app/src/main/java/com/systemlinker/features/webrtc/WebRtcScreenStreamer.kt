package com.systemlinker.features.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import org.webrtc.*

class WebRtcScreenStreamer(
    private val context: Context,
    private val webRtcManager: WebRtcManager
) {
    private var screenCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    var isStreaming = false
        private set

    fun startStreaming(resultCode: Int, data: Intent) {
        if (isStreaming) return

        val factory = webRtcManager.getFactory() ?: return
        val eglContext = webRtcManager.getEglBaseContext()

        screenCapturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
            override fun onStop() { stopStreaming() }
        })

        surfaceTextureHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglContext)
        videoSource = factory.createVideoSource((screenCapturer as ScreenCapturerAndroid).isScreencast)
        screenCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
        
        screenCapturer?.startCapture(1280, 720, 30)

        videoTrack = factory.createVideoTrack("SCREEN_TRACK_ID_${System.currentTimeMillis()}", videoSource)
        webRtcManager.peerConnection?.addTrack(videoTrack)

        isStreaming = true
    }

    fun stopStreaming() {
        if (!isStreaming) return
        isStreaming = false

        try { screenCapturer?.stopCapture() } catch (e: Exception) {}

        screenCapturer?.dispose()
        screenCapturer = null

        videoSource?.dispose()
        videoSource = null

        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null

        videoTrack?.dispose()
        videoTrack = null
    }
}
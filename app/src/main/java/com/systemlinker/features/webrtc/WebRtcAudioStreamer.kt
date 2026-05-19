package com.systemlinker.features.webrtc

import org.webrtc.*

class WebRtcAudioStreamer(private val webRtcManager: WebRtcManager) {
    
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    var isStreaming = false
        private set

    fun startStreaming() {
        if (isStreaming) return
        val factory = webRtcManager.getFactory() ?: return

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }

        audioSource = factory.createAudioSource(audioConstraints)
        audioTrack = factory.createAudioTrack("AUDIO_TRACK_ID_${System.currentTimeMillis()}", audioSource)

        webRtcManager.peerConnection?.addTrack(audioTrack)
        isStreaming = true
    }

    fun stopStreaming() {
        if (!isStreaming) return
        isStreaming = false

        audioSource?.dispose(); audioSource = null
        audioTrack?.dispose(); audioTrack = null
    }
}
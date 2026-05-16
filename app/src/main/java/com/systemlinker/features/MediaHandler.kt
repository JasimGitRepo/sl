package com.systemlinker.features

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MediaHandler(private val context: Context) {

    @SuppressLint("MissingPermission")
    suspend fun takePicture(isFront: Boolean): File? = suspendCancellableCoroutine { continuation ->
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        var cameraId: String? = null

        try {
            for (id in manager.cameraIdList) {
                val chars = manager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (isFront && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    cameraId = id; break
                } else if (!isFront && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id; break
                }
            }

            if (cameraId == null) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            val handlerThread = HandlerThread("CameraBackground").apply { start() }
            val bgHandler = Handler(handlerThread.looper)
            val imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 1)

            val file = File(context.cacheDir, "cam_cap_${System.currentTimeMillis()}.jpg")

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    imageReader.setOnImageAvailableListener({ reader ->
                        try {
                            reader.acquireLatestImage()?.use { image ->
                                val buffer = image.planes[0].buffer
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)
                                FileOutputStream(file).use { it.write(bytes) }
                                
                                camera.close()
                                handlerThread.quitSafely()
                                if (continuation.isActive) continuation.resume(file)
                            }
                        } catch (e: Exception) {
                            camera.close()
                            handlerThread.quitSafely()
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }, bgHandler)

                    val characteristics = manager.getCameraCharacteristics(camera.id)
                    val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

                    val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(imageReader.surface)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                    }

                    camera.createCaptureSession(listOf(imageReader.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            try {
                                session.capture(captureBuilder.build(), null, bgHandler)
                            } catch (e: Exception) {
                                camera.close()
                            }
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            camera.close()
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }, bgHandler)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    handlerThread.quitSafely()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    handlerThread.quitSafely()
                    if (continuation.isActive) continuation.resume(null)
                }
            }, bgHandler)

        } catch (e: Exception) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun recordAudio(durationSeconds: Int): File? {
        val file = File(context.cacheDir, "mic_cap_${System.currentTimeMillis()}.mp4")
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        return try {
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            delay(durationSeconds * 1000L)
            recorder.stop()
            recorder.release()
            file
        } catch (e: Exception) {
            recorder.release()
            if (file.exists()) file.delete()
            null
        }
    }
}
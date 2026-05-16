package com.systemlinker.features

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import kotlin.math.min

class ScreenStreamer(
    private val context: Context,
    private val onFrameReady: (ByteArray) -> Unit
) {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastFrameTime = 0L
    private val TARGET_FPS = 5

    // --- FIX: Expose a public getter for isStreaming, but keep the setter private ---
    var isStreaming: Boolean = false
        private set

    // Default resolution (Shorter side in pixels)
    private var targetResolutionP = 360

    @SuppressLint("WrongConstant")
    fun startStreaming(resultCode: Int, data: Intent) {
        if (isStreaming) return

        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data) ?: return

        backgroundThread = HandlerThread("ScreenStreamer").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        isStreaming = true
        buildDisplayPipeline()
    }

    fun setResolution(resolutionP: Int) {
        if (targetResolutionP == resolutionP) return
        targetResolutionP = resolutionP

        // If we are currently streaming, hot-swap the pipeline
        if (isStreaming && mediaProjection != null) {
            teardownDisplayPipeline()
            buildDisplayPipeline()
        }
    }

    private fun buildDisplayPipeline() {
        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val density = metrics.densityDpi

        // Calculate aspect-ratio preserving dimensions
        val shortSide = min(screenWidth, screenHeight)
        val scaleFactor = targetResolutionP.toFloat() / shortSide.toFloat()

        // Prevent upscaling if requested res is higher than physical screen
        val finalScale = if (scaleFactor > 1f) 1f else scaleFactor

        val targetWidth = (screenWidth * finalScale).toInt()
        val targetHeight = (screenHeight * finalScale).toInt()

        imageReader = ImageReader.newInstance(targetWidth, targetHeight, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "SystemLinkerScreen",
            targetWidth, targetHeight, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, backgroundHandler
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            if (!isStreaming) return@setOnImageAvailableListener

            val now = System.currentTimeMillis()
            if (now - lastFrameTime < (1000 / TARGET_FPS)) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            lastFrameTime = now

            val image = try {
                reader.acquireLatestImage()
            } catch (e: Exception) { null } ?: return@setOnImageAvailableListener

            scope.launch {
                try {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * targetWidth

                    val bitmap = Bitmap.createBitmap(targetWidth + rowPadding / pixelStride, targetHeight, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(buffer)
                    image.close()

                    val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, targetWidth, targetHeight)
                    val stream = ByteArrayOutputStream()

                    // Adjust JPEG quality based on resolution to maintain bandwidth limits
                    val quality = when (targetResolutionP) {
                        in 0..240 -> 60
                        in 241..480 -> 40
                        else -> 30 // Compress heavier for HD
                    }

                    croppedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                    val jpegBytes = stream.toByteArray()

                    croppedBitmap.recycle()
                    bitmap.recycle()

                    val dataToSend = ByteArray(jpegBytes.size + 1)
                    dataToSend[0] = 0x03
                    System.arraycopy(jpegBytes, 0, dataToSend, 1, jpegBytes.size)

                    onFrameReady(dataToSend)
                } catch (e: Exception) {
                    image.close()
                }
            }
        }, backgroundHandler)
    }

    private fun teardownDisplayPipeline() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
    }

    fun stopStreaming() {
        isStreaming = false
        teardownDisplayPipeline()
        mediaProjection?.stop()
        mediaProjection = null
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null
    }
}
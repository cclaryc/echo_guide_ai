package com.example.myapplication.utils

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import android.graphics.Rect
import android.util.Log
import android.graphics.BitmapFactory

object ImageUtils {

    fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        try {
            val yBuffer = image.planes[0].buffer   // Y
            val uBuffer = image.planes[1].buffer   // U
            val vBuffer = image.planes[2].buffer   // V

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            // U și V sunt inversate la CameraX → trebuie swap
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(
                nv21,
                ImageFormat.NV21,
                image.width,
                image.height,
                null
            )

            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)

            val jpegBytes = out.toByteArray()

            return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)

        } catch (e: Exception) {
            Log.e("ImageUtils", "imageProxyToBitmap ERROR: ${e.message}")
            throw e
        }
    }
}

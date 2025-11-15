package com.example.myapplication.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import kotlin.math.max
import kotlin.math.min
import com.example.myapplication.state.LightState


class YoloV5OnnxDetector(private val context: Context) {

    data class Detection(
        val label: String,
        val score: Float,
        val rect: Rect
    )

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val labels: List<String>

    init {
        val modelBytes = context.assets.open("yolov5s.onnx").readBytes()
        session = env.createSession(modelBytes)
        labels = context.assets.open("labels.txt").bufferedReader().readLines()
    }

    private fun argmax(arr: FloatArray): Int {
        var maxI = 0
        var max = arr[0]
        for (i in 1 until arr.size) {
            if (arr[i] > max) {
                max = arr[i]
                maxI = i
            }
        }
        return maxI
    }

    private fun parseDetections(data: Array<FloatArray>): List<Detection> {
        val detections = ArrayList<Detection>()

        for (row in data) {
            val objectness = row[4]
            if (objectness < 0.25f) continue

            val classes = row.copyOfRange(5, row.size)
            val classId = argmax(classes)
            val classProb = classes[classId]

            if (classProb < 0.25f) continue

            val label = labels[classId].lowercase()
            if (!label.contains("traffic")) continue  // FILTRARE STRICTĂ

            val cx = row[0]
            val cy = row[1]
            val w = row[2]
            val h = row[3]

            val left = (cx - w / 2).toInt()
            val top = (cy - h / 2).toInt()
            val right = (cx + w / 2).toInt()
            val bottom = (cy + h / 2).toInt()

            // Ignoră semafoarele prea mici (zgomot)
            if (w < 20 || h < 50) continue

            detections.add(
                Detection(label, classProb, Rect(left, top, right, bottom))
            )
        }

        return detections
    }

    private fun toTensor(bmp: Bitmap): OnnxTensor {
        val inputSize = 640
        val arr = FloatArray(3 * inputSize * inputSize)

        var idx = 0
        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val p = bmp.getPixel(x, y)

                val r = ((p shr 16) and 0xFF) / 255f
                val g = ((p shr 8) and 0xFF) / 255f
                val b = (p and 0xFF) / 255f

                // NCHW: B, G, R
                arr[idx] = b
                arr[idx + inputSize * inputSize] = g
                arr[idx + 2 * inputSize * inputSize] = r

                idx++
            }
        }

        val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        return OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(arr), shape)
    }

    fun detectTrafficLight(original: Bitmap): LightState {
        val inputSize = 640
        val resized = Bitmap.createScaledBitmap(original, inputSize, inputSize, true)

        val results = session.run(mapOf("images" to toTensor(resized)))
        val output = results[0].value as Array<Array<FloatArray>>
        val detections = parseDetections(output[0])

        if (detections.isEmpty()) {
            Log.d("YOLO", "Niciun semafor detectat.")
            return LightState.NONE
        }

        // ALEGEM SEMAFORUL PREDOMINANT — cel mai mare în scenă
        val best = detections.maxByOrNull { it.rect.width() * it.rect.height() }!!

        val ow = original.width
        val oh = original.height

        // Recalibrare coordonate din spațiul 640x640 în dimensiunile originale
        val scaleX = ow / 640f
        val scaleY = oh / 640f

        var left = (best.rect.left * scaleX).toInt().coerceAtLeast(0)
        var top = (best.rect.top * scaleY).toInt().coerceAtLeast(0)
        var right = (best.rect.right * scaleX).toInt().coerceAtMost(ow)
        var bottom = (best.rect.bottom * scaleY).toInt().coerceAtMost(oh)

        if (right <= left || bottom <= top) return LightState.NONE

        // STRÂNGEM bounding box-ul spre centru ca să evităm frunzele / fundalul
        val bw = right - left
        val bh = bottom - top
        val shrinkX = (bw * 0.15f).toInt()
        val shrinkY = (bh * 0.15f).toInt()

        left = (left + shrinkX).coerceAtLeast(0)
        top = (top + shrinkY).coerceAtLeast(0)
        right = (right - shrinkX).coerceAtMost(ow)
        bottom = (bottom - shrinkY).coerceAtMost(oh)

        if (right <= left || bottom <= top) return LightState.NONE

        val crop = Bitmap.createBitmap(original, left, top, right - left, bottom - top)

        return analyzeLightColor(crop)
    }

    /**
     * Analiză robustă a culorii:
     * - folosește HSV (hue/sat/value)
     * - împarte vertical în 3 zone (sus=roșu, mijloc=galben, jos=verde)
     * - ia doar zona centrală pe orizontală ca să ignore frunzele de pe margine
     * - numără pixeli de culoarea potrivită și decide după numărul maxim
     */
    private fun analyzeLightColor(crop: Bitmap): LightState {
        val w = crop.width
        val h = crop.height

        if (w <= 0 || h <= 0) return LightState.NONE

        // Luăm doar ~40% central pe orizontală (evităm margini cu frunze / stâlp)
        val xStart = (w * 0.3f).toInt()
        val xEnd = (w * 0.7f).toInt()

        // Pas de eșantionare (nu luăm fiecare pixel ca să nu omorâm performanța)
        val sampleStep = max(1, min(w, h) / 30)

        val hsv = FloatArray(3)

        fun countColor(
            yStartFrac: Float,
            yEndFrac: Float,
            isColor: (hue: Float, sat: Float, value: Float) -> Boolean
        ): Int {
            var count = 0
            val yStartPx = (h * yStartFrac).toInt().coerceIn(0, h - 1)
            val yEndPx = (h * yEndFrac).toInt().coerceIn(0, h)

            var y = yStartPx
            while (y < yEndPx) {
                var x = xStart
                while (x < xEnd) {
                    val px = crop.getPixel(x, y)
                    val r = Color.red(px)
                    val g = Color.green(px)
                    val b = Color.blue(px)

                    Color.RGBToHSV(r, g, b, hsv)
                    val hue = hsv[0]    // 0..360
                    val sat = hsv[1]    // 0..1
                    val value = hsv[2]  // 0..1

                    // Ignorăm pixeli foarte întunecați sau foarte desaturați (gri / negru)
                    if (value > 0.3f && sat > 0.4f && isColor(hue, sat, value)) {
                        count++
                    }

                    x += sampleStep
                }
                y += sampleStep
            }

            return count
        }

        // Intervalele de hue pot fi ajustate după ce testezi pe imaginile tale
        // Sus (roșu)
        val redCount = countColor(0.00f, 0.33f) { hue, _, _ ->
            hue < 15f || hue > 345f       // roșu în jur de 0°
        }

        // Mijloc (galben)
        val yellowCount = countColor(0.33f, 0.66f) { hue, _, _ ->
            hue in 20f..60f               // galben / portocaliu
        }

        // Jos (verde)
        val greenCount = countColor(0.66f, 1.00f) { hue, _, _ ->
            hue in 80f..160f              // verde
        }

        Log.d("YOLO_LIGHT", "redCount=$redCount yellowCount=$yellowCount greenCount=$greenCount")

        val maxCount = maxOf(redCount, yellowCount, greenCount)

        // prag minim ca să nu ne păcălim din zgomot
        val MIN_PIXELS = 20
        if (maxCount < MIN_PIXELS) {
            return LightState.NONE
        }

        // Dacă două culori sunt foarte apropiate ca număr de pixeli, considerăm că nu știm sigur
        fun areClose(a: Int, b: Int): Boolean {
            if (a == 0 || b == 0) return false
            val ratio = min(a, b).toFloat() / max(a, b).toFloat()
            return ratio > 0.75f // 75% din cealaltă → prea apropiate
        }

        return when {
            maxCount == redCount &&
                    !areClose(redCount, yellowCount) &&
                    !areClose(redCount, greenCount) ->
                LightState.RED

            maxCount == yellowCount &&
                    !areClose(yellowCount, redCount) &&
                    !areClose(yellowCount, greenCount) ->
                LightState.YELLOW

            maxCount == greenCount &&
                    !areClose(greenCount, yellowCount) &&
                    !areClose(greenCount, redCount) ->
                LightState.GREEN

            else -> LightState.NONE
        }
    }
}
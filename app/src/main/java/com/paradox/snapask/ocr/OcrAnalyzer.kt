package com.paradox.snapask.ocr

import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/**
 * 端侧 OCR 分析器：常驻相机的 ImageAnalysis 通道，但只在 armed=true 时真正跑识别，
 * 其余帧直接丢弃 —— 省电、省内存、避免无意义的模型调用。
 */
class OcrAnalyzer : ImageAnalysis.Analyzer {

    @Volatile
    var armed = false

    var onText: ((String) -> Unit)? = null

    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        if (!armed) {
            imageProxy.close()
            return
        }
        armed = false

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        recognizer.process(input)
            .addOnSuccessListener { result -> onText?.invoke(result.text) }
            .addOnFailureListener { e -> onText?.invoke("OCR 失败：${e.message}") }
            .addOnCompleteListener { imageProxy.close() }
    }
}

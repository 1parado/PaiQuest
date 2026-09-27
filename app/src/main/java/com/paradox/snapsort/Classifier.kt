package com.paradox.snapsort

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 端侧识别引擎：图像标签 + 中文 OCR，全部端侧完成，零网络请求。
 * 只负责「提取信息」（供搜索与 AI 讲解使用），不做自动归类——
 * 新记录默认「未分类」，归类权完全交给用户。
 */
object Classifier {

    private val labeler by lazy { ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS) }
    private val ocr by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }

    data class Result(
        val labels: List<String>,
        val ocrText: String,
    )

    fun classify(context: Context, imageFile: File): Result {
        val input = InputImage.fromFilePath(context, Uri.fromFile(imageFile))

        val labels = try {
            Tasks.await(labeler.process(input), 15, TimeUnit.SECONDS)
                .filter { it.confidence >= 0.5f }
                .sortedByDescending { it.confidence }
                .map { it.text }
        } catch (e: Exception) {
            emptyList()
        }

        val text = try {
            Tasks.await(ocr.process(input), 15, TimeUnit.SECONDS).text
        } catch (e: Exception) {
            ""
        }

        return Result(labels, text)
    }
}

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
 * 离线分类引擎：图像标签（粗分类）+ 中文 OCR（辅助判断），全部端侧完成，零网络请求。
 *
 * 分类规则（高信息密度、可解释）：
 * 1. 文字占比高（OCR 字数多）→ 错题本：拍题是最高频的「拍文字」场景；
 * 2. 标签命中植物类 → 植物；
 * 3. 标签命中动物类 → 动物；
 * 4. 其余 → 其他。
 */
object Classifier {

    private val labeler by lazy { ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS) }
    private val ocr by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }

    data class Result(
        val categoryId: String,
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

        return Result(decide(labels, text), labels, text)
    }

    private fun decide(labels: List<String>, ocrText: String): String {
        fun has(vararg keys: String) = labels.any { l -> keys.any { l.contains(it, ignoreCase = true) } }

        val textHeavy = ocrText.length >= 30
        return when {
            textHeavy && (has("Text", "Document", "Book", "Paper", "Handwriting") || ocrText.length >= 120) ->
                Categories.MISTAKE.id
            has("Plant", "Flower", "Tree", "Leaf", "Grass", "Garden", "Fruit") ->
                Categories.PLANT.id
            has("Animal", "Cat", "Dog", "Bird", "Fish", "Insect", "Pet", "Butterfly", "Horse", "Reptile") ->
                Categories.ANIMAL.id
            else -> Categories.OTHER.id
        }
    }
}

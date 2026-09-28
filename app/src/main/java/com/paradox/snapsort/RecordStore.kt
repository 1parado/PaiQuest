package com.paradox.snapsort

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 零依赖本地存储：每条记录 = 一个文件夹（photo.jpg + meta.json）。
 * 不引入 Room/SQLite —— 个人级数据量下，扫描目录足够快，换来的是体积与复杂度双降。
 *
 * filesDir/records/<id>/photo.jpg   正式记录
 * filesDir/records/<id>/meta.json   { id, category, created_at, labels, ocr_text, note }
 * filesDir/trash/<id>/...           回收站（删除先入站，可恢复 / 彻底清除）
 */
class RecordStore(context: Context) {

    private val root = File(context.filesDir, "records").apply { mkdirs() }
    private val trashDir = File(context.filesDir, "trash").apply { mkdirs() }

    data class Record(
        val id: String,
        val categoryId: String,
        val createdAt: Long,
        val labels: List<String>,
        val ocrText: String,
        val note: String?,
        val trashedAt: Long = 0,
    )

    fun save(
        photoBytes: ByteArray,
        categoryId: String = Categories.UNCATEGORIZED.id,
        labels: List<String>,
        ocrText: String,
    ): Record {
        val id = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) +
            "_" + (1000..9999).random()
        val dir = File(root, id).apply { mkdirs() }
        File(dir, "photo.jpg").writeBytes(photoBytes)

        val meta = JSONObject()
            .put("id", id)
            .put("category", categoryId)
            .put("created_at", System.currentTimeMillis())
            .put("labels", JSONArray(labels))
            .put("ocr_text", ocrText)
            .put("note", "")
        File(dir, "meta.json").writeText(meta.toString())

        return Record(id, categoryId, meta.getLong("created_at"), labels, ocrText, null)
    }

    fun list(categoryId: String? = null): List<Record> =
        root.listFiles { f -> f.isDirectory && File(f, "meta.json").exists() }
            ?.mapNotNull { dir ->
                runCatching { parse(File(dir, "meta.json")) }.getOrNull()
            }
            ?.filter { categoryId == null || it.categoryId == categoryId }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()

    fun get(id: String): Record? =
        File(root, "$id/meta.json").takeIf { it.exists() }?.let { runCatching { parse(it) }.getOrNull() }

    fun photoFile(id: String): File = File(root, "$id/photo.jpg")

    fun move(id: String, newCategoryId: String) = mutate(id) { it.put("category", newCategoryId) }

    /** 把某分类下的全部记录迁移到另一分类（重命名 / 删除分类时用） */
    fun retargetCategory(fromId: String, toId: String) {
        list(fromId).forEach { move(it.id, toId) }
    }

    fun saveNote(id: String, note: String) = mutate(id) { it.put("note", note) }

    /** 手动编辑标签（整体覆盖：添加与删除都走这里） */
    fun saveLabels(id: String, labels: List<String>) = mutate(id) { it.put("labels", JSONArray(labels)) }

    // ---------------- 回收站 ----------------

    /** 删除 → 先移入回收站（可恢复） */
    fun trash(id: String) {
        val dir = File(root, id)
        if (!dir.exists()) return
        runCatching {
            val mf = File(dir, "meta.json")
            val m = JSONObject(mf.readText())
            m.put("trashed_at", System.currentTimeMillis())
            mf.writeText(m.toString())
        }
        val dest = File(trashDir, id)
        if (dest.exists()) dest.deleteRecursively()
        dir.renameTo(dest)
    }

    fun listTrashed(): List<Record> =
        trashDir.listFiles { f -> f.isDirectory && File(f, "meta.json").exists() }
            ?.mapNotNull { dir -> runCatching { parse(File(dir, "meta.json")) }.getOrNull() }
            ?.sortedByDescending { it.trashedAt }
            ?: emptyList()

    fun trashedPhotoFile(id: String): File = File(trashDir, "$id/photo.jpg")

    fun restore(id: String) {
        val dir = File(trashDir, id)
        if (!dir.exists()) return
        runCatching {
            val mf = File(dir, "meta.json")
            val m = JSONObject(mf.readText())
            m.remove("trashed_at")
            mf.writeText(m.toString())
        }
        dir.renameTo(File(root, id))
    }

    /** 彻底删除（不可恢复） */
    fun purge(id: String) {
        File(trashDir, id).deleteRecursively()
    }

    fun emptyTrash() {
        trashDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    // ---------------- 内部 ----------------

    private fun mutate(id: String, edit: (JSONObject) -> JSONObject) {
        val metaFile = File(root, "$id/meta.json")
        val meta = JSONObject(metaFile.readText())
        metaFile.writeText(edit(meta).toString())
    }

    private fun parse(metaFile: File): Record {
        val m = JSONObject(metaFile.readText())
        val labelsJson = m.optJSONArray("labels") ?: JSONArray()
        val labels = (0 until labelsJson.length()).map { labelsJson.getString(it) }
        return Record(
            id = m.getString("id"),
            categoryId = m.getString("category"),
            createdAt = m.getLong("created_at"),
            labels = labels,
            ocrText = m.optString("ocr_text"),
            note = m.optString("note").ifBlank { null },
            trashedAt = m.optLong("trashed_at"),
        )
    }
}

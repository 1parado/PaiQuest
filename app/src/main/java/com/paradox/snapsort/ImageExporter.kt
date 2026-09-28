package com.paradox.snapsort

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 把记录原图保存到系统相册（Pictures/SnapSort）。
 * API 29+：MediaStore 直接写入，无需任何权限；
 * API 24-28：写公共 Pictures 目录 + 媒体扫描，需要 WRITE_EXTERNAL_STORAGE 运行时权限（manifest 限 maxSdk 28）。
 */
object ImageExporter {

    fun saveToGallery(context: Context, source: File): Result<String> = runCatching {
        val name = "SnapSort_${System.currentTimeMillis()}.jpg"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SnapSort")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("系统拒绝了相册写入请求")
            context.contentResolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: error("写入相册失败")
            "相册 Pictures/SnapSort"
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "SnapSort")
            dir.mkdirs()
            val dest = File(dir, name)
            source.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
            MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf("image/jpeg"), null)
            "相册 Pictures/SnapSort"
        }
    }
}

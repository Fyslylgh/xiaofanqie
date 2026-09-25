package com.fysly.pomodoro.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 媒体卡片自定义封面的存取。
 *
 * 用户选的图片会被**拷贝进应用私有目录**，而不是保存相册的 content URI——
 * URI 的读取权限可能被回收，也可能因为换机、清理相册而失效，
 * 拷贝一份最省心，代价只是一张压缩到 1024px 的 JPEG。
 */
object CustomArtworkStore {

    private const val FILE_NAME = "media_artwork.jpg"
    private const val TARGET_SIZE = 1024
    private const val JPEG_QUALITY = 90

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun exists(context: Context): Boolean = file(context).isFile

    /** 读取自定义封面；没设置或读不出来时返回 null。 */
    fun load(context: Context): Bitmap? {
        val f = file(context)
        if (!f.isFile) return null
        return runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
    }

    fun lastModified(context: Context): Long = file(context).lastModified()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    /**
     * 把相册里的图片导入成封面：解码 → 居中裁成正方形 → 缩放 → 存 JPEG。
     *
     * 解码分两趟：先只读尺寸算出采样率，再真正解码。
     * 现在的手机随手一拍就是几千万像素，直接整张解码进内存很容易 OOM。
     */
    suspend fun importFrom(context: Context, uri: android.net.Uri): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri).use { stream ->
                    if (stream == null) return@runCatching false
                    BitmapFactory.decodeStream(stream, null, bounds)
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching false

                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, TARGET_SIZE)
                }
                val decoded = resolver.openInputStream(uri).use { stream ->
                    if (stream == null) return@runCatching false
                    BitmapFactory.decodeStream(stream, null, decodeOptions)
                } ?: return@runCatching false

                val squared = centerCropToSquare(decoded)
                val scaled = if (squared.width == TARGET_SIZE) {
                    squared
                } else {
                    Bitmap.createScaledBitmap(squared, TARGET_SIZE, TARGET_SIZE, true)
                }

                FileOutputStream(file(context)).use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }
                if (scaled !== decoded) scaled.recycle()
                decoded.recycle()
                true
            }.getOrDefault(false)
        }

    /** 采样率取 2 的幂，保证解码结果不小于目标尺寸。 */
    private fun sampleSizeFor(width: Int, height: Int, target: Int): Int {
        var sample = 1
        var longest = max(width, height)
        while (longest / 2 >= target) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    private fun centerCropToSquare(source: Bitmap): Bitmap {
        val side = min(source.width, source.height)
        val left = (source.width - side) / 2
        val top = (source.height - side) / 2
        return Bitmap.createBitmap(source, left, top, side, side)
    }
}

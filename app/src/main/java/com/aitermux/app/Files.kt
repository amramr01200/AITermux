package com.aitermux.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayOutputStream

object Files {
    fun nameOf(ctx: Context, uri: Uri): String {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) return c.getString(i)
        }
        return uri.lastPathSegment ?: "file"
    }

    private fun toUrl(b: Bitmap): String {
        val s = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 80, s)
        return "data:image/jpeg;base64," + Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)
    }

    private fun scaled(b: Bitmap, max: Int): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= max) return b
        val f = max.toFloat() / m
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt(), (b.height * f).toInt(), true)
    }

    fun read(ctx: Context, uri: Uri): Attachment {
        val name = nameOf(ctx, uri)
        val mime = ctx.contentResolver.getType(uri) ?: ""
        return try {
            when {
                mime.startsWith("image/") -> {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o) }
                    var s = 1
                    while (maxOf(o.outWidth, o.outHeight) / s > 2560) s *= 2
                    val o2 = BitmapFactory.Options().apply { inSampleSize = s }
                    val b = ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o2) }!!
                    Attachment(name, mime, null, listOf(toUrl(scaled(b, 1280))))
                }
                mime == "application/pdf" -> {
                    ctx.contentResolver.openFileDescriptor(uri, "r")!!.use { fd ->
                        val r = PdfRenderer(fd)
                        val imgs = ArrayList<String>()
                        val total = r.pageCount
                        for (i in 0 until minOf(total, 5)) {
                            val pg = r.openPage(i)
                            val w = 1000
                            val h = (pg.height * w / pg.width.toFloat()).toInt()
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            pg.close()
                            imgs.add(toUrl(bmp))
                        }
                        r.close()
                        Attachment(name, mime, "(PDF: ${imgs.size}/$total pages sent as images)", imgs)
                    }
                }
                mime.startsWith("video/") -> {
                    val r = MediaMetadataRetriever()
                    r.setDataSource(ctx, uri)
                    val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val imgs = (0 until 4).mapNotNull {
                        r.getFrameAtTime(dur * 1000L * it / 4 + 1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            ?.let { b -> toUrl(scaled(b, 1024)) }
                    }
                    r.release()
                    Attachment(name, mime, "(video: ${imgs.size} frames extracted)", imgs)
                }
                mime.startsWith("audio/") ->
                    Attachment(name, mime, "(audio file attached: speech-to-text for files is not supported yet)", emptyList())
                else -> {
                    val t = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                    Attachment(name, mime, t.take(100000), emptyList())
                }
            }
        } catch (e: Exception) {
            Attachment(name, mime, "(could not read file: ${e.message})", emptyList())
        }
    }
}

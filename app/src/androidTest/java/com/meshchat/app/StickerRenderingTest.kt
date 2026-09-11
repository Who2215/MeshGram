package com.meshchat.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.meshchat.app.ui.NotoStickerCatalog
import java.io.File
import junit.framework.TestCase

class StickerRenderingTest : TestCase() {
    fun testEveryAnimationRendersAndChangesFrames() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir(null), "sticker-qa").apply { mkdirs() }
        val paint = Paint().apply { color = Color.WHITE; textSize = 12f }
        NotoStickerCatalog.entries.chunked(10).forEachIndexed { page, entries ->
            val sheet = Bitmap.createBitmap(768, 1500, Bitmap.Config.ARGB_8888)
            val board = Canvas(sheet).apply { drawColor(Color.rgb(22, 26, 40)) }
            entries.forEachIndexed { row, entry ->
                val parsed = context.assets.open(entry.asset!!).use { LottieCompositionFactory.fromJsonInputStreamSync(it, null) }
                assertNull("${entry.id}: parse", parsed.exception)
                val composition = parsed.value!!
                assertTrue(composition.duration > 0)
                val drawable = LottieDrawable().apply { setComposition(composition); setBounds(0, 0, 128, 128) }
                val hashes = mutableSetOf<Int>()
                var nonempty = false
                board.drawText(entry.id, 4f, row * 150f + 14, paint)
                for (frame in 0..5) {
                    val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
                    drawable.progress = frame / 6f
                    drawable.draw(Canvas(bitmap))
                    val pixels = IntArray(128 * 128)
                    bitmap.getPixels(pixels, 0, 128, 0, 0, 128, 128)
                    nonempty = nonempty || pixels.any { Color.alpha(it) > 0 }
                    assertTrue("${entry.id}: transparency", pixels.any { Color.alpha(it) == 0 })
                    hashes.add(pixels.contentHashCode())
                    board.drawBitmap(bitmap, frame * 128f, row * 150f + 20, null)
                    bitmap.recycle()
                }
                assertTrue("${entry.id}: empty", nonempty)
                assertTrue("${entry.id}: static", hashes.size > 1)
                val preview = BitmapFactory.decodeResource(context.resources, entry.preview!!)
                assertNotNull("${entry.id}: preview", preview)
                assertTrue(preview.hasAlpha())
                preview.recycle()
            }
            File(output, "sheet-$page.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
            sheet.recycle()
        }
    }
    fun testMalformedAnimationFailsWithoutThrowing() {
        val result = LottieCompositionFactory.fromJsonStringSync("{broken", null)
        assertNull(result.value)
        assertNotNull(result.exception)
    }
}

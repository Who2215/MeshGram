package com.meshchat.app

import com.meshchat.app.ui.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class StickerCatalogTest {
    @Test fun catalogIsCompleteAndCompatible() {
        assertEquals(80, NotoStickerCatalog.entries.size)
        assertEquals(106, StickerCatalog.entries.size)
        assertEquals(106, StickerCatalog.entries.map { it.id }.toSet().size)
        StickerCatalog.entries.forEach { assertEquals(it.id, MeshExpressions.stickerId(MeshExpressions.token(it.id))) }
        assertEquals("hello", StickerCatalog.find("wave")!!.id)
    }
    @Test fun localAssetsLicensesAndSizeBudget() {
        val assets = File("src/main/assets")
        NotoStickerCatalog.entries.forEach {
            assertTrue(it.id, File(assets, it.asset!!).isFile)
            assertTrue(it.id, File("src/main/res/drawable-nodpi/${it.id}.png").isFile)
            assertTrue(it.attribution.contains("CC BY 4.0"))
        }
        assertTrue(File(assets, "licenses/noto-animation-CC-BY-4.0.txt").length() > 1000)
        assertTrue(assets.walkTopDown().filter { it.isFile }.sumOf { it.length() } < 16 * 1024 * 1024)
    }
    @Test fun recentsDeduplicateFilterAndBound() {
        val ids = StickerCatalog.entries.map { it.id }
        val result = StickerCatalog.recent(ids + "missing", "popcorn")
        assertEquals("popcorn", result.first())
        assertEquals(24, result.size)
        assertEquals(result.size, result.toSet().size)
        assertFalse(result.contains("missing"))
    }
    @Test fun sixScenesReturnSmoothlyToRestAndActuallyMove() {
        listOf("laugh", "facepalm", "popcorn", "coffee", "dance", "rage").forEach { id ->
            assertEquals(MascotMotion.pose(id, 0f), MascotMotion.pose(id, 1f))
            assertNotEquals(MascotMotion.pose(id, 0f), MascotMotion.pose(id, .5f))
            for (i in 0..100) {
                val p = MascotMotion.pose(id, i / 100f)
                assertTrue(p.x.isFinite() && p.y.isFinite() && p.tilt.isFinite())
                assertTrue(kotlin.math.abs(p.tilt) < 35)
            }
        }
    }
}

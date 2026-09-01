package com.meshchat.app.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveMeshBackgroundTest {
    @Test
    fun modernDeviceUsesFullQuality() {
        assertEquals(
            MeshRenderQuality.FULL,
            adaptiveMeshRenderQuality(
                sdkInt = 35,
                isLowRamDevice = false,
                isPowerSaveMode = false
            )
        )
    }

    @Test
    fun oldOrConstrainedDeviceUsesReducedQuality() {
        assertEquals(
            MeshRenderQuality.REDUCED,
            adaptiveMeshRenderQuality(
                sdkInt = 25,
                isLowRamDevice = false,
                isPowerSaveMode = false
            )
        )
        assertEquals(
            MeshRenderQuality.REDUCED,
            adaptiveMeshRenderQuality(
                sdkInt = 35,
                isLowRamDevice = true,
                isPowerSaveMode = false
            )
        )
        assertEquals(
            MeshRenderQuality.REDUCED,
            adaptiveMeshRenderQuality(
                sdkInt = 35,
                isLowRamDevice = false,
                isPowerSaveMode = true
            )
        )
        assertEquals(
            MeshRenderQuality.REDUCED,
            adaptiveMeshRenderQuality(
                sdkInt = 35,
                isLowRamDevice = false,
                isPowerSaveMode = false,
                shortestWidthDp = 411
            )
        )
    }

    @Test
    fun themeColorsAreMappedToAmbientPalette() {
        val palette = ambientPaletteFromTheme(
            backgroundStart = Color.Red,
            backgroundEnd = Color.Blue,
            primary = Color.Magenta,
            secondary = Color.Cyan,
            tertiary = Color.Green
        )

        assertEquals(Color.Red, palette.backgroundStart)
        assertEquals(Color.Blue, palette.backgroundEnd)
        assertEquals(Color.Magenta, palette.primary)
        assertEquals(Color.Cyan, palette.secondary)
        assertEquals(Color.Green, palette.tertiary)
    }
}

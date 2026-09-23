package com.meshchat.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshInviteLocalizationTest {
    @Test
    fun supportedLanguagesHaveCompleteInviteCopy() {
        val english = localizedInviteStrings("en")
        val supported = listOf("ru", "es", "de", "fr", "pt", "it", "tr", "zh", "ja", "ko", "ar", "hi")

        supported.forEach { language ->
            val strings = localizedInviteStrings(language)
            assertNotEquals("$language must not fall back to the complete English invite flow", english, strings)
            assertTrue("$language must preserve the invite protocol prefix", strings.inviteCodePrefixHint.contains("MESHINV1:"))
            assertTrue(inviteValues(strings).all(String::isNotBlank))
        }
    }

    @Test
    fun unknownLanguageUsesEnglishFallback() {
        assertEquals(localizedInviteStrings("en"), localizedInviteStrings("unknown"))
    }

    private fun inviteValues(strings: MeshInviteStrings): List<String> = listOf(
        strings.joinByCode,
        strings.scanInviteQr,
        strings.pasteInviteCode,
        strings.inviteCodePrefixHint,
        strings.joinInvite,
        strings.inviteApplied,
        strings.inviteInvalid,
        strings.inviteQrApplied,
        strings.inviteQrTitle,
        strings.copyInviteCode,
        strings.showInviteQr,
        strings.inviteCodeCopied,
        strings.inviteCodeUnavailable,
        strings.qrScanCanceled,
        strings.scannedQrInvalid,
        strings.cameraPermissionDenied
    )
}

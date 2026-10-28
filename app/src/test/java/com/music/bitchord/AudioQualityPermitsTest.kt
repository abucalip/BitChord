package com.music.bitchord

import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.settings.permits
import com.music.bitchord.data.sources.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which sources a streaming ceiling lets answer — see [permits]. */
class AudioQualityPermitsTest {

    @Test
    fun `a Navidrome server is permitted on every rung`() {
        AudioQuality.entries.forEach { quality ->
            assertTrue("$quality must permit Navidrome", quality.permits(SourceKind.NAVIDROME))
        }
    }

    @Test
    fun `YouTube is permitted on every rung`() {
        AudioQuality.entries.forEach { quality ->
            assertTrue("$quality must permit YouTube", quality.permits(SourceKind.YOUTUBE))
        }
    }

    @Test
    fun `lossless-capable addon kinds are still skipped below Lossless`() {
        listOf(SourceKind.ADDON, SourceKind.CUSTOM_MODULE).forEach { kind ->
            assertTrue(AudioQuality.LOSSLESS.permits(kind))
            assertFalse(AudioQuality.HIGH.permits(kind))
            assertFalse(AudioQuality.MEDIUM.permits(kind))
            assertFalse(AudioQuality.LOW.permits(kind))
        }
    }

    @Test
    fun `Navidrome is the only lossless-capable kind that every rung permits`() {
        val everywhere = SourceKind.entries.filter { kind ->
            kind.canServeLossless && AudioQuality.entries.all { it.permits(kind) }
        }
        assertEquals(listOf(SourceKind.NAVIDROME), everywhere)
    }
}

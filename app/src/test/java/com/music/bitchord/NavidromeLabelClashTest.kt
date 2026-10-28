package com.music.bitchord

import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.ui.screens.navidromeLabelTakenBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavidromeLabelClashTest {
    private val home = SourceConfig(id = "a", kind = SourceKind.NAVIDROME, label = "Home", baseUrl = "https://a.example")
    private val other = SourceConfig(id = "b", kind = SourceKind.NAVIDROME, label = "Office", baseUrl = "https://b.example")
    private val unnamed = SourceConfig(id = "c", kind = SourceKind.NAVIDROME, label = "", baseUrl = "https://c.example")
    private val addon = SourceConfig(id = "d", kind = SourceKind.ADDON, label = "Home", baseUrl = "https://d.example")
    private val all = listOf(home, other, unnamed, addon)

    @Test
    fun sameNameInAnotherServerIsFlaggedIgnoringCaseAndSpace() {
        assertEquals(home, navidromeLabelTakenBy(all, " home ", exceptId = "b"))
    }

    @Test
    fun editingTheServerItselfIsNotAClash() {
        assertNull(navidromeLabelTakenBy(all, "Home", exceptId = "a"))
    }

    @Test
    fun blankLabelNeverClashes() {
        assertNull(navidromeLabelTakenBy(all, "  ", exceptId = null))
        assertNull(navidromeLabelTakenBy(all, "", exceptId = null))
    }

    @Test
    fun otherSourceKindsDoNotCount() {
        assertNull(navidromeLabelTakenBy(listOf(addon), "Home", exceptId = null))
    }

    @Test
    fun uniqueNameIsNotFlagged() {
        assertNull(navidromeLabelTakenBy(all, "Cabin", exceptId = null))
    }
}

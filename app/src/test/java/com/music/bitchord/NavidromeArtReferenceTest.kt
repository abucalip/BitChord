package com.music.bitchord

import com.music.bitchord.data.sources.navidrome.NavidromeArt
import com.music.bitchord.data.sources.navidrome.NavidromeArtKeyer
import com.music.bitchord.data.sources.navidrome.NavidromeArtReferenceKeyer
import com.music.bitchord.data.sources.navidrome.parseNavidromeArtReference
import com.music.bitchord.data.sources.navidrome.toReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The cover reference a search row persists in place of an artwork URL. */
class NavidromeArtReferenceTest {
    @Test
    fun aReferenceRoundTripsToTheSameCover() {
        val art = NavidromeArt("cfg-1", "al-42_ab c/d", 150)
        assertEquals(art, parseNavidromeArtReference(art.toReference()))
    }

    @Test
    fun aReferenceNamesTheServerAndNeverALogin() {
        val text = NavidromeArt("cfg-1", "al-42", 150).toReference()
        assertFalse(text.contains("http"))
        assertFalse(text.contains("u="))
        assertFalse(text.contains("t="))
        assertFalse(text.contains("/rest/"))
    }

    @Test
    fun anythingThatIsNotAReferenceIsIgnored() {
        assertNull(parseNavidromeArtReference("https://server/rest/getCoverArt?id=1"))
        assertNull(parseNavidromeArtReference("navidrome-art:cfg-1/al-42"))
        assertNull(parseNavidromeArtReference("navidrome-art:cfg-1/al-42/x"))
        assertNull(parseNavidromeArtReference("navidrome-art:/al-42/150"))
        assertNull(parseNavidromeArtReference("navidrome-art:cfg-1//150"))
    }

    @Test
    fun aReferenceAndItsImageShareOneCacheKeyByServerAndReference() {
        val art = NavidromeArt("cfg-1", "al-42", 150)
        assertEquals("navidrome:cfg-1:al-42:150", NavidromeArtKeyer.keyOf(art))
        assertEquals(NavidromeArtKeyer.keyOf(art), NavidromeArtReferenceKeyer.referenceCacheKey(art.toReference()))
    }

    @Test
    fun otherStringsKeepTheirOwnKey() {
        assertEquals("https://x/y", NavidromeArtReferenceKeyer.referenceCacheKey("https://x/y"))
    }
}

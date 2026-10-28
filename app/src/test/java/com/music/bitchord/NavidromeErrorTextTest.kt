package com.music.bitchord

import com.music.bitchord.R
import com.music.bitchord.data.sources.navidrome.NavidromeException
import com.music.bitchord.ui.screens.messageRes
import org.junit.Assert.assertEquals
import org.junit.Test

class NavidromeErrorTextTest {
    @Test
    fun everyCategoryHasItsOwnExplanation() {
        val ids = listOf(
            NavidromeException.InvalidUrl().messageRes(),
            NavidromeException.Network("x").messageRes(),
            NavidromeException.Network("x", timedOut = true).messageRes(),
            NavidromeException.Tls().messageRes(),
            NavidromeException.Auth("x").messageRes(),
            NavidromeException.Unsupported("x").messageRes(),
            NavidromeException.RateLimited().messageRes(),
            NavidromeException.ServerError(500).messageRes(),
            NavidromeException.NotFound().messageRes(),
            NavidromeException.Malformed("x").messageRes(),
        )
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun timeoutAndUnreachableAreDistinct() {
        assertEquals(R.string.navidrome_err_timeout, NavidromeException.Network("x", timedOut = true).messageRes())
        assertEquals(R.string.navidrome_err_network, NavidromeException.Network("x").messageRes())
        assertEquals(R.string.navidrome_err_auth, NavidromeException.Auth("Wrong").messageRes())
    }

    @Test
    fun invalidUrlReusesTheExistingLine() {
        assertEquals(R.string.navidrome_invalid_url, NavidromeException.InvalidUrl().messageRes())
    }
}

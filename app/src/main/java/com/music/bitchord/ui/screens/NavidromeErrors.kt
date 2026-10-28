package com.music.bitchord.ui.screens

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.music.bitchord.R
import com.music.bitchord.data.sources.navidrome.NavidromeException

/** The string that explains a Navidrome failure to the person, in their language. */
@StringRes
internal fun NavidromeException.messageRes(): Int = when (this) {
    is NavidromeException.InvalidUrl -> R.string.navidrome_invalid_url
    is NavidromeException.Network -> if (timedOut) R.string.navidrome_err_timeout else R.string.navidrome_err_network
    is NavidromeException.Tls -> R.string.navidrome_err_tls
    is NavidromeException.Auth -> R.string.navidrome_err_auth
    is NavidromeException.Unsupported -> R.string.navidrome_err_unsupported
    is NavidromeException.RateLimited -> R.string.navidrome_err_rate_limited
    is NavidromeException.ServerError -> R.string.navidrome_err_server
    is NavidromeException.NotFound -> R.string.navidrome_err_not_found
    is NavidromeException.NotAllowed -> R.string.navidrome_err_not_allowed
    is NavidromeException.Malformed -> R.string.navidrome_err_malformed
}

/**
 * Localised text for [error]. A Navidrome failure gets its own explanation;
 * anything else falls back to its own message, or the generic unavailable line
 * when it has none. Nothing here is ever a URL or a credential.
 */
@Composable
internal fun navidromeErrorText(error: Throwable): String {
    if (error !is NavidromeException) {
        return error.message?.takeIf { it.isNotBlank() } ?: stringResource(R.string.navidrome_unavailable)
    }
    return if (error is NavidromeException.ServerError) {
        stringResource(error.messageRes(), error.code)
    } else {
        stringResource(error.messageRes())
    }
}

package com.music.bitchord.data.sources.navidrome

import com.music.bitchord.data.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * A client for one Navidrome server's Subsonic-compatible API.
 *
 * Owns everything that touches the wire: URL construction, token
 * authentication, response parsing, timeouts and error translation. Nothing
 * above it sees a password, a token or a salt, and nothing it returns to the
 * rest of the app carries one except [streamUrl], whose result is meant to be
 * handed straight to the player and never stored or logged.
 *
 * Authentication is the Subsonic token scheme: `t = md5(password + salt)` with
 * a fresh random `s` for every request. The password itself is never sent.
 *
 * @param rawBaseUrl the server's address as the user typed it, including any
 *   reverse-proxy path prefix. Normalised by [normalizeBase].
 * @param salts where salts come from; tests substitute a fixed one.
 */
class NavidromeClient(
    rawBaseUrl: String,
    private val username: String,
    private val password: String,
    private val http: OkHttpClient = defaultClient,
    private val salts: () -> String = ::randomSalt,
) {

    private val base: HttpUrl? = normalizeBase(rawBaseUrl)

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    /** Confirms the address, credentials and API version in one call. */
    suspend fun ping(): Result<NavidromePing> = call("ping").mapCatching { response ->
        NavidromePing(
            apiVersion = response.version,
            serverType = response.type,
            serverVersion = response.serverVersion,
        )
    }

    /** Songs matching [query], via `search3` (ID3-based, paged by [offset]). */
    suspend fun searchSongs(query: String, count: Int = 25, offset: Int = 0): Result<List<NavidromeSong>> =
        call(
            "search3",
            "query" to query,
            "songCount" to count.toString(),
            "songOffset" to offset.toString(),
            "artistCount" to "0",
            "albumCount" to "0",
        ).map { it.searchResult3?.song.orEmpty().filter { song -> song.id.isNotBlank() } }

    /**
     * The URL the player should open for [trackId].
     *
     * Subsonic has no header-based authentication, so the credentials travel as
     * query parameters. The salt is fresh on every call, so a URL built here is
     * not a reusable credential for anything but this stream — but it is still
     * a secret: never persist it, never log it, and build a new one for every
     * open rather than caching this one.
     *
     * @param maxKbps ask the server to transcode down to this, or null for the original.
     */
    fun streamUrl(trackId: String, maxKbps: Int? = null): Result<String> = runCatching {
        val url = endpoint("stream") ?: throw NavidromeException.InvalidUrl()
        url.newBuilder()
            .addQueryParameter("id", trackId)
            .apply {
                // `raw` is the documented way to ask for no transcoding.
                if (maxKbps == null) addQueryParameter("format", "raw")
                else addQueryParameter("maxBitRate", maxKbps.toString())
            }
            .build()
            .toString()
    }

    // ── Plumbing ──────────────────────────────────────────────────────────

    private fun endpoint(name: String): HttpUrl? =
        base?.newBuilder()
            ?.addPathSegments("rest/$name")
            ?.addQueryParameter("u", username)
            ?.apply {
                val salt = salts()
                addQueryParameter("t", token(password, salt))
                addQueryParameter("s", salt)
                addQueryParameter("v", API_VERSION)
                addQueryParameter("c", CLIENT_NAME)
                addQueryParameter("f", "json")
            }
            ?.build()

    private suspend fun call(name: String, vararg params: Pair<String, String>): Result<SubsonicResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = endpoint(name)?.newBuilder()
                    ?.apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
                    ?.build()
                    ?: throw NavidromeException.InvalidUrl()
                val request = Request.Builder().url(url).header("Accept", "application/json").build()
                val body = http.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful ->
                            response.body?.string()?.takeIf { it.isNotBlank() }
                                ?: throw NavidromeException.Malformed("Empty response")
                        response.code == 429 -> throw NavidromeException.RateLimited()
                        response.code in 500..599 -> throw NavidromeException.ServerError(response.code)
                        response.code == 404 ->
                            throw NavidromeException.Unsupported("No Subsonic API found at that address")
                        else -> throw NavidromeException.Unsupported("HTTP ${response.code}")
                    }
                }
                parse(body)
            }.recoverCatching { failure -> throw translate(failure) }
        }

    private fun parse(body: String): SubsonicResponse {
        val response = try {
            json.decodeFromString<SubsonicEnvelope>(body).response
        } catch (e: SerializationException) {
            // A web page or a proxy's error screen lands here; say so rather than quoting it.
            throw NavidromeException.Unsupported("That address did not answer like a Subsonic server")
        }
        if (response.status.equals("ok", ignoreCase = true)) return response
        val error = response.error
        throw when (error?.code) {
            40, 41, 42, 43, 44 -> NavidromeException.Auth(authReason(error.code))
            70 -> NavidromeException.NotFound()
            else -> NavidromeException.Unsupported("The server reported an error (code ${error?.code ?: "?"})")
        }
    }

    private fun authReason(code: Int) = when (code) {
        40 -> "Wrong username or password"
        41 -> "This server does not accept token authentication"
        42 -> "This server requires a different authentication mechanism"
        43 -> "Multiple authentication mechanisms were provided"
        else -> "The server rejected the credentials"
    }

    /** Maps whatever went wrong onto the categories the UI can explain, without leaking the URL. */
    private fun translate(failure: Throwable): Throwable = when (failure) {
        is CancellationException -> failure
        is NavidromeException -> failure
        is SSLException -> NavidromeException.Tls()
        is SocketTimeoutException -> NavidromeException.Network("The server took too long to answer")
        is IOException -> NavidromeException.Network("Could not reach the server")
        else -> NavidromeException.Malformed("Unexpected response")
    }

    companion object {
        /** The oldest Subsonic API version that has token auth and `search3`. */
        const val API_VERSION = "1.16.1"
        const val CLIENT_NAME = "BitChord"

        private val defaultClient: OkHttpClient by lazy {
            Http.client.newBuilder()
                .callTimeout(20, TimeUnit.SECONDS)
                .build()
        }

        private val random = SecureRandom()

        /** 16 random bytes as hex. Fresh for every request. */
        fun randomSalt(): String =
            ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

        /** `md5(password + salt)` as lowercase hex, per the Subsonic spec. */
        fun token(password: String, salt: String): String =
            MessageDigest.getInstance("MD5")
                .digest((password + salt).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /**
         * The server's base URL, or null when [raw] is not a usable http(s) address.
         *
         * Keeps any path prefix (a reverse proxy may host Navidrome under
         * `/music`), drops a trailing slash and an accidental trailing `/rest`,
         * and rejects credentials embedded in the URL.
         */
        fun normalizeBase(raw: String): HttpUrl? {
            val url = raw.trim().toHttpUrlOrNull() ?: return null
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            val segments = url.pathSegments.filter { it.isNotEmpty() }.toMutableList()
            if (segments.lastOrNull().equals("rest", ignoreCase = true)) segments.removeAt(segments.lastIndex)
            return url.newBuilder()
                .encodedPath("/")
                .query(null)
                .fragment(null)
                .apply { segments.forEach { addPathSegment(it) } }
                .build()
        }

        /** A log-safe form of [url]: scheme and host only. */
        fun redact(url: String): String =
            url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}/***" } ?: "***"
    }
}

/** Why a Navidrome call failed, in terms the UI can explain without secrets. */
sealed class NavidromeException(message: String) : Exception(message) {
    class InvalidUrl : NavidromeException("That is not a valid http or https address")
    class Network(message: String) : NavidromeException(message)
    class Tls : NavidromeException("A secure connection could not be established (check the certificate)")
    class Auth(message: String) : NavidromeException(message)
    class Unsupported(message: String) : NavidromeException(message)
    class RateLimited : NavidromeException("The server is rate limiting BitChord")
    class ServerError(val code: Int) : NavidromeException("The server had a problem (HTTP $code)")
    class NotFound : NavidromeException("The server does not have that track")
    class Malformed(message: String) : NavidromeException(message)
}

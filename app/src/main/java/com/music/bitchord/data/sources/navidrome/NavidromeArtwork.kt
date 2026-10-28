package com.music.bitchord.data.sources.navidrome

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.music.bitchord.data.Http
import com.music.bitchord.data.sources.SourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A cover on a Navidrome server, as an image model.
 *
 * Deliberately not a URL. Subsonic cover-art URLs carry the login as query
 * parameters, so a URL used as the model would be written into Coil's cache
 * keys and into any state that held it. This names the picture instead —
 * which server, which server-side reference — and [NavidromeArtFetcher] builds
 * the authenticated URL at the moment it fetches.
 */
data class NavidromeArt(val configId: String, val ref: String, val sizePx: Int = 300)

/** Prefix of a cover reference stored in a song's thumbnail. */
const val NAVIDROME_ART_SCHEME = "navidrome-art:"

/**
 * This cover as a reference a [Song][com.music.bitchord.data.model.Song] can
 * persist: `navidrome-art:<configId>/<ref>/<size>`. It names the server
 * (by its config id) and the server-side cover, and nothing that logs in, so it
 * is safe in queues and history. [NavidromeArtFetcher] turns it back into the
 * image when it is shown.
 */
fun NavidromeArt.toReference(): String =
    "$NAVIDROME_ART_SCHEME$configId/${URLEncoder.encode(ref, "UTF-8")}/$sizePx"

/** The cover a reference from [toReference] names, or null for anything else. */
fun parseNavidromeArtReference(text: String): NavidromeArt? {
    if (!text.startsWith(NAVIDROME_ART_SCHEME)) return null
    val parts = text.removePrefix(NAVIDROME_ART_SCHEME).split('/')
    if (parts.size != 3) return null
    val configId = parts[0]
    val ref = URLDecoder.decode(parts[1], "UTF-8")
    val size = parts[2].toIntOrNull() ?: return null
    if (configId.isBlank() || ref.isBlank() || size <= 0) return null
    return NavidromeArt(configId, ref, size)
}

/**
 * Keys cached images on the server and reference, never on a URL: two servers
 * can hand out the same reference, and a URL's salt changes on every request.
 */
class NavidromeArtKeyer : Keyer<NavidromeArt> {
    override fun key(data: NavidromeArt, options: Options): String = keyOf(data)

    companion object {
        fun keyOf(art: NavidromeArt): String = "navidrome:${art.configId}:${art.ref}:${art.sizePx}"
    }
}

class NavidromeArtFetcher private constructor(private val art: NavidromeArt) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val library = SourceRegistry.browsable(art.configId) ?: return@withContext null
        val url = library.artworkUrl(art.ref, art.sizePx) ?: return@withContext null
        val response = Http.client.newCall(Request.Builder().url(url).build()).execute()
        // Closed by Coil with the ImageSource; failures close here.
        val body = response.body
        if (!response.isSuccessful || body == null) {
            response.close()
            return@withContext null
        }
        SourceFetchResult(
            source = ImageSource(body.source(), okio.FileSystem.SYSTEM),
            mimeType = body.contentType()?.toString()?.takeIf { it.startsWith("image/") },
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory : Fetcher.Factory<NavidromeArt> {
        override fun create(data: NavidromeArt, options: Options, imageLoader: ImageLoader): Fetcher =
            NavidromeArtFetcher(data)
    }

    /** Serves the string references that [toReference] makes; anything else falls through to Coil's own fetchers. */
    class ReferenceFactory : Fetcher.Factory<String> {
        override fun create(data: String, options: Options, imageLoader: ImageLoader): Fetcher? =
            parseNavidromeArtReference(data)?.let { NavidromeArtFetcher(it) }
    }
}

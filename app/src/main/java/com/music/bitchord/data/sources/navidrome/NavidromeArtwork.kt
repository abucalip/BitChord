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
}

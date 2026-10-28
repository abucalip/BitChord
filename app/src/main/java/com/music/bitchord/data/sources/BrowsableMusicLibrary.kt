package com.music.bitchord.data.sources

import com.music.bitchord.data.model.Song

/**
 * A source that can be browsed as a library: artists, albums, tracks.
 *
 * Separate from [MusicSource] on purpose. That interface is "search and
 * stream"; browsing is something only a source holding the user's own
 * collection can do, and putting it on [MusicSource] would give every other
 * implementation methods it could only stub. A caller that wants to browse
 * asks `source as? BrowsableMusicLibrary`.
 *
 * Every call returns a [Result]: a failure is a value the screen can render as
 * a retryable state, and an empty list is a real answer (an empty library, an
 * artist with no albums), not an error. Ids are opaque and meaningful only to
 * the source that issued them; pair them with [MusicSource.configId] anywhere
 * they are kept.
 */
interface BrowsableMusicLibrary {

    /** All artists, A-Z. The listing is lightweight (no tracks); page over it with [LibraryPage.of]. */
    suspend fun artists(): Result<List<LibraryArtist>>

    /** A page of albums, A-Z by title, starting at [offset]. */
    suspend fun albums(offset: Int = 0, limit: Int = DEFAULT_PAGE): Result<LibraryPage<LibraryAlbum>>

    /** The albums of one artist. */
    suspend fun artistAlbums(artistId: String): Result<List<LibraryAlbum>>

    /** The tracks of one album in play order, as [Song]s that play back through this source. */
    suspend fun albumTracks(albumId: String): Result<List<Song>>

    /**
     * An authenticated URL for [LibraryArtist.artworkRef] or [LibraryAlbum.artworkRef],
     * or null if the source can't build one.
     *
     * Credentials may travel in the URL: pass it straight to the image loader,
     * never persist or log it, and key any image cache on
     * ([MusicSource.configId], ref) instead.
     */
    fun artworkUrl(artworkRef: String, sizePx: Int): String?

    companion object {
        const val DEFAULT_PAGE = 50
    }
}

data class LibraryArtist(
    val id: String,
    val name: String,
    val albumCount: Int?,
    val artworkRef: String?,
)

data class LibraryAlbum(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String?,
    val year: Int?,
    val trackCount: Int?,
    val artworkRef: String?,
)

/**
 * One page of a listing. [nextOffset] is where the following page starts, or
 * null when this one came back short and so was the last.
 */
data class LibraryPage<T>(val items: List<T>, val nextOffset: Int?) {
    companion object {
        /** A slice of an in-memory [all] as a page, for listings the server does not page. */
        fun <T> of(all: List<T>, offset: Int, limit: Int): LibraryPage<T> {
            val from = offset.coerceIn(0, all.size)
            val to = (from + limit).coerceAtMost(all.size)
            return LibraryPage(all.subList(from, to), to.takeIf { it < all.size })
        }
    }
}

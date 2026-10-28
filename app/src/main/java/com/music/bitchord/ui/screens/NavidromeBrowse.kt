package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.playingRowMatcher
import com.music.bitchord.data.sources.BrowsableMusicLibrary
import com.music.bitchord.data.sources.LibraryAlbum
import com.music.bitchord.data.sources.LibraryArtist
import com.music.bitchord.data.sources.LibraryPage
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.navidrome.NavidromeArt
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.PlayingAccent
import com.music.bitchord.ui.components.ROW_DIVIDER_INSET
import com.music.bitchord.ui.components.SongRow
import com.music.bitchord.ui.components.songListSkeleton

/** Rows loaded at a time; the next batch is requested as the list nears its end. */
private const val PAGE = 50

/** How many rows from the end of the list the next batch is asked for. */
private const val PREFETCH = 8

/**
 * Albums kept in saved state. A font-scale change recreates the Activity (only
 * orientation and size are handled in place), and the loaded albums are the
 * part that is otherwise lost; the scroll position is restored against them.
 * Capped so a very large library never has to fit in a saved-state bundle.
 */
private const val SAVED_ALBUMS = 200

private fun LibraryPage<LibraryAlbum>.capped(): LibraryPage<LibraryAlbum> =
    if (items.size <= SAVED_ALBUMS) this else LibraryPage(items.take(SAVED_ALBUMS), SAVED_ALBUMS)

/** Flat, bundle-safe form of an album page: the next offset, then seven fields per album. */
internal val albumPageSaver: Saver<LibraryPage<LibraryAlbum>, Any> = listSaver(
    save = { page ->
        buildList {
            add(page.nextOffset ?: -1)
            page.items.forEach { a ->
                add(a.id)
                add(a.title)
                add(a.artist)
                add(a.artistId.orEmpty())
                add(a.year ?: -1)
                add(a.trackCount ?: -1)
                add(a.artworkRef.orEmpty())
            }
        }
    },
    restore = { saved ->
        runCatching {
            LibraryPage(
                items = saved.drop(1).chunked(7).map { f ->
                    LibraryAlbum(
                        id = f[0] as String,
                        title = f[1] as String,
                        artist = f[2] as String,
                        artistId = (f[3] as String).ifEmpty { null },
                        year = (f[4] as Int).takeIf { it >= 0 },
                        trackCount = (f[5] as Int).takeIf { it >= 0 },
                        artworkRef = (f[6] as String).ifEmpty { null },
                    )
                },
                nextOffset = (saved[0] as Int).takeIf { it >= 0 },
            )
        }.getOrNull()
    },
)

/**
 * The browsing contract of the server named by [configId], or null when it is
 * gone, disabled or not browsable.
 *
 * Re-read when the source list changes, so a server removed, disabled or
 * edited while its pages are open is noticed instead of browsed from a stale
 * instance. An untouched source keeps its instance, so the paging state keyed
 * on it survives unrelated changes.
 */
@Composable
private fun rememberLibrary(configId: String): BrowsableMusicLibrary? {
    val configs by SourceRegistry.configs.collectAsStateWithLifecycle()
    return remember(configs, configId) { SourceRegistry.browsable(configId) }
}

/**
 * A Navidrome server's library: Artists and Albums.
 *
 * Artists come as one lightweight listing shown a page at a time; albums are
 * genuinely paged by the server, so a large library is never loaded whole.
 * Every state a network list can be in has its own face: loading skeletons, an
 * empty library, an error with Try Again (also at the foot of a list when only
 * the next batch failed), and a server that has gone away.
 */
@Composable
internal fun NavidromeLibraryScreen(
    configId: String,
    onOpenArtist: (LibraryArtist) -> Unit,
    onOpenAlbum: (LibraryAlbum) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val library = rememberLibrary(configId)
    if (library == null) {
        Unavailable(contentPadding, modifier)
        return
    }
    var tab by rememberSaveable(configId) { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val artists = remember(library) {
        // The server lists artists whole; fetched once and paged over in memory.
        var all: List<LibraryArtist>? = null
        PagedList<LibraryArtist> { offset ->
            all?.let { Result.success(LibraryPage.of(it, offset, PAGE)) }
                ?: library.artists().map { loaded ->
                    all = loaded
                    LibraryPage.of(loaded, offset, PAGE)
                }
        }
    }
    var albumPage by rememberSaveable(configId, stateSaver = albumPageSaver) {
        mutableStateOf(LibraryPage<LibraryAlbum>(emptyList(), 0))
    }
    val albums = remember(library) {
        PagedList(restored = albumPage.takeIf { it.items.isNotEmpty() }) { offset -> library.albums(offset, PAGE) }
    }
    LaunchedEffect(albums) {
        snapshotFlow { albums.items }.collect { albumPage = albums.snapshot().capped() }
    }
    val current = if (tab == 0) artists else albums

    // Starts the visible list, then extends it whenever the end comes near.
    LaunchedEffect(current) {
        current.loadNext()
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            (last >= info.totalItemsCount - PREFETCH) to current.items.size
        }.collect { (near, _) -> if (near) current.loadNext() }
    }

    LazyColumn(state = listState, modifier = modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(key = "tabs") {
            SegmentedControl(
                options = listOf(stringResource(R.string.artists), stringResource(R.string.albums)),
                selectedIndex = tab,
                onSelect = { tab = it },
                modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
            )
        }
        if (tab == 0) {
            items(artists.items.size, key = { "artist:${artists.items[it].id}" }) { index ->
                val artist = artists.items[index]
                BrowseRow(
                    title = artist.name,
                    subtitle = null,
                    art = artist.artworkRef?.let { NavidromeArt(configId, it, ART_ROW_PX) },
                    placeholder = Icons.Rounded.Person,
                    circular = true,
                    onClick = { onOpenArtist(artist) },
                )
                if (index < artists.items.lastIndex) RowRule()
            }
        } else {
            items(albums.items.size, key = { "album:${albums.items[it].id}" }) { index ->
                val album = albums.items[index]
                AlbumRow(configId, album) { onOpenAlbum(album) }
                if (index < albums.items.lastIndex) RowRule()
            }
        }
        item(key = "state:$tab") {
            when {
                current.error != null -> Failed(
                    message = current.error.orEmpty(),
                    compact = current.items.isNotEmpty(),
                ) { scope.launch { current.retry() } }
                current.loading && current.items.isEmpty() -> Skeletons()
                current.loading -> Spinner()
                current.started && current.exhausted && current.items.isEmpty() ->
                    MessageState(stringResource(R.string.navidrome_empty))
            }
        }
    }
}

/** One artist's albums. */
@Composable
internal fun NavidromeArtistScreen(
    configId: String,
    artistId: String,
    onOpenAlbum: (LibraryAlbum) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val library = rememberLibrary(configId)
    if (library == null) {
        Unavailable(contentPadding, modifier)
        return
    }
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<UiState<List<LibraryAlbum>>>(UiState.Loading) }
    LaunchedEffect(library, artistId, attempt) {
        state = UiState.Loading
        state = library.artistAlbums(artistId).fold(
            onSuccess = { UiState.Success(it) },
            onFailure = { UiState.Error(it.message ?: "") },
        )
    }
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = contentPadding) {
        when (val s = state) {
            UiState.Loading -> item { Skeletons() }
            is UiState.Error -> item { Failed(s.message) { attempt++ } }
            is UiState.Success -> if (s.data.isEmpty()) {
                item { MessageState(stringResource(R.string.navidrome_empty)) }
            } else {
                items(s.data.size, key = { "album:${s.data[it].id}" }) { index ->
                    AlbumRow(configId, s.data[index]) { onOpenAlbum(s.data[index]) }
                    if (index < s.data.lastIndex) RowRule()
                }
            }
        }
    }
}

/** One album: cover, Play and Shuffle, and its tracks in order. */
@Composable
internal fun NavidromeAlbumScreen(
    configId: String,
    albumId: String,
    title: String,
    artist: String,
    artworkRef: String?,
    onPlay: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    contentPadding: PaddingValues,
    currentSong: Song?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val library = rememberLibrary(configId)
    if (library == null) {
        Unavailable(contentPadding, modifier)
        return
    }
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<UiState<List<Song>>>(UiState.Loading) }
    LaunchedEffect(library, albumId, attempt) {
        state = UiState.Loading
        state = library.albumTracks(albumId).fold(
            onSuccess = { UiState.Success(it) },
            onFailure = { UiState.Error(it.message ?: "") },
        )
    }
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(key = "header") {
            AlbumHeader(
                title = title,
                artist = artist,
                art = artworkRef?.let { NavidromeArt(configId, it, ART_HEADER_PX) },
                playable = (state as? UiState.Success)?.data?.isNotEmpty() == true,
                onPlay = { (state as? UiState.Success)?.data?.let { onPlay(it, 0) } },
                onShuffle = { (state as? UiState.Success)?.data?.shuffled()?.let { onPlay(it, 0) } },
            )
        }
        when (val s = state) {
            UiState.Loading -> songListSkeleton(count = 8, keyPrefix = "skeleton:navidrome-album")
            is UiState.Error -> item { Failed(s.message) { attempt++ } }
            is UiState.Success -> if (s.data.isEmpty()) {
                item { MessageState(stringResource(R.string.navidrome_empty)) }
            } else {
                val songs = s.data
                val isCurrentRow = playingRowMatcher(songs, currentSong)
                items(songs.size, key = { "${songs[it].videoId}:$it" }) { index ->
                    val song = songs[index]
                    SongRow(
                        song = song,
                        onClick = { onPlay(songs, index) },
                        onLongPress = { onSongLongPress(song) },
                        onSwipeToQueue = { onSongSwipe(song) },
                        trackNumber = song.trackNumber ?: (index + 1),
                        isCurrent = isCurrentRow(song),
                        isPlaying = isCurrentRow(song) && isPlaying,
                        searchPlayingStyle = true,
                        activeTint = PlayingAccent,
                    )
                    if (index < songs.lastIndex) RowRule()
                }
            }
        }
    }
}

// ── Pieces ──────────────────────────────────────────────────────────────

private const val ART_ROW_PX = 150
private const val ART_HEADER_PX = 600

@Composable
private fun AlbumRow(configId: String, album: LibraryAlbum, onClick: () -> Unit) {
    BrowseRow(
        title = album.title,
        subtitle = listOfNotNull(album.artist.ifBlank { null }, album.year?.toString()).joinToString(" · ")
            .ifBlank { null },
        art = album.artworkRef?.let { NavidromeArt(configId, it, ART_ROW_PX) },
        placeholder = Icons.Rounded.Album,
        circular = false,
        onClick = onClick,
    )
}

@Composable
private fun BrowseRow(
    title: String,
    subtitle: String?,
    art: NavidromeArt?,
    placeholder: androidx.compose.ui.graphics.vector.ImageVector,
    circular: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(art, placeholder, Modifier.size(48.dp), 24.dp, circular)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A cover over a flat tile, so a missing or failed image reads as an empty sleeve rather than a hole. */
@Composable
private fun Cover(
    art: NavidromeArt?,
    placeholder: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier,
    iconSize: androidx.compose.ui.unit.Dp,
    circular: Boolean,
) {
    val shape = if (circular) RoundedCornerShape(percent = 50) else RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = placeholder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(iconSize),
        )
        if (art != null) {
            AsyncImage(
                model = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun AlbumHeader(
    title: String,
    artist: String,
    art: NavidromeArt?,
    playable: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Up to 220dp, but never wider than the screen allows: 70% of the width on a narrow phone.
        Cover(
            art,
            Icons.Rounded.Album,
            Modifier.fillMaxWidth(0.7f).widthIn(max = 220.dp).aspectRatio(1f),
            48.dp,
            circular = false,
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (artist.isNotBlank()) {
            Text(
                text = artist,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.size(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onPlay, enabled = playable) { Text(stringResource(R.string.play)) }
            FilledTonalButton(onClick = onShuffle, enabled = playable) { Text(stringResource(R.string.shuffle)) }
        }
    }
}

@Composable
private fun RowRule() {
    HorizontalDivider(
        modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

@Composable
private fun Skeletons() {
    Column {
        repeat(8) { index ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth(if (index % 2 == 0) 0.6f else 0.45f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
        }
    }
}

@Composable
private fun Spinner() {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun Failed(message: String, compact: Boolean = false, onRetry: () -> Unit) {
    MessageState(
        message = message.ifBlank { stringResource(R.string.navidrome_unavailable) },
        actionLabel = stringResource(R.string.try_again),
        onAction = onRetry,
        modifier = if (compact) Modifier.padding(vertical = 0.dp) else Modifier,
    )
}

@Composable
private fun Unavailable(contentPadding: PaddingValues, modifier: Modifier) {
    Box(modifier.fillMaxSize().padding(contentPadding)) {
        MessageState(stringResource(R.string.navidrome_unavailable))
    }
}

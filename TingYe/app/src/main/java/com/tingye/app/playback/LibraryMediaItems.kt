package com.tingye.app.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.tingye.app.data.Book
import com.tingye.app.data.Chapter

fun libraryMediaItems(book: Book, chapters: List<Chapter>): List<MediaItem> = chapters.map { chapter ->
    MediaItem.Builder().setUri(chapter.uri).setMediaId("${book.id}:${chapter.id}")
        .setMediaMetadata(MediaMetadata.Builder().setTitle(book.title).setArtist(chapter.title)
            .setAlbumTitle(book.title).setDisplayTitle(book.title).setSubtitle(chapter.title)
            .setIsPlayable(true).setIsBrowsable(false).setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
            .setArtworkUri(book.coverUri?.let(Uri::parse)).build()).build()
}

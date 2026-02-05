package com.theveloper.pixelplay.data.playlist

import android.content.Context
import android.net.Uri
import com.theveloper.pixelplay.data.model.Playlist
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.repository.MusicRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class M3uManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository
) {

    suspend fun parseM3u(uri: Uri): Pair<String, List<String>> {
        val songIds = mutableListOf<String>()
        var playlistName = "Imported Playlist"

        // Pre-load all songs once for efficient lookup
        val allSongs = musicRepository.getAudioFiles().first()

        // Build normalized lookup maps for robust matching
        val songsByNormalizedPath = allSongs.associateBy { normalizeM3uPath(it.path) }
        val songsByFileName = allSongs.groupBy { it.path.substringAfterLast('/').lowercase() }

        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val trimmedLine = line?.trim() ?: continue
                    if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) continue

                    val normalizedLine = normalizeM3uPath(trimmedLine)
                    val directMatch = songsByNormalizedPath[normalizedLine]
                    if (directMatch != null) {
                        songIds.add(directMatch.id)
                        continue
                    }

                    // Fallback by filename only when unique to avoid wrong matches.
                    val fileName = normalizedLine.substringAfterLast('/').lowercase()
                    val candidates = songsByFileName[fileName].orEmpty()
                    if (candidates.size == 1) {
                        songIds.add(candidates.first().id)
                    }
                }
            }
        }

        // Deduplicate to avoid duplicate-key crashes in playlist UI.
        val uniqueSongIds = songIds.distinct()

        // Try to get the filename as playlist name
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                playlistName = cursor.getString(nameIndex).removeSuffix(".m3u").removeSuffix(".m3u8")
            }
        }

        return Pair(playlistName, uniqueSongIds)
    }

    fun generateM3u(playlist: Playlist, songs: List<Song>): String {
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        for (song in songs) {
            sb.append("#EXTINF:${song.duration / 1000},${song.artist} - ${song.title}\n")
            sb.append("${song.path}\n")
        }
        return sb.toString()
    }

    private fun normalizeM3uPath(raw: String): String {
        val decoded = runCatching { URLDecoder.decode(raw, StandardCharsets.UTF_8.name()) }.getOrDefault(raw)
        return decoded
            .removePrefix("file://")
            .replace('\\', '/')
            .trim()
    }
}

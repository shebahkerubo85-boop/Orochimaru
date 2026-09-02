package tachiyomi.domain.source.anime.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.source.anime.model.AnimeSource
import tachiyomi.domain.source.anime.model.AnimeSourceWithCount

interface AnimeSourceRepository {

    fun getAnimeSources(): Flow<List<AnimeSource>>

    fun getOnlineAnimeSources(): Flow<List<AnimeSource>>

    fun getAnimeSourcesWithFavoriteCount(): Flow<List<Pair<AnimeSource, Long>>>

    fun getSourcesWithNonLibraryAnime(): Flow<List<AnimeSourceWithCount>>

}

package com.hexated

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty

data class ApiResponse(
    val data: List<ApiItem> = emptyList(),
    val pagination: Pagination? = null,
    val meta: Meta? = null
)

data class ApiItem(
    val id: String? = null,
    val title: String? = null,
    val slug: String? = null,

    @JsonAlias("poster_path", "posterPath", "poster", "image", "thumbnail")
    val posterPath: String? = null,
    @JsonAlias("backdrop_path", "backdropPath", "backdrop")
    val backdropPath: String? = null,

    val releaseDate: String? = null,
    val firstAirDate: String? = null,

    val voteAverage: String? = null,
    val viewCount: Any? = null,

    val quality: String? = null,
    val country: String? = null,
    val runtime: Int? = null,

    val createdAt: String? = null,
    val numberOfSeasons: Int? = null,
    val numberOfEpisodes: Int? = null,

    val contentType: String? = null,

    val commentCount: Int? = null,

    val originalLanguage: String? = null,
    val popularity: Any? = null,
    val genres: List<APIGenre>? = null,
    val hasVideo: Boolean? = null,
    val isPublished: Boolean? = null
)

data class APIGenre(
    val id: String? = null,
    val name: String? = null,
    val slug: String? = null
)

data class Pagination(
    val page: Int? = null,
    val limit: Int? = null,
    val total: Int? = null,
    val totalPages: Int? = null
)

data class Meta(
    val genre: String? = null,
    val country: String? = null,
    val year: String? = null,
    val network: String? = null,
    val sort: String? = null
)

data class DetailResponse(
    val id: String? = null,
    val title: String? = null,
    val slug: String? = null,
    @JsonAlias("imdb_id", "imdbId")
    val imdbId: String? = null,
    @JsonAlias("tmdb_id", "tmdbId")
    val tmdbId: Any? = null,
    val overview: String? = null,
    val tagline: String? = null,
    @JsonAlias("poster_path", "posterPath", "poster", "image")
    val posterPath: String? = null,
    @JsonAlias("backdrop_path", "backdropPath", "backdrop")
    val backdropPath: String? = null,
    @JsonAlias("logo_path", "logoPath", "logo")
    val logoPath: String? = null,
    val backdrops: List<String>? = null,
    @JsonAlias("release_date", "releaseDate")
    val releaseDate: String? = null,
    @JsonAlias("first_air_date", "firstAirDate")
    val firstAirDate: String? = null,
    val runtime: Int? = null,
    @JsonAlias("vote_average", "voteAverage")
    val voteAverage: Any? = null,
    val popularity: Any? = null,
    @JsonAlias("original_language", "originalLanguage")
    val originalLanguage: String? = null,
    val country: String? = null,
    val status: String? = null,
    @JsonAlias("trailer_url", "trailerUrl")
    val trailerUrl: String? = null,
    val quality: String? = null,
    val director: String? = null,
    val genres: List<Genre>? = null,
    val cast: List<Cast>? = null,
    val seasons: List<Season>? = null,
    @JsonAlias("default_season", "defaultSeason", "first_season", "firstSeason")
    val firstSeason: Season? = null,
    @JsonAlias("view_count", "viewCount")
    val viewCount: Any? = null,
    @JsonAlias("is_published", "isPublished")
    val isPublished: Boolean? = null
)

data class Genre(
    val id: String? = null,
    val name: String? = null,
    val slug: String? = null
)

data class Cast(
    val id: String? = null,
    val name: String? = null,
    val character: String? = null,
    @JsonAlias("profile_path", "profilePath")
    val profilePath: String? = null
)

data class Season(
    val id: String? = null,
    @JsonAlias("season_number", "seasonNumber")
    val seasonNumber: Int? = null,
    val name: String? = null,
    @JsonAlias("poster_path", "posterPath")
    val posterPath: String? = null,
    val episodes: List<IdlixEpisode>? = null
)

data class IdlixEpisode(
    val id: String? = null,
    @JsonAlias("episode_number", "episodeNumber")
    val episodeNumber: Int? = null,
    val name: String? = null,
    val overview: String? = null,
    @JsonAlias("still_path", "stillPath")
    val stillPath: String? = null,
    @JsonAlias("air_date", "airDate")
    val airDate: String? = null,
    val runtime: Int? = null,
    @JsonAlias("vote_average", "voteAverage")
    val voteAverage: Any? = null
)

data class SeasonWrapper(
    val season: Season? = null
)

data class SearchApiResponse(
    val results: List<SearchApiResult> = emptyList(),
    val total: Long = 0,
)

data class SearchApiResult(
    val id: String,
    val contentType: String,
    val title: String,
    val originalTitle: String? = null,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    val originalLanguage: String? = null,
    val voteAverage: Double? = null,
    val viewCount: Long? = null,
    val popularity: Double? = null,
    @JsonAlias("poster_path", "posterPath", "poster", "image", "thumbnail")
    val posterPath: String? = null,
    @JsonAlias("backdrop_path", "backdropPath", "backdrop")
    val backdropPath: String? = null,
    val slug: String,
    val firstAirDate: String? = null,
    val numberOfSeasons: Long? = null,
    val releaseDate: String? = null,
    val quality: String? = null,
)

data class LoadData(
    val id: String,
    val type: String
)

package com.hexated

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

    val posterPath: String? = null,
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
    @JsonProperty("imdb_id")
    val imdbId: String? = null,
    @JsonProperty("tmdb_id")
    val tmdbId: String? = null,
    val overview: String? = null,
    val tagline: String? = null,
    @JsonProperty("poster_path")
    val posterPath: String? = null,
    @JsonProperty("backdrop_path")
    val backdropPath: String? = null,
    @JsonProperty("logo_path")
    val logoPath: String? = null,
    val backdrops: List<String>? = null,
    @JsonProperty("release_date")
    val releaseDate: String? = null,
    @JsonProperty("first_air_date")
    val firstAirDate: String? = null,
    val runtime: Int? = null,
    @JsonProperty("vote_average")
    val voteAverage: Any? = null,
    val popularity: Any? = null,
    @JsonProperty("original_language")
    val originalLanguage: String? = null,
    val country: String? = null,
    val status: String? = null,
    @JsonProperty("trailer_url")
    val trailerUrl: String? = null,
    val quality: String? = null,
    val director: String? = null,
    val genres: List<Genre>? = null,
    val cast: List<Cast>? = null,
    val seasons: List<Season>? = null,
    @JsonProperty("first_season")
    val firstSeason: Season? = null,
    @JsonProperty("view_count")
    val viewCount: Any? = null,
    @JsonProperty("is_published")
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
    val profilePath: String? = null
)

data class Season(
    val id: String? = null,
    val seasonNumber: Int? = null,
    val name: String? = null,
    val posterPath: String? = null,
    val episodes: List<IdlixEpisode>? = null
)

data class IdlixEpisode(
    val id: String? = null,
    val episodeNumber: Int? = null,
    val name: String? = null,
    val overview: String? = null,
    val stillPath: String? = null,
    val airDate: String? = null,
    val runtime: Int? = null,
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
    val posterPath: String? = null,
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

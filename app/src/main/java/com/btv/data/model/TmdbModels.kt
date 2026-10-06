package com.btv.data.model

import kotlinx.serialization.Serializable

@Serializable
data class TmdbSearchResponse(val results: List<TmdbSearchResult> = emptyList())

@Serializable
data class TmdbSearchResult(val id: Int)

@Serializable
data class TmdbCreditsResponse(val cast: List<TmdbCastMember> = emptyList())

@Serializable
data class TmdbCastMember(val name: String, val profile_path: String? = null)

/** One cast member with a resolved (or absent) photo URL - what the UI actually renders. */
data class TmdbCastPerson(val name: String, val photoUrl: String?)

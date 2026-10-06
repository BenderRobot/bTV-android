package com.btv.data.api

import com.btv.data.model.TmdbCreditsResponse
import com.btv.data.model.TmdbSearchResponse
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface TmdbApi {
    @GET("search/{mediaType}")
    suspend fun search(
        @Path("mediaType") mediaType: String,
        @Query("query") query: String,
        @Query("language") language: String = "fr-FR",
        @Query("year") year: String? = null,
        @Query("first_air_date_year") firstAirDateYear: String? = null
    ): TmdbSearchResponse

    @GET("{mediaType}/{id}/credits")
    suspend fun credits(
        @Path("mediaType") mediaType: String,
        @Path("id") id: Int,
        @Query("language") language: String = "fr-FR"
    ): TmdbCreditsResponse
}

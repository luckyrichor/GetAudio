package com.getaudio.app.data.remote

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface GetAudioApi {

    @POST("extract")
    suspend fun extract(@Body body: ExtractRequest): ExtractResponse

    @GET("tracks")
    suspend fun tracks(
        @Query("q") query: String? = null,
        @Query("platform") platform: String? = null,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
    ): List<TrackDto>

    @GET("sync")
    suspend fun sync(
        @Query("cursor") cursor: Long,
        @Query("limit") limit: Int = 200,
    ): SyncResponse

    @POST("login/qr")
    suspend fun loginStart(@Body body: LoginStartRequest): LoginStartResponse

    @GET("login/qr/{sessionId}")
    suspend fun loginPoll(@Path("sessionId") sessionId: String): LoginPollResponse

    @POST("login/cookies")
    suspend fun importCookies(@Body body: CookieImportRequest): Map<String, String>

    @GET("credentials/{userId}")
    suspend fun credentials(@Path("userId") userId: String): List<CredentialDto>

    @DELETE("credentials/{userId}/{platform}")
    suspend fun revoke(
        @Path("userId") userId: String,
        @Path("platform") platform: String,
    ): Map<String, Boolean>
}

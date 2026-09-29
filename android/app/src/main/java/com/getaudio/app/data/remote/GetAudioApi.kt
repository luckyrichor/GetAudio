package com.getaudio.app.data.remote

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface GetAudioApi {

    @POST("agent/chat")
    suspend fun agentChat(@Body body: AgentChatRequest): AgentChatResponse

    @GET("agent/memories/{userId}")
    suspend fun agentMemories(@Path("userId") userId: String): List<AgentMemoryOut>

    @POST("agent/memories")
    suspend fun agentMemoryCreate(@Body body: AgentMemoryRequest): AgentMemoryOut

    @PUT("agent/memories/{userId}/{memoryId}")
    suspend fun agentMemoryUpdate(
        @Path("userId") userId: String,
        @Path("memoryId") memoryId: Long,
        @Body body: AgentMemoryUpdate,
    ): AgentMemoryOut

    @DELETE("agent/memories/{userId}/{memoryId}")
    suspend fun agentMemoryDelete(
        @Path("userId") userId: String,
        @Path("memoryId") memoryId: Long,
    ): Map<String, Boolean>

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

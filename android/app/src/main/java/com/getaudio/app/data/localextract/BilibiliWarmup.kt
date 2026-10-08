package com.getaudio.app.data.localextract

import java.util.concurrent.CancellationException

/** Optional cookie priming must not veto the authoritative metadata request. */
internal suspend fun <T> withOptionalBilibiliWarmup(
    warmup: suspend () -> Unit,
    onFailure: suspend (Exception) -> Unit,
    required: suspend () -> T,
): T {
    try { warmup() }
    catch (e: CancellationException) { throw e }
    catch (e: Exception) { onFailure(e) }
    return required()
}

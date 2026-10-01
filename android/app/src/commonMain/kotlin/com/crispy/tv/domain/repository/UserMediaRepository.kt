package com.crispy.tv.domain.repository

import com.crispy.tv.player.CanonicalContinueWatchingResult
import com.crispy.tv.player.CanonicalWatchStateSnapshot
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.player.WatchHistoryRequest
import com.crispy.tv.player.WatchHistoryResult
import com.crispy.tv.player.WatchProgressSnapshot

interface UserMediaRepository {
    suspend fun getCanonicalWatchState(identity: PlaybackIdentity): CanonicalWatchStateSnapshot?

    suspend fun getTitleWatchState(
        itemId: String,
        contentType: MetadataLabMediaType,
    ): CanonicalWatchStateSnapshot?

    /**
     * `nowMs` has no default, and that is the same defect `:android:player` already fixed.
     *
     * `WatchHistoryService.getCanonicalContinueWatching` in `:player` is the same method with
     * the same parameter shape, and it used to default `nowMs` to `System.currentTimeMillis()`.
     * That is the one expression that made `:player` fail `compileKotlinLinuxX64` when the
     * module became multiplatform, and its KDoc there explains why the import-based purity
     * gate passed it: `System` is in `java.lang` and needs no import on the JVM, so no check
     * that reads import lines can see it. The same file, the same method name, the same
     * parameter — and `:app` has no `linuxX64` target to catch it, because Compose Multiplatform
     * publishes no Linux artifacts, so `compileKotlinIosArm64` is the gate that sees it.
     *
     * `limit` loses its default for the same reason and not only that one: a default argument
     * that reads a clock hides the read from the caller, which is the opposite of the rest of
     * this repository, where every timestamp is passed in so behaviour is a function of its
     * inputs. Both in-repo callers already passed both arguments explicitly —
     * `WatchCtaResolver` with `limit = 50, nowMs = nowMs` and `DefaultUserMediaRepository`
     * forwarding them — so removing the defaults changed no call site. The defect was the
     * default, not the parameter.
     */
    suspend fun getCanonicalContinueWatching(
        limit: Int,
        nowMs: Long,
    ): CanonicalContinueWatchingResult

    suspend fun getLocalWatchProgress(identity: PlaybackIdentity): WatchProgressSnapshot?

    suspend fun markWatched(
        request: WatchHistoryRequest,
    ): WatchHistoryResult

    suspend fun unmarkWatched(
        request: WatchHistoryRequest,
    ): WatchHistoryResult

    suspend fun setInWatchlist(
        request: WatchHistoryRequest,
        inWatchlist: Boolean,
    ): WatchHistoryResult

    suspend fun setTitleInWatchlist(
        itemId: String,
        inWatchlist: Boolean,
    ): WatchHistoryResult

    suspend fun setLiked(
        request: WatchHistoryRequest,
        liked: Boolean?,
    ): WatchHistoryResult

    suspend fun setTitleLiked(
        itemId: String,
        liked: Boolean?,
    ): WatchHistoryResult
}

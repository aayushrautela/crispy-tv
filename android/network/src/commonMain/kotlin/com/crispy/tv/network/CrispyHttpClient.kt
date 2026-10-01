package com.crispy.tv.network

/**
 * The HTTP seam, and the only thing in this module that has to be written twice.
 *
 * ## Why the port exists, and what it cost to measure
 *
 * Before this, `CrispyHttpClient` was a `class` in `androidMain` wrapping an
 * `OkHttpClient`, and it leaked `okhttp3.HttpUrl`, `Headers` and `Request` in
 * **its own member signatures**. That is what pinned `:backend`'s entire
 * `androidMain` surface: 39 of `CrispyBackendParsers.kt`'s 45 top-level
 * functions are `internal fun CrispyBackendClient.parseX(json: JSONObject)`, so
 * the pin rode in on the **extension receiver**, and swapping the JSON node type
 * for a portable one would have changed nothing about where those functions
 * live. `CrispyBackendClient` itself looked nearly free to port — it speaks
 * OkHttp in three places and never touches `OkHttpClient` — so the wall was one
 * layer further down than the one everyone could see.
 *
 * **Port the wall, then the pin.** This file is the wall.
 *
 * ## The three members that took a request *description* rather than an object
 *
 * `get`, `postJson` and `delete` were already portable in intent and only
 * `HttpUrl`/`Headers` stood in the way, so they changed shape and nothing else.
 * `execute` could not: its five callers each built a whole OkHttp `Request`
 * themselves, needing `Request.Builder`, `toHttpUrl()`, `toRequestBody()` and a
 * `MediaType`, which is **four OkHttp touchpoints at a call site that ought to
 * describe an intent**. So [HttpRequest] is a description — method, url, headers,
 * body — and the implementation owns the rest.
 *
 * ## Why [HttpMethod] has two constants and not five
 *
 * Because only `execute` takes a [HttpMethod], and `execute` is called five
 * times: three `PATCH` in `CrispyBackendAccountApi` and two `PUT` in
 * `CrispyBackendWatchApi`. `GET`, `POST` and `DELETE` have their own members, so
 * naming them here as well would be a second way to say the same thing, and two
 * ways to say the same thing is [the defect this seam exists to end][seam]. **A
 * measure first and add a constant when a caller needs it** — the two were
 * measured, not guessed.
 *
 * [seam]: BackendApi
 */
data class CrispyHttpResponse(
    val code: Int,
    val body: String,
)

/**
 * The only verbs [CrispyHttpClient.execute] is used for. See the interface's
 * KDoc for why this is two constants rather than all five, and why the other
 * three verbs are not here at all.
 */
enum class HttpMethod {
    PATCH,
    PUT,
}

/**
 * A request as a **description**, not as an object.
 *
 * The old `execute` took a fully built `okhttp3.Request`, which meant its five
 * callers each imported `Request`, `toHttpUrl`, `toRequestBody` and a `MediaType`
 * to describe a single verb. Every one of those is an implementation detail of
 * whichever transport runs the call, so the description carries the four facts a
 * caller actually knows: [method], [url], [headers], [body].
 *
 * @property body the request payload, or `null` for a bodyless request. Both
 *   measured `execute` call sites send JSON, and the transport's content type is
 *   an implementation detail — the old callers each had to name
 *   `"application/json; charset=utf-8".toMediaType()` themselves.
 */
data class HttpRequest(
    val method: HttpMethod,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

/**
 * The transport seam, and the only part of HTTP this module exposes.
 *
 * **Every member takes `String` and `Map<String, String>` rather than an OkHttp
 * type, and that is the whole point**: `HttpUrl` and `Headers` were named in the
 * old signatures, so nothing in `commonMain` could hold or implement this type.
 * The transport is the only place that should know what a URL or a header is
 * made of.
 *
 * Implementations must be safe to share and must not own their own scope — this
 * type has no `CoroutineScope`, no dispatcher and no lifetime. The old `class`
 * wrapped a single `OkHttpClient`; implementations here are free to do the same,
 * and `:network`'s `androidMain` keeps exactly that implementation behind this
 * interface.
 */
interface CrispyHttpClient {
    /**
     * Run a request described by [request].
     *
     * @param callTimeoutMs overrides the client's per-call timeout when greater
     *   than zero. Kept as a nullable `Long?` because that is what the callers
     *   pass — they thread an optional timeout through every member — and
     *   changing it to `0` would make every call site invent a meaning for zero.
     */
    suspend fun execute(
        request: HttpRequest,
        callTimeoutMs: Long? = null,
    ): CrispyHttpResponse

    /**
     * GET [url] with [query] appended as query parameters.
     *
     * **[query] is structured rather than pre-concatenated into [url], and that
     * is the measurement rather than a preference.** Eight measured call sites
     * were building OkHttp query strings with `HttpUrl.Builder.addQueryParameter`,
     * which **percent-encodes both the name and the value** — so a value
     * containing `&`, `=` or a space changes the meaning of the request if the
     * two are joined by hand. A step is platform work if its answer depends on
     * the platform, and percent-encoding is OkHttp's answer rather than ours, so
     * the parameters cross the port as pairs and the implementation is the only
     * place that encodes them.
     *
     * **Order is the caller's order and it is load-bearing.** `addQueryParameter`
     * appends in call order and the backend's route schema validates the query
     * parameter set, so a caller that builds this with `buildList { }` keeps its
     * sequence exactly.
     */
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        callTimeoutMs: Long? = null,
        query: List<Pair<String, String>> = emptyList(),
    ): CrispyHttpResponse

    /**
     * [get], except that a URL the implementation cannot parse answers `null`
     * instead of throwing.
     *
     * **This member exists for exactly one caller, and it is not a convenience.**
     * Three call sites used to hold `okhttp3`'s `toHttpUrlOrNull`, which answers
     * `null` where this port's `toHttpUrl()` throws. Two of those three wrap the
     * whole call in `runCatching { }.getOrNull()`, so a malformed URL and a
     * failed request were already the same answer to them and they keep calling
     * [get]. The third does not: it maps an unusable URL to one sealed case and
     * a thrown request to another, and the first of those is not retryable while
     * the second is.
     *
     * **So `null` here means "this was never a request", and a thrown exception
     * still means "the request failed".** The alternative -- letting
     * `runCatching { }.getElse { … }` catch the parse failure too -- would make a
     * malformed URL retryable, which is why a `null`-returning member is the only
     * honest shape here. Collapsing the two into one `runCatching { }.getOrNull()`
     * is what this exists to prevent.
     */
    suspend fun getOrNull(
        url: String,
        headers: Map<String, String> = emptyMap(),
        callTimeoutMs: Long? = null,
        query: List<Pair<String, String>> = emptyList(),
    ): CrispyHttpResponse?

    /**
     * POST [jsonBody] as `application/json; charset=utf-8`.
     *
     * The content type is named here rather than by the caller because no
     * measured call site sends anything else, and it was previously repeated as
     * a `MediaType` at each one.
     */
    suspend fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String> = emptyMap(),
        callTimeoutMs: Long? = null,
    ): CrispyHttpResponse

    suspend fun delete(
        url: String,
        headers: Map<String, String> = emptyMap(),
        callTimeoutMs: Long? = null,
    ): CrispyHttpResponse
}
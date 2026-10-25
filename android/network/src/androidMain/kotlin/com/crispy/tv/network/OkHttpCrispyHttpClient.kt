package com.crispy.tv.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * The OkHttp implementation of [CrispyHttpClient], and the only file in the
 * repository that knows what a URL or a header is made of.
 *
 * **Named `OkHttpCrispyHttpClient` rather than moved, because a `create`-style
 * factory is not a reason a name cannot be honest**: the old type was called
 * `CrispyHttpClient` while being an OkHttp wrapper, so every module that held
 * one was holding a transport by a name that promised none. The port takes that
 * name for the interface and gives the implementation the name it had earned.
 *
 * ## Everything `commonMain` no longer says lives here
 *
 * `HttpUrl.Companion.toHttpUrl`, `Headers.Builder`, `RequestBody` and the
 * `MediaType` for `application/json; charset=utf-8` were each named at a call
 * site before this class existed — four OkHttp imports in `commonMain`-adjacent
 * code that had no business importing them. `getJsonObject` and its four copies
 * are the same shape of problem and are **not** fixed here: that is a separate
 * decision, and a port is not a licence to consolidate.
 *
 * ## Timeouts
 *
 * [CrispyHttpClient.callTimeoutMs] is applied per call with
 * `call.timeout().timeout(…)`, exactly as before. **It is applied after
 * `newCall(request)` and before the call executes**, so a zero or negative value
 * is ignored rather than treated as "no timeout" — the measured callers pass
 * `null` for that and never zero.
 */
class OkHttpCrispyHttpClient(
    private val okHttpClient: OkHttpClient,
) : CrispyHttpClient {

    override suspend fun execute(
        request: HttpRequest,
        callTimeoutMs: Long?,
    ): CrispyHttpResponse = withContext(Dispatchers.IO) {
        val url = request.url.toHttpUrl()
        val builder = Request.Builder().url(url).headers(request.headers.toOkHttpHeaders())
        val body = request.body?.toRequestBody(JSON_MEDIA_TYPE)
        if (body == null) {
            // PATCH and PUT both require a body in OkHttp, and its own error for
            // this is "method PATCH must have a request body" — which names the
            // framework rather than the contract. Say which of our two fields is
            // missing instead.
            require(request.method == HttpMethod.PATCH || request.method == HttpMethod.PUT) {
                "A bodyless request is not expressible: every HttpMethod this seam exposes requires one."
            }
        } else {
            builder.method(request.method.name, body)
        }
        run(builder.build(), callTimeoutMs)
    }

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        callTimeoutMs: Long?,
        query: List<Pair<String, String>>,
    ): CrispyHttpResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url.withQuery(query))
            .headers(headers.toOkHttpHeaders())
            .get()
            .build()
        run(request, callTimeoutMs)
    }

    override suspend fun getOrNull(
        url: String,
        headers: Map<String, String>,
        callTimeoutMs: Long?,
        query: List<Pair<String, String>>,
    ): CrispyHttpResponse? {
        // The parse is the answer, not an incidental guard: `null` here means
        // "this was never a request", so it must be decided before `Dispatchers.IO`
        // is entered and must NOT be reported as a failure. A network error still
        // propagates out of `get`, which is what keeps the two answers apart.
        if (url.toHttpUrlOrNull() == null) return null
        return get(url, headers, callTimeoutMs, query)
    }

    /**
     * The only place in the repository that percent-encodes anything.
     *
     * **The empty case returns the parsed URL untouched**, deliberately rather
     * than for speed: 44 of the 53 measured call sites pass no query at all, and
     * rebuilding an identical URL through a builder would be a difference with
     * no reader. OkHttp's builder appends in call order, so the pairs arrive
     * encoded in the order the caller wrote them.
     */
    private fun String.withQuery(query: List<Pair<String, String>>): HttpUrl =
        toHttpUrl().let { parsed ->
            if (query.isEmpty()) {
                parsed
            } else {
                parsed.newBuilder()
                    .apply { query.forEach { (name, value) -> addQueryParameter(name, value) } }
                    .build()
            }
        }

    override suspend fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String>,
        callTimeoutMs: Long?,
    ): CrispyHttpResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url.toHttpUrl())
            .headers(headers.toOkHttpHeaders())
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        run(request, callTimeoutMs)
    }

    override suspend fun delete(
        url: String,
        headers: Map<String, String>,
        callTimeoutMs: Long?,
    ): CrispyHttpResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url.toHttpUrl())
            .headers(headers.toOkHttpHeaders())
            .delete()
            .build()
        run(request, callTimeoutMs)
    }

    /**
     * The one place a call is actually made, and the only member that touches
     * the `OkHttpClient`.
     *
     * **It is not `open` and not an extension** even though both would read
     * better: a seam with two shapes is a seam with two ways to be wrong, and
     * nothing here has a second caller.
     */
    private suspend fun run(request: Request, callTimeoutMs: Long?): CrispyHttpResponse =
        withContext(Dispatchers.IO) {
            val call = okHttpClient.newCall(request)
            if (callTimeoutMs != null && callTimeoutMs > 0) {
                call.timeout().timeout(callTimeoutMs, TimeUnit.MILLISECONDS)
            }
            call.await().use { response ->
                CrispyHttpResponse(
                    code = response.code,
                    body = response.body.string(),
                )
            }
        }

    /**
     * `Map<String, String>` to OkHttp's `Headers`.
     *
     * **The empty case is a real branch and not a convenience.** An empty
     * `Headers.Builder` builds an empty `Headers`, and OkHttp treats that as
     * "send nothing", which is what `Headers.headersOf()` — the old default on
     * three signatures — did. Naming the empty case makes that equivalence a
     * stated fact rather than a coincidence of defaults.
     */
    private fun Map<String, String>.toOkHttpHeaders(): Headers {
        val builder = Headers.Builder()
        for ((name, value) in this) {
            builder.add(name, value)
        }
        return builder.build()
    }

    private companion object {
        /**
         * `application/json; charset=utf-8`, named once.
         *
         * Before the port this string was turned into a `MediaType` at **five**
         * `execute` call sites plus every `postJson`, by a caller that had no
         * business choosing it. It is a property of the transport.
         */
        private val JSON_MEDIA_TYPE: MediaType = "application/json; charset=utf-8".toMediaType()
    }
}
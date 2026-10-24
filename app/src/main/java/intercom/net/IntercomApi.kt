package intercom.net

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** [status] is the HTTP status, or [NOT_AN_API] when the reply was not what the manager API sends. */
class ApiException(val status: Int, message: String) : IOException(message) {
    /** Retrying without the user changing something will not help. */
    val isFatal: Boolean get() = status in FATAL_STATUSES

    companion object {
        const val NOT_AN_API = 0
        private val FATAL_STATUSES = setOf(NOT_AN_API, 400, 401, 403, 404, 405)
    }
}

data class Participant(
    val name: String,
    val sessionId: String,
    val endpointId: String?,
    val isActive: Boolean,
    val isWhip: Boolean,
)

data class Line(
    val id: String,
    val name: String,
    val participants: List<Participant>,
    val programOutputLine: Boolean,
)

data class SessionOffer(val sessionId: String, val sdp: String)

/**
 * Client for the subset of the Eyevinn intercom-manager REST API that a guest
 * needs to sit on a line. Mirrors `src/api/api.ts` in the web frontend.
 */
class IntercomApi(
    private val origin: HttpUrl,
    authUsername: String,
    authPassword: String,
    apiUrl: String,
) {
    private val configuredApiUrl: String = apiUrl.trim()

    // Basic auth credentials only ever go to hosts the user typed in, never to
    // one a server response pointed us at.
    private val credentialHosts: Set<String> =
        setOfNotNull(origin.host, configuredApiUrl.toHttpUrlOrNull()?.host)
    private val authorization: String? =
        if (authUsername.isEmpty() && authPassword.isEmpty()) null
        else Credentials.basic(authUsername, authPassword, Charsets.UTF_8)

    private val client: OkHttpClient = baseClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val sendCredentials = authorization != null &&
                request.url.isHttps && request.url.host in credentialHosts
            chain.proceed(
                if (sendCredentials) {
                    request.newBuilder().header("Authorization", authorization!!).build()
                } else {
                    request
                }
            )
        }
        .build()

    // The manager holds the participants request open for 25 s.
    private val longPollClient: OkHttpClient =
        client.newBuilder().readTimeout(45, TimeUnit.SECONDS).build()

    private val apiBaseLock = Mutex()
    private var apiBase: HttpUrl? = null

    suspend fun fetchLine(productionId: String, lineId: String): Line =
        parseLine(json(get("production", productionId, "line", lineId)))

    suspend fun fetchProductionName(productionId: String): String =
        json(get("production", productionId)).optString("name")

    /** Resolves when the line's participants change or the server-side hold times out. */
    suspend fun longPollParticipants(productionId: String, lineId: String): List<Participant> {
        val request = Request.Builder()
            .url(endpoint("production", productionId, "line", lineId, "participants"))
            .post(EMPTY_BODY)
            .build()
        return parseParticipants(jsonArray(execute(longPollClient, request)))
    }

    suspend fun offerSession(productionId: String, lineId: String, username: String): SessionOffer {
        // The web client sends numeric ids, which every manager version accepts.
        val body = JSONObject()
            .put("productionId", productionId.toLongOrNull() ?: productionId)
            .put("lineId", lineId.toLongOrNull() ?: lineId)
            .put("username", username)
        val request = Request.Builder().url(endpoint("session")).post(body.toJsonBody()).build()
        val reply = json(execute(client, request))
        val sessionId = reply.optString("sessionId")
        val sdp = reply.optString("sdp")
        if (sessionId.isEmpty() || sdp.isEmpty()) throw notAnApi()
        return SessionOffer(sessionId, sdp)
    }

    suspend fun patchSession(sessionId: String, sdpAnswer: String) {
        val body = JSONObject().put("sdpAnswer", sdpAnswer)
        execute(client, Request.Builder().url(endpoint("session", sessionId)).patch(body.toJsonBody()).build())
    }

    suspend fun deleteSession(sessionId: String) {
        execute(client, Request.Builder().url(endpoint("session", sessionId)).delete().build())
    }

    suspend fun heartbeat(sessionId: String) {
        get("heartbeat", sessionId)
    }

    private suspend fun get(vararg segments: String): HttpResult =
        execute(client, Request.Builder().url(endpoint(*segments)).build())

    private suspend fun endpoint(vararg segments: String): HttpUrl {
        val builder = resolveApiBase().newBuilder()
        segments.forEach { builder.addPathSegment(it) }
        return builder.build()
    }

    private suspend fun resolveApiBase(): HttpUrl = apiBaseLock.withLock {
        apiBase ?: discoverApiBase().also { apiBase = it }
    }

    /**
     * The web frontend learns its backend from `/env.js` (`window.__ENV__.MANAGER_URL`,
     * written by the container entrypoint) and otherwise talks to its own origin.
     */
    private suspend fun discoverApiBase(): HttpUrl {
        if (configuredApiUrl.isNotEmpty()) {
            val url = configuredApiUrl.toHttpUrlOrNull()
                ?: throw ApiException(ApiException.NOT_AN_API, "The API server URL in Settings is not a valid URL.")
            return if (url.encodedPath.contains("/api/")) url.withoutTrailingSlash() else url.apiV1()
        }

        val envJs = client.newCall(Request.Builder().url(origin.resolve("/env.js")!!).build()).await()
        if (envJs.code == 401) throw ApiException(401, errorMessage(envJs))
        val managerUrl = if (envJs.isSuccess) {
            MANAGER_URL.find(envJs.body)?.groupValues?.get(1)?.replace(JS_ESCAPE, "$1")
        } else {
            null
        }
        val base = managerUrl?.takeIf { it.isNotBlank() }?.let { origin.resolve(it) } ?: origin
        return base.apiV1()
    }

    private fun HttpUrl.withoutTrailingSlash(): HttpUrl =
        if (pathSegments.last().isEmpty() && pathSegments.size > 1) {
            newBuilder().removePathSegment(pathSegments.size - 1).build()
        } else {
            this
        }

    private fun HttpUrl.apiV1(): HttpUrl =
        withoutTrailingSlash().newBuilder().addPathSegments("api/v1").query(null).fragment(null).build()

    private suspend fun execute(client: OkHttpClient, request: Request): HttpResult {
        val result = client.newCall(request).await()
        if (!result.isSuccess) throw ApiException(result.code, errorMessage(result))
        return result
    }

    private fun json(result: HttpResult): JSONObject =
        try {
            JSONObject(result.body)
        } catch (e: JSONException) {
            throw notAnApi()
        }

    private fun jsonArray(result: HttpResult): JSONArray =
        try {
            JSONArray(result.body)
        } catch (e: JSONException) {
            throw notAnApi()
        }

    // Typically the frontend's web server answering with its HTML page because
    // the API lives on another host.
    private fun notAnApi() = ApiException(
        ApiException.NOT_AN_API,
        "The server did not answer like an intercom API. If the API is hosted at a different address than the call URL, set it under Advanced in Settings.",
    )

    private fun errorMessage(result: HttpResult): String {
        if (result.code == 401) {
            return "Sign-in was rejected. Check the username and password in Settings."
        }
        val detail = when {
            result.contentType.contains("text/plain") -> result.body.trim()
            result.contentType.contains("application/json") -> try {
                JSONObject(result.body).let { it.optString("message").ifEmpty { it.optString("error") } }
            } catch (e: JSONException) {
                ""
            }
            else -> ""
        }
        return detail.take(200).ifEmpty { "The server returned HTTP ${result.code}." }
    }

    private fun parseLine(o: JSONObject) = Line(
        id = o.optString("id"),
        name = o.optString("name"),
        participants = parseParticipants(o.optJSONArray("participants") ?: JSONArray()),
        programOutputLine = o.optBoolean("programOutputLine", false),
    )

    private fun parseParticipants(array: JSONArray): List<Participant> =
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }.map { o ->
            Participant(
                name = o.optString("name"),
                sessionId = o.optString("sessionId"),
                endpointId = if (o.isNull("endpointId")) null else o.optString("endpointId"),
                isActive = o.optBoolean("isActive", false),
                isWhip = o.optBoolean("isWhip", false),
            )
        }

    private class HttpResult(val code: Int, val contentType: String, val body: String) {
        val isSuccess: Boolean get() = code in 200..399
    }

    private suspend fun Call.await(): HttpResult = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use {
                        HttpResult(it.code, it.header("Content-Type").orEmpty(), it.body.string())
                    }
                } catch (e: IOException) {
                    continuation.resumeWithException(e)
                    return
                }
                continuation.resume(result)
            }
        })
    }

    private companion object {
        val baseClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        val JSON = "application/json".toMediaType()

        // Sent without a content type: the manager rejects an empty JSON body.
        val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)

        val MANAGER_URL = Regex("""MANAGER_URL\s*:\s*"((?:[^"\\]|\\.)*)"""")
        val JS_ESCAPE = Regex("""\\(.)""")

        fun JSONObject.toJsonBody(): RequestBody = toString().toRequestBody(JSON)
    }
}

package intercom.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class LineRole { LISTENER, PROGRAM_USER }

data class CallRef(
    val productionId: String,
    val lineId: String,
    val role: LineRole? = null,
) {
    val key: String get() = "$productionId:$lineId"
}

/** Where the intercom lives and which lines a call URL points at. */
data class CallTarget(
    val origin: HttpUrl,
    val calls: List<CallRef>,
)

sealed interface CallUrlResult {
    data class Ok(val target: CallTarget) : CallUrlResult
    data class Invalid(val reason: String) : CallUrlResult
}

/**
 * Parses the call links the web frontend shares:
 * `https://host/calls?lines=<production>:<line>[:l|p][,...]&guest=1`, plus the
 * older `/production-lines/production/<id>/line/<id>` path form.
 */
object CallUrl {
    private val PATH_FORM = Regex("production/([^/]+)/line/([^/]+)")

    fun parse(raw: String): CallUrlResult {
        val text = raw.trim()
        if (text.isEmpty()) return CallUrlResult.Invalid("Enter the call URL.")
        if (text.startsWith("http://", ignoreCase = true)) {
            return CallUrlResult.Invalid("Only https:// call URLs are supported.")
        }
        val withScheme = if (text.contains("://")) text else "https://$text"
        val url = withScheme.toHttpUrlOrNull()
        if (url == null || !url.isHttps) return CallUrlResult.Invalid("That is not a valid https:// URL.")

        val calls = (fromLinesParam(url.queryParameter("lines")) + fromPath(url))
            .distinctBy { it.key }
        if (calls.isEmpty()) {
            return CallUrlResult.Invalid("The URL does not point at a call. It should look like https://host/calls?lines=1:2&guest=1")
        }

        val origin = url.newBuilder()
            .username("").password("")
            .encodedPath("/")
            .query(null)
            .fragment(null)
            .build()
        return CallUrlResult.Ok(CallTarget(origin, calls))
    }

    private fun fromLinesParam(param: String?): List<CallRef> =
        param.orEmpty().split(',').mapNotNull { entry ->
            val parts = entry.trim().split(':')
            if (parts.size < 2 || parts[0].isEmpty() || parts[1].isEmpty()) return@mapNotNull null
            val role = when (parts.getOrNull(2)) {
                "l" -> LineRole.LISTENER
                "p" -> LineRole.PROGRAM_USER
                else -> null
            }
            CallRef(parts[0], parts[1], role)
        }

    private fun fromPath(url: HttpUrl): List<CallRef> {
        val match = PATH_FORM.find(url.pathSegments.joinToString("/")) ?: return emptyList()
        return listOf(CallRef(match.groupValues[1], match.groupValues[2]))
    }
}

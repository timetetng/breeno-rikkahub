package com.timetetng.breeno.bridge

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal client for RikkaHub's embedded Ktor Web API.
 *
 * Only the three endpoints the bridge needs are implemented:
 *   GET  /api/conversations                       -> resolve the conversation id
 *   POST /api/conversations/{id}/messages         -> feed the user's utterance in
 *   GET  /api/conversations/{id}/stream           -> SSE: snapshot / node_update
 *
 * No auth: the RikkaHub server is expected to be bound to loopback. If JWT is enabled
 * there, this stops working by design — turn it off for the loopback listener.
 */
object RikkaClient {

    private val io: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "breno-rikka-io").apply { isDaemon = true }
    }

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 10_000

    @Volatile
    private var cachedConversationId: String? = null

    /** Runs [block] on the shared IO pool. Never throws into the caller. */
    fun submit(block: () -> Unit) {
        io.execute {
            try {
                block()
            } catch (t: Throwable) {
                L.e("io task failed", t)
            }
        }
    }

    // ---------------------------------------------------------------- conversations

    /** Resolve (and cache) the RikkaHub conversation this module talks into. */
    fun resolveConversationId(forceRefresh: Boolean = false): String? {
        cachedConversationId?.takeIf { !forceRefresh }?.let { return it }

        val body = httpGet("${Config.baseUrl}/api/conversations") ?: return null
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: run {
            L.w("conversations: not a JSON array")
            return null
        }
        if (arr.length() == 0) {
            L.w("conversations: empty")
            return null
        }

        var containsMatch: String? = null
        var newestId: String? = null
        var newestAt = Long.MIN_VALUE

        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val id = c.optString("id").takeIf { it.isNotBlank() } ?: continue
            val title = c.optString("title")
            val updateAt = c.optLong("updateAt", 0L)

            if (title == Config.CONVERSATION_TITLE) {
                L.i("resolved conversation by exact title: $title -> $id")
                cachedConversationId = id
                return id
            }
            if (containsMatch == null &&
                title.isNotBlank() &&
                title.contains(Config.CONVERSATION_TITLE, ignoreCase = true)
            ) {
                containsMatch = id
            }
            if (updateAt > newestAt) {
                newestAt = updateAt
                newestId = id
            }
        }

        val chosen = containsMatch ?: newestId
        if (chosen == null) L.w("no usable conversation found") else {
            L.i("resolved conversation (${if (containsMatch != null) "fuzzy" else "fallback-newest"}) -> $chosen")
        }
        cachedConversationId = chosen
        return chosen
    }

    // ---------------------------------------------------------------- send

    /** POST a user utterance. RikkaHub takes it from there (answer = true). */
    fun sendMessage(conversationId: String, text: String): Boolean {
        val payload = JSONObject().apply {
            put("parts", JSONArray().put(JSONObject().apply {
                put("type", "text")
                put("text", text)
            }))
        }
        return httpPost("${Config.baseUrl}/api/conversations/$conversationId/messages", payload.toString())
    }

    // ---------------------------------------------------------------- stream

    /**
     * Opens the SSE stream for [conversationId] and reports every assistant text it sees.
     *
     * The transport is full-state, not delta: every event carries the whole node, so the
     * caller gets the complete assistant text each time and is responsible for diffing.
     *
     * @param onUpdate   (fullAssistantText, isGenerating) — invoked on the IO thread
     * @return a handle that can be closed to stop reading
     */
    fun openStream(
        conversationId: String,
        baselineMessageCount: Int,
        onUpdate: (text: String, isGenerating: Boolean) -> Unit,
    ): StreamHandle {
        val stopped = AtomicBoolean(false)
        var conn: HttpURLConnection? = null

        io.execute {
            try {
                conn = (URL("${Config.baseUrl}/api/conversations/$conversationId/stream")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = 0 // SSE: block indefinitely, we control teardown
                    setRequestProperty("Accept", "text/event-stream")
                    setRequestProperty("Cache-Control", "no-store")
                }
                conn.connect()
                L.d("stream connected http=${conn.responseCode}")

                val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                var eventName = ""
                val data = StringBuilder()

                while (!stopped.get()) {
                    val line = reader.readLine() ?: break

                    when {
                        line.isEmpty() -> {
                            if (data.isNotEmpty()) {
                                handleEvent(eventName, data.toString(), baselineMessageCount, onUpdate)
                                if (eventName == "node_update" || eventName == "snapshot") {
                                    // generation finished -> the payload says so; caller decides
                                }
                                eventName = ""
                                data.setLength(0)
                            }
                        }
                        line.startsWith(":") -> Unit // heartbeat / comment
                        line.startsWith("event:") -> eventName = line.substring(6).trim()
                        line.startsWith("data:") -> {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.substring(5).trim())
                        }
                    }
                }
                L.d("stream reader ended")
            } catch (t: Throwable) {
                if (!stopped.get()) L.w("stream failed: ${t.message}")
            } finally {
                runCatching { conn?.disconnect() }
            }
        }

        return StreamHandle(stopped, conn)
    }

    private fun handleEvent(
        eventName: String,
        payload: String,
        baselineMessageCount: Int,
        onUpdate: (String, Boolean) -> Unit,
    ) {
        if (eventName.isEmpty() || eventName == "error") {
            if (eventName == "error") L.w("stream error event: $payload")
            return
        }
        val obj = runCatching { JSONObject(payload) }.getOrNull() ?: return

        val isGenerating = obj.optBoolean("isGenerating", false)

        val node: JSONObject? = when (eventName) {
            "node_update" -> obj.optJSONObject("node")
            "snapshot" -> lastNode(obj.optJSONObject("conversation"))
            else -> null
        } ?: return

        // Only look at nodes that appeared after we started listening.
        val count = obj.optJSONObject("conversation")?.optJSONArray("messages")?.length()
        if (eventName == "snapshot" && count != null && count < baselineMessageCount) return

        val text = extractAssistantText(node)
        if (text.isEmpty()) return
        onUpdate(text, isGenerating)
    }

    private fun lastNode(conversation: JSONObject?): JSONObject? {
        val arr = conversation?.optJSONArray("messages") ?: return null
        if (arr.length() == 0) return null
        return arr.optJSONObject(arr.length() - 1)
    }

    /** Concatenates the text parts of the selected message in [node], if it is an answer. */
    fun extractAssistantText(node: JSONObject): String {
        val variants = node.optJSONArray("messages") ?: return ""
        val idx = node.optInt("selectIndex", 0).coerceIn(0, (variants.length() - 1).coerceAtLeast(0))
        val msg = variants.optJSONObject(idx) ?: return ""
        if (!msg.optString("role").equals("ASSISTANT", ignoreCase = true)) return ""
        val parts = msg.optJSONArray("parts") ?: return ""

        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            if (p.optString("type") == "text") sb.append(p.optString("text"))
        }
        return sb.toString()
    }

    class StreamHandle(
        private val stopped: AtomicBoolean,
        private val conn: HttpURLConnection?,
    ) {
        fun close() {
            stopped.set(true)
            runCatching { conn?.disconnect() }
        }
    }

    // ---------------------------------------------------------------- plumbing

    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }
            if (code !in 200..299) {
                L.w("GET $url -> $code ${body?.take(200)}")
                null
            } else body
        } catch (t: Throwable) {
            L.w("GET $url failed: ${t.message}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun httpPost(url: String, json: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(json) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }
            if (code !in 200..299) {
                L.w("POST $url -> $code ${body?.take(300)}")
                false
            } else {
                L.i("POST $url -> $code")
                true
            }
        } catch (t: Throwable) {
            L.w("POST $url failed: ${t.message}")
            false
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}

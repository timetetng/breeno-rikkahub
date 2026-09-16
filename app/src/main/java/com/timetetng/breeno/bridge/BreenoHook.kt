package com.timetetng.breeno.bridge

import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The actual bridge.
 *
 * Breeno renders its chat by pushing [AIChatViewBean] objects into a data center. The
 * tricky part is that the data layer exists **twice** in 11.8.3:
 *
 *   com.heytap.speechassist.aichat.AIChatDataCenter
 *       the legacy one, methods obfuscated to single letters
 *       (J = insert, E = update). Hooking it looks like it works and then never fires.
 *
 *   com.heytap.speechassist.pluginAdapter.aichat.AIChatDataCenter
 *       the one the current CUI actually drives, method names intact:
 *       addChatBeanData(bean) / notifyUpdateView(bean, boolean)
 *
 * Both are hooked; whichever carries traffic wins. The insert/update pair of the
 * whichever class fired is remembered per turn so the answer is painted back through
 * the same door.
 */
object BreenoHook {

    private const val BEAN_CLASS = "com.heytap.speechassist.aichat.bean.AIChatViewBean"
    private const val RECORD_PREFIX = "rikkahub_"

    private data class Target(
        val className: String,
        val insert: String,
        val update: String,
    )

    private val DATA_CENTERS = listOf(
        Target(
            className = "com.heytap.speechassist.pluginAdapter.aichat.AIChatDataCenter",
            insert = "addChatBeanData",
            update = "notifyUpdateView",
        ),
        Target(
            className = "com.heytap.speechassist.aichat.AIChatDataCenter",
            insert = "J",
            update = "E",
        ),
    )

    private val main = Handler(Looper.getMainLooper())

    private lateinit var beanCls: Class<*>
    private var typeQuery = 1
    private var typeAnswer = 2

    /** One in-flight exchange per Breeno "room". */
    private class Turn(
        val roomId: String,
        val recordId: String,
        val target: Target,
        @Volatile var dataCenter: Any?,
    ) {
        @Volatile var handle: RikkaClient.StreamHandle? = null
        @Volatile var lastText: String = ""
        @Volatile var inserted: Boolean = false
        @Volatile var finished: Boolean = false
    }

    private val turns = ConcurrentHashMap<String, Turn>()

    @Volatile
    private var lastInputKey: String = ""

    // ---------------------------------------------------------------- install

    fun install(cl: ClassLoader) {
        beanCls = XposedHelpers.findClass(BEAN_CLASS, cl)
        typeQuery = staticIntOr(beanCls, "TYPE_QUERY", typeQuery)
        typeAnswer = staticIntOr(beanCls, "TYPE_ANSWER", typeAnswer)
        L.i("resolved types: query=$typeQuery answer=$typeAnswer")

        var installed = 0
        for (target in DATA_CENTERS) {
            val dcCls = runCatching { XposedHelpers.findClass(target.className, cl) }.getOrNull()
            if (dcCls == null) {
                L.w("class not found: ${target.className}")
                continue
            }
            try {
                XposedHelpers.findAndHookMethod(
                    dcCls, target.insert, beanCls, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) = onDataCall(param, target)
                    },
                )
                XposedHelpers.findAndHookMethod(
                    dcCls, target.update, beanCls, java.lang.Boolean.TYPE, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) = onDataCall(param, target)
                    },
                )
                installed++
                L.i("hooked ${target.className} [${target.insert}/${target.update}]")
                if (Config.DEBUG_TRACE) installTrace(dcCls)
            } catch (t: Throwable) {
                L.e("hooking ${target.className} failed", t)
            }
        }
        L.i("installed $installed/${DATA_CENTERS.size} data-center hooks")

        // Every user utterance and every answer is an AIChatViewBean that somebody
        // constructed; tracing the constructor is the surest way to see traffic that
        // never reaches the data center.
        try {
            XposedBridge.hookAllConstructors(
                beanCls, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val b = param.thisObject
                        L.i(
                            "bean.new chatType=${intOrNull(b, "getChatType")} " +
                                "record=${strOrNull(b, "getRecordId")} " +
                                "content=${strOrNull(b, "getContent")?.take(80)}",
                        )
                    }
                },
            )
            L.i("bean constructor trace installed")
        } catch (t: Throwable) {
            L.e("bean constructor trace failed", t)
        }
    }

    /** Diagnostic: log every entry point we can see. */
    private fun installTrace(dcCls: Class<*>) {
        try {
            XposedBridge.hookAllMethods(
                dcCls, null, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val sb = StringBuilder(">> ").append(param.method.name).append('(')
                            param.args.forEachIndexed { i, a ->
                                if (i > 0) sb.append(", ")
                                sb.append(describe(a))
                            }
                            L.i(sb.append(')').toString())
                        } catch (_: Throwable) {
                        }
                    }
                },
            )
            L.i("trace installed on ${dcCls.simpleName}")
        } catch (t: Throwable) {
            L.e("trace failed on ${dcCls.simpleName}", t)
        }
    }

    // ---------------------------------------------------------------- hooks

    /** Single entry point for both insert and update calls on either data center. */
    private fun onDataCall(param: XC_MethodHook.MethodHookParam, target: Target) {
        try {
            val bean = param.args.getOrNull(0) ?: return
            val chatType = intOrNull(bean, "getChatType")
            val recordId = strOrNull(bean, "getRecordId")
            val content = strOrNull(bean, "getContent")
            val roomId = strOrNull(bean, "getRoomId").orEmpty()
            val isOurs = recordId != null && recordId.startsWith(RECORD_PREFIX)

            if (!isOurs) {
                L.i("${param.method.name} chatType=$chatType room=$roomId content=${content?.take(120)}")
            }

            if (chatType == typeQuery) {
                if (!content.isNullOrBlank()) startTurn(param.thisObject, target, roomId, content)
                return
            }

            if (chatType == typeAnswer && !isOurs && !Config.DRY_RUN) {
                param.result = null
            }
        } catch (t: Throwable) {
            L.e("onDataCall failed", t)
        }
    }

    // ---------------------------------------------------------------- turn lifecycle

    private fun startTurn(dataCenter: Any, target: Target, roomId: String, query: String) {
        val key = "$roomId|$query"
        synchronized(this) {
            if (key == lastInputKey) {
                L.d("duplicate input ignored")
                return
            }
            lastInputKey = key
        }
        if (Config.DRY_RUN) {
            L.i("DRY_RUN: not forwarding to RikkaHub")
            return
        }

        turns[roomId]?.handle?.close()

        val turn = Turn(roomId, RECORD_PREFIX + UUID.randomUUID(), target, dataCenter)
        turns[roomId] = turn

        RikkaClient.submit {
            val conversationId = RikkaClient.resolveConversationId()
            if (conversationId == null) {
                L.w("no RikkaHub conversation; giving up")
                return@submit
            }
            if (!RikkaClient.sendMessage(conversationId, query)) {
                L.w("sendMessage failed; giving up")
                return@submit
            }
            turn.handle = RikkaClient.openStream(
                conversationId = conversationId,
                baselineMessageCount = 0,
            ) { text, isGenerating ->
                onAssistantText(turn, text, !isGenerating)
            }
        }
    }

    private fun onAssistantText(turn: Turn, text: String, final: Boolean) {
        if (turn.finished) return
        if (text == turn.lastText && !final) return

        val first = !turn.inserted
        turn.lastText = text
        main.post { render(turn, text, first, final) }
    }

    private fun render(turn: Turn, text: String, first: Boolean, isFinal: Boolean) {
        if (turn.finished) return
        try {
            val dataCenter = turn.dataCenter ?: return
            val bean = buildAnswerBean(turn, text, first, isFinal)

            if (first) {
                XposedHelpers.callMethod(dataCenter, turn.target.insert, bean)
                turn.inserted = true
                L.i("inserted answer via ${turn.target.insert} record=${turn.recordId} len=${text.length}")
            } else {
                XposedHelpers.callMethod(dataCenter, turn.target.update, bean, false)
            }

            if (isFinal) {
                turn.finished = true
                turns.remove(turn.roomId)
                turn.handle?.close()
                L.i("turn finished room=${turn.roomId} len=${text.length}")
            }
        } catch (t: Throwable) {
            L.e("render failed", t)
        }
    }

    // ---------------------------------------------------------------- bean building

    private fun buildAnswerBean(turn: Turn, text: String, first: Boolean, isFinal: Boolean): Any {
        val bean = XposedHelpers.newInstance(beanCls)
        bean.set("setChatType", typeAnswer)
        bean.set("setRoomId", turn.roomId)
        bean.set("setRecordId", turn.recordId)
        bean.set("setContent", text)
        bean.set("setFinal", java.lang.Boolean.valueOf(isFinal))
        bean.set("setFirstSlice", first)
        bean.set("setHasTextPrintAnimPlayed", false)
        bean.set("setMsPerChar", 8)
        bean.set("setInterceptStreamTTS", true)
        bean.set("setMicOn", false)
        // Client-side flags the CUI reads before it will lay the bubble out.
        bean.set("addClientLocalData", "fromcui", true)
        bean.set("addClientLocalData", "resultStart", true)
        bean.set("addClientLocalData", "key_dash_line", true)
        return bean
    }

    // ---------------------------------------------------------------- reflection helpers

    private fun staticIntOr(cls: Class<*>, field: String, fallback: Int): Int =
        runCatching { XposedHelpers.getStaticIntField(cls, field) }
            .onFailure { L.w("no static int $field, using $fallback") }
            .getOrDefault(fallback)

    private fun intOrNull(target: Any, method: String): Int? =
        runCatching { XposedHelpers.callMethod(target, method) as? Int }.getOrNull()

    private fun strOrNull(target: Any, method: String): String? =
        runCatching { XposedHelpers.callMethod(target, method) as? String }.getOrNull()

    private fun describe(a: Any?): String = when {
        a == null -> "null"
        beanCls.isInstance(a) ->
            "bean{chat=${intOrNull(a, "getChatType")} c='${strOrNull(a, "getContent")?.take(60)}'}"
        a is String -> "'${a.take(60)}'"
        else -> a.javaClass.simpleName
    }

    private fun Any.set(method: String, vararg args: Any?): Any? =
        runCatching { XposedHelpers.callMethod(this, method, *args) }
            .onFailure { L.w("call $method failed: ${it.message}") }
            .getOrNull()
}

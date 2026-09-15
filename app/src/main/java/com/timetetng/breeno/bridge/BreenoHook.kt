package com.timetetng.breeno.bridge

import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The actual bridge.
 *
 * Breeno renders its chat by pushing [AIChatViewBean] objects through
 * `AIChatDataCenter`. Two methods matter:
 *
 *   `J(Lcom/heytap/speechassist/aichat/bean/AIChatViewBean;)V` — insert a message
 *   `E(Lcom/heytap/speechassist/aichat/bean/AIChatViewBean;Z)V` — update the last one
 *
 * (These are the obfuscated names on Breeno 11.8.3 / versionCode 110803; the shapes are
 * stable across versions even when the letters change — see README for how to re-derive
 * them with dexdump.)
 *
 * So: watch `J` for chatType == TYPE_QUERY (the user's utterance), hand the text to
 * RikkaHub, and paint the answer that comes back through the SSE stream as our own beans.
 * Native answers are dropped so the two don't fight over the same bubble.
 */
object BreenoHook {

    private const val DC_CLASS = "com.heytap.speechassist.aichat.AIChatDataCenter"
    private const val BEAN_CLASS = "com.heytap.speechassist.aichat.bean.AIChatViewBean"

    private const val RECORD_PREFIX = "rikkahub_"

    private val main = Handler(Looper.getMainLooper())

    private lateinit var beanCls: Class<*>
    private var typeQuery = 1
    private var typeAnswer = 2

    /** One in-flight exchange per Breeno "room". */
    private class Turn(
        val roomId: String,
        val recordId: String,
        @Volatile var dataCenter: Any?,
    ) {
        @Volatile var handle: RikkaClient.StreamHandle? = null
        @Volatile var lastText: String = ""
        @Volatile var inserted: Boolean = false
        @Volatile var finished: Boolean = false
    }

    private val turns = ConcurrentHashMap<String, Turn>()
    private var lastInputKey: String = ""

    // ---------------------------------------------------------------- install

    fun install(cl: ClassLoader) {
        beanCls = XposedHelpers.findClass(BEAN_CLASS, cl)
        val dcCls = XposedHelpers.findClass(DC_CLASS, cl)

        typeQuery = staticIntOr(beanCls, "TYPE_QUERY", typeQuery)
        typeAnswer = staticIntOr(beanCls, "TYPE_ANSWER", typeAnswer)
        L.i("resolved types: query=$typeQuery answer=$typeAnswer")

        XposedHelpers.findAndHookMethod(dcCls, "J", beanCls, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) = onInsert(param)
        })

        XposedHelpers.findAndHookMethod(
            dcCls, "E", beanCls, java.lang.Boolean.TYPE, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) = onUpdate(param)
            },
        )

        L.i("hooks installed on $DC_CLASS")
    }

    // ---------------------------------------------------------------- hooks

    private fun onInsert(param: XC_MethodHook.MethodHookParam) {
        try {
            val bean = param.args.getOrNull(0) ?: return
            val chatType = intOrNull(bean, "getChatType") ?: return

            if (chatType == typeQuery) {
                val roomId = strOrNull(bean, "getRoomId").orEmpty()
                val query = strOrNull(bean, "getContent")
                L.i("user input room=$roomId text=${query?.take(200)}")
                if (!query.isNullOrBlank()) startTurn(param.thisObject, roomId, query)
                return
            }

            if (chatType == typeAnswer && isForeignAnswer(bean)) {
                if (Config.DRY_RUN) return
                L.i("dropping native answer (record=${strOrNull(bean, "getRecordId")})")
                param.result = null
            }
        } catch (t: Throwable) {
            L.e("onInsert failed", t)
        }
    }

    private fun onUpdate(param: XC_MethodHook.MethodHookParam) {
        try {
            val bean = param.args.getOrNull(0) ?: return
            val chatType = intOrNull(bean, "getChatType") ?: return
            if (chatType == typeAnswer && isForeignAnswer(bean)) {
                if (Config.DRY_RUN) return
                param.result = null
            }
        } catch (t: Throwable) {
            L.e("onUpdate failed", t)
        }
    }

    /** True when this answer did not originate from us. */
    private fun isForeignAnswer(bean: Any): Boolean {
        val record = strOrNull(bean, "getRecordId")
        return record == null || !record.startsWith(RECORD_PREFIX)
    }

    // ---------------------------------------------------------------- turn lifecycle

    private fun startTurn(dataCenter: Any, roomId: String, query: String) {
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

        val turn = Turn(roomId, RECORD_PREFIX + UUID.randomUUID(), dataCenter)
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
                XposedHelpers.callMethod(dataCenter, "J", bean)
                turn.inserted = true
                L.i("inserted answer record=${turn.recordId} len=${text.length}")
            } else {
                XposedHelpers.callMethod(dataCenter, "E", bean, false)
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

    private fun Any.set(method: String, vararg args: Any?): Any? =
        runCatching { XposedHelpers.callMethod(this, method, *args) }
            .onFailure { L.w("call $method failed: ${it.message}") }
            .getOrNull()
}

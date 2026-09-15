# breeno-rikkahub

把 ColorOS / OPPO / 一加 / realme 上**长按电源键唤出的小布助手**，接到本机 **RikkaHub** 的一个会话上。

小布只留下那个浮层当壳；说的话进 RikkaHub，回答从 RikkaHub 流回来。人格、记忆、工具、Skills、模型，全都是 RikkaHub 里的那一个。

An LSPosed module that reroutes Breeno (长按电源键) into a local RikkaHub conversation over RikkaHub's embedded Web API.

---

## 它到底做了什么

小布渲染对话时，会把 `AIChatViewBean` 对象塞给 `AIChatDataCenter`。关键的两个方法：

| 方法（Breeno 11.8.3 / versionCode 110803） | 作用 |
|---|---|
| `AIChatDataCenter.J(Lcom/heytap/speechassist/aichat/bean/AIChatViewBean;)V` | 插入一条消息 |
| `AIChatDataCenter.E(Lcom/heytap/speechassist/aichat/bean/AIChatViewBean;Z)V` | 更新最后一条 |

（方法名是混淆后的字母，跨版本会变；签名形状基本稳定。）

于是：

```
长按电源键
  → SpeechService(heytap.intent.action.ACTIVATE_SPEECH_ASSIST)
  → FlamingoPowerActivity（那个浮层）
  → 用户说话 / 打字
  → AIChatDataCenter.J(bean)  chatType == TYPE_QUERY
        ├─ 本模块截下文本
        ├─ POST /api/conversations/{id}/messages   （RikkaHub Web API）
        ├─ SSE  /api/conversations/{id}/stream
        └─ 构造 chatType == TYPE_ANSWER 的 bean 塞回去
           首帧 J(bean) 插入 / 后续 E(bean,false) 更新 / 收尾 E(bean,true)
```

原生回答会被丢弃，否则同一个气泡会打架。

## 前置条件

1. **LSPosed**（经典 API，zygisk 版即可），作用域勾 `com.heytap.speechassist`
2. **RikkaHub 打开 Web 服务**
   - 设置 → Web 服务 → 打开
   - 端口 `8080`
   - **建议勾「仅本机」**（绑 127.0.0.1）
   - **JWT 认证关掉**（本模块不实现 token 流程）
3. RikkaHub 里建一个会话，标题按 `Config.CONVERSATION_TITLE`（默认「小布」）。
   找不到就退回"最近更新的会话"。

## 配置

全在 `app/src/main/java/com/timetetng/breeno/bridge/Config.kt`，改完让 CI 重编：

| 常量 | 默认 | 说明 |
|---|---|---|
| `RIKKAHUB_PORT` | `8080` | RikkaHub Web 服务端口 |
| `CONVERSATION_TITLE` | `小布` | 要接过去的会话标题 |
| `ENABLED` | `true` | 总开关 |
| `DRY_RUN` | `false` | 只打日志、不碰 UI、不转发 |

## 构建

CI 出包（`.github/workflows/build.yml`，push 到 main 或手动触发），APK 在 Actions 的 artifact 里。

本地构建需要 JDK 17 + Android SDK：

```sh
gradle assembleDebug
```

## 换一个 Breeno 版本要做什么

方法名变了就得重新对一遍。拿设备上的 APK：

```sh
APK=/product/priv-app/HeyTapSpeechAssist/HeyTapSpeechAssist.apk
unzip -p "$APK" classes4.dex > /data/local/tmp/c4.dex
/apex/com.android.art/bin/dexdump /data/local/tmp/c4.dex > /data/local/tmp/c4.txt
```

然后在 `c4.txt` 里找 `Class descriptor  : 'Lcom/heytap/speechassist/aichat/AIChatDataCenter;'`
那一段，看哪个方法收单个 `AIChatViewBean`（那就是插入）以及
`(AIChatViewBean;Z)`（那就是更新）。改 `BreenoHook.install()` 里的两个方法名字符串即可。

参考实现：[niki914/zafiro](https://github.com/niki914/zafiro)（它自己做了一整套 agent，
本模块只借用它对 Breeno 数据层的 hook 思路）。

## 日志

```sh
adb logcat -s BrenoRikka
# 或
su -c 'logcat -s BrenoRikka'
```

## 已知限制

- 小布升级后方法名可能变，hook 会静默失效（日志里能看到 `findAndHookMethod` 抛的异常）
- 只做了单向文本；语音 TTS 仍由小布自己的引擎读我们的文字（`setInterceptStreamTTS` 先关掉了）
- 没有设置界面，配置是编译期常量

## 许可

MIT

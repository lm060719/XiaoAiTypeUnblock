# 超级小爱输入法语音延迟分析

分析日期：2026-10-07。当前设备连接全部 offline，本轮为本地 APK 静态分析，不代表已复现用户的慢速会话。

## 分析范围与基础证据

- 主样本：`work/apk/超级小爱输入法_0.2.1053.06f65b7c.apk`，版本信息由 `work/xiaoai-21053/apktool/apktool.yml` 核对，versionCode 21053。
- 主样本 SHA-256：`C7E1E9D73FC515BB087E3677288B4DE64110B5191D913D1D6C9757B3C1B5563B`。没有在线设备可核对用户当前安装版本；下面的类名、默认值均限定于此版本。
- 复用现有 JADX Java 与 apktool smali/资源产物：`work/xiaoai-21053/`；对照 20974：`work/voice-compat-20260930/decoded/`。
- 本项目是 Xposed 模块，宿主是 `com.xiaomi.type`，不能用模块自身 APK 代替输入法样本。
- E-manifest：Application 为 `com.mi.ime.MiImeApplication`，输入法入口为 `com.mi.ime.MiInputMethodService`，语音设置页为 `com.mi.ime.settings.VoiceSettingsActivity`。
- E-imports（DEX 依赖 / IPC 等价视图）：文字输入内核引用 `com.iflytek.depend.common.base.SmartEngineManager`；语音 ASR 使用 Binder 接口 `com.xiaomi.speech.framework.common.asr.IAsrListener`、ASR client；服务组件指向 `com.miui.voiceassist/com.aios.osbot.external.OSbotSpeechService`，Manifest 声明 `com.xiaomi.speech.permission.BIND_SPEECH_SERVICE`。AI 文本后处理依赖 `com.xiaomi.taiyi.sdk`。因此不能把文字输入的讯飞内核直接认定为当前语音识别服务。
- E-native-inventory：本包 arm64 库为 `libandroidx.graphics.path.so` 和 `libgeneralcore-jni-v1001.so`；本轮关键链路可在 Java / Binder 包装层定位。未深入分析小爱服务 APK、网络服务器或原生内核。
- E-device：`adb devices -l` 所列设备全部 offline；无本轮运行时耗时或服务端延迟证据。

## 发现

用户现象：①说完显示处理中，延迟落字；②开始说话后迟迟不出文字；③说完等待处理，最后没有文字。

### 1. 转写后按长度选择是否进入大模型

这是已确认的代码行为，是快慢差异的一项具体解释；尚未证明用户每次慢都由它引起。

- `m9/f.java:13`、`:23`：语音模式默认 `ADVANCED`，持久化项为 `mi_ime_voice_prefs/voice_mode`。
- `g9/k.java:16`：短文本门槛默认为 30。实际使用 Java `String.length()`，不是语音秒数或词数。
- `ab/p0.java:544`：识别结果若处于高级模式且长度小于门槛，默认跳过 LLM。较长文本进入 AI 净化；工具栏的 `PLAIN/POLISH/TRANSLATE` 选择会覆盖默认决策，数字输入等场景另有直接提交分支。
- 该分支在 `apktool/smali/ab/p0.smali:3348` 附近交叉核对：读取门槛、比较长度，再读取工具栏策略。
- `uh/c.java:212` 的实时工具栏策略也根据模式、输入字段类型和当前文本长度选择 `PLAIN` 或 `POLISH`。
- `y7/q0.java:51`、`:75`、`MiInputMethodService.java:3700`：云控项 `voice_config_llm_short_text_skip_threshold` 可覆盖 30。因此“30 字”是默认值，不是固定规则。
- `uc/b.java:409` → `d2/f3.smali:1246` → `y7/p0.java:97`、`:125`：进入大模型时显示 `voice_llm_processing`，中文资源为“正在解析…”，请求业务标识为 `voice_postprocess`。
- `y7/p0.java:63`：根据 AI session 能力版本选择 V1 或 V2；V2 在 `ab/q3.smali:493` 设每次结果等待 5000 ms，首次输出未通过内容有效性校验时用 retryPrompt 再请求一次（`:815` 起）。超时本身直接回退，不是所有失败都重试。
- `gb/s0.java:75` 与 `y7/n0.java:51`：AI 净化失败或超时原则上回退到已有 ASR 文本。因此“AI 处理慢”本身不足以解释文字最终完全消失。

复原链路：

```text
语音入口 → 小爱语音服务 → partial / final ASR 文本
  ├─ 默认短文本 / 原始转写 / PLAIN → 直接写入输入框
  ├─ AI 净化 / POLISH → AI session → 结果有效性校验 → 写入输入框
  │                              └─ 失败 / 超时 → 回退 ASR 原文
  └─ TRANSLATE → 翻译分支
```

### 2. 开始说话后没有文字，要看小爱服务和首个识别回调

- `r8/h.java:10`：服务为 `com.miui.voiceassist/com.aios.osbot.external.OSbotSpeechService`。不是宿主在本进程独立完成识别。
- `r8/f.java:555`：尚未绑定时，先绑定再开始识别；已经绑定时直接创建会话。`ib/d.java:116` 还会尝试提前绑定，不能把每次慢都称为冷启动。
- `r8/f.java:185`–`:209`：请求引擎为 `volc` 或 `aliyun`，默认 `volc`；服务不支持 `aliyun` 时回退 `volc`。参数有 `language=zh-CN`、`businessTag=mi_ime`、`featureId=voice_asr`。这并不证明用户会话发生过切换，也未观察真实网络请求。
- `y7/q0.java:81`：`voice_asr_engine_provider` 可由云控调整。`r8/d.java:107` 可从 final result 的 `extra.effectiveProvider` 更新实际引擎；`ib/d.java:157` 起会统计 requested_provider / actual_provider / is_rerouted。
- `r8/d.java:74`：partial 回调把文本写入状态流；`ib/c.java:152` 只有会话仍活跃且 ASR 状态为 Recording 时才更新显示。服务没有返回 partial 或会话状态不匹配时，界面可以暂时没有文字。
- `ib/c.java:79`：RECORDER_BUSY 时会先清理旧录音器再重试一次，也会延迟启动。具体录音、联网和云端计算耗时需要小爱服务日志来区分。

### 3. “处理完没有字”：宿主本身存在静默结束路径

这是已确认的路径，与用户现象吻合，但没有运行日志证明本次实际命中。

- `ib/c.java:121`：用户已停止语音后再收到大部分 ASR 错误，宿主会走 `Error after stop, suppressing error display`，隐藏错误并调用结束逻辑。CONTENT_MODERATION 被单独处理，不走这个普通错误隐藏分支。
- `cc/d.java:178` 起：ASR onError 清理 client、停止计时器、清空活跃会话和 partial 等状态，再发布 Error。普通错误不会凭空制造 final 文本。
- `ib/d.java:75` 的结束逻辑只读取 finalText；若其长度为 0，就直接恢复 Idle 并重置会话，没有提交文字。
- 因此可以出现：没有最终识别结果 → 停止后收到网络/服务等错误 → 错误提示被隐藏 → finalText 为空 → 处理界面结束但无文字。该错误路径没有像 3 秒停止兜底一样先把 partial 复制为 final。
- 另一路：`o0/j0.java:598` 的 3 秒停止兜底只有已收到 partial 时才能保留文本；partial、final 都为空时，同样无法落字。
- 还有输入目标保护：`uh/c.java:89` 校验原输入会话和 InputConnection；`ab/p0.java:538` 在 App 接管输入框、发送或切换后取消提交。`uh/c.java:897` 的 AI 失败回退也需要目标仍有效。不能把这种有意取消提交直接认定为识别故障。

### 4. 模块吞掉 30002 回调，存在等待副作用

- `app/src/main/java/io/mo/xatype/hooks/VoiceModerationHook.kt:154`：开启语音风控解除时，`code=30002` 的整个 onError(Bundle) 被直接返回，宿主原方法不执行。
- 原 onError 不只显示风控提示，还负责会话结束和资源清理（`r8/d.java:43` → `cc/d.java:178` 起）。把整个回调吞掉，也会跳过这些清理。
- 这可能导致已被服务终止的会话仍显示聆听/处理中，直到停止兜底或更外层超时；本轮只能确认该机制风险，不能确认用户遇到了 30002。
- `r8/f.java:343`：把 30002 改为 -1 只会映射为 UNKNOWN 错误，不是识别成功。隐藏错误也不能恢复服务没有返回的文字。
- 当前模块自身日志只在 verbose 开启时记录具体拦截。旧的语音排查文件含 mock 设备测试输出，本轮未把它们当作真实用户会话证据。

### 5. 超时值及含义

| 环节 | 主样本内的值 | 解释 |
|---|---:|---|
| 小爱服务绑定 | 7000 ms | `r8/e.java:98`；绑定失败时结束/报服务不可用，不是每次都等待这么久 |
| 用户停止后等 final | 3000 ms | `o0/j0.java:589`；可用 partial 兜底，没有 partial 就没有可恢复文字 |
| AI 净化 V1 结果等待 | 5000 ms | `ab/m.java:79`；该调用传入默认参数掩码 8，未选择普通 AI 编辑的 30000 ms 默认分支 |
| AI 净化 V2 每次等待 | 5000 ms | `ab/q3.smali:493`；首次内容校验不通过时最多再请求一次 |
| 外层 ASR 结果保护 | 10000 ms | `ib/f.java:82` 起；由停止调用 `ib/g.java:320` 启动，非每次语音的总时长 |
| 连接错误冷却 | 2000 ms | `ib/g.java:263`；冷却内拒绝再次启动，不能误读为固定多等 2 秒 |

这些是异步保护时间，部分并行，不能相加当作固定总延迟。

## 最小复现实验与判别

1. 在输入法自身设置的“语音输入”里关闭“AI语音净化”（不是模块的“语音风控解除”）。同一 App、同一网络，分别说短句和长句，各重复几次。如果“说完等待”明显减少，AI 后处理参与了延迟；开始说话不出字仍需单独查 ASR。
2. 保持第 1 步设置，仅关闭模块“语音风控解除”重复同样实验。若出现明确错误，或“无字但一直等待”变成及时报错，可对照 30002 和清理日志。仅开关变化本身不等于原因已证实；如果模块提示需重启宿主，按实际设置生效要求操作。
3. 区分首次调用和连续调用，并记录是否切换 App、输入框或网络。无字案例最需要保留一次失败前后的日志。
4. 设备重新在线后核对 `com.xiaomi.type`、`com.miui.voiceassist` 的版本，再抓快、慢、无字三类会话。

只读日志示例（替换实际 device，不清空日志、不安装或修改应用）：

```powershell
adb -s '<device>' logcat -v threadtime 'ime_SpeechAsrManager:V' 'ime_SpeechVoiceHelper:V' 'ime_UnifiedVoiceManager:V' 'ime_VoiceCorrectionCoordinator:V' 'ime_MiInputMethodService:V' 'SpeechManager:V' 'AsrClient:V' 'XiaoAiTypeUnblock:V' '*:S'
```

宿主日志封装会给标签加 `ime_`（`u1/c.java:691`），只抓不带前缀的 SpeechAsrManager 会漏掉关键日志。日志是否保留还取决于实际安装包与系统策略。

| 观察点 | 能回答什么 |
|---|---|
| bind start → CONNECTED → startRecording → onReady | 服务启动、绑定和录音准备是否慢 |
| partial 回调、界面首字 → onFinalResult | 识别阶段是否慢；当前普通日志未记录每个 partial，需要必要时加只记录时间/长度的被动观察 |
| onFinalResult → Voice result: ASR len=… → VOICE_CORRECT V2 committed / fallback | ASR 已结束后，AI 净化或写入输入框是否慢 |
| Stop timeout / ASR result timeout | 命中哪层兜底；是否有可用 partial/final |
| Error after stop, suppressing error display | 原包在停止后隐藏了真实失败 |
| Suppressed ASR error 30002 callback | 模块吞掉了会话清理入口；需与超时、会话号一起看 |
| field taken over by app / fallback 未提交 | 结果已有，但输入框目标失效或提交失败 |

`startRecording` 到 `onFinalResult` 包含用户说话时间，不能直接当作服务处理延迟。要量化停止后的等待，需要额外的停止时刻；要量化首字需要首个非空 partial 时刻。当前普通日志不足以提供这两项精确指标。

## 本轮验证与边界

- 已读 APK Manifest、依赖/IPC 等价视图、原生库清单和宿主入口。
- 长度分支、错误后静默结束及重试/超时均以 JADX 与 smali 对照。
- 对反编译失败的 `ab.q3`，用本机 JADX 1.5.6 重新生成 simple 模式产物：`work/voice-latency-20261007/VoiceCorrectionCoordinator-simple.java`，与现有 smali 一致。
- 已核对 AI 超时回退、输入目标校验和本项目 VoiceModerationHook；没有修改 Hook 行为、安装 APK 或改动设备设置。
- 设备全部离线，尚未复现三类现象、确认当前安装版本或区分真实网络/服务端耗时；具体归因需一次实际失败会话。

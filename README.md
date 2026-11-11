# XiaoAiTypeUnblock (超级小爱输入法净化)

## 📖 项目简介

**XiaoAiTypeUnblock** 是一款专为小米澎湃 OS（Xiaomi HyperOS 4 / Android 17）中内置的 **超级小爱输入法（`com.xiaomi.type`）** 打造的 Xposed 净化与增强模块。

超级小爱输入法深度集成了讯飞输入法定制内核（iFlyTek SmartEngine）以及小米端云大语言模型。然而在日常使用、AI 写作、文案润色和语音转写过程中，系统设置了较为严苛的风控与敏感词过滤规则，常导致生成内容被直接截断（提示“已屏蔽敏感内容”）、语音输入中断或云端候选词库被屏蔽。

本模块采用 **libxposed (API 102)** 规范与 ART Runtime Hook 技术，在内存中净化大模型响应与引擎风控链路，并在小爱原生键盘上补充输入方案、手势和外观定制。

各功能默认关闭，在模块设置页逐项开启；除特别说明外，修改后需重启输入法生效。

---

## ✨ 功能

### 一、净化

#### 1. 🚀 澎湃 OS4+ 版本限制解除
* **问题**：输入法在调出与初始化时检测系统属性 `ro.mi.os.version.code`（通过 `z7.s0.f18512a` 判定系统是否低于 OS 4.0）。在 HyperOS 1/2/3、MIUI 或第三方 AOSP ROM 上会弹出 `OS_VERSION_UNSUPPORTED` 阻断对话框并调用 `requestHideSelf(0)` 自动隐藏，键盘无法使用。
* **方案**：
  * 挂钩 `android.os.SystemProperties`，对 `ro.mi.os.version.code`、`ro.mi.os.version.name` 与 `ro.miui.ui.version.code` 返回 HyperOS 4.0+ 的值；回调先按键名过滤，其他属性直接放行；
  * 在注入阶段反射重置 `z7.s0.f18512a` 阻断标志；
  * 挂钩 `AIVersion.isOS4Service`、`isServiceSupport` 与 `SERVICE_SDK_INT`，解除端侧 AI 服务的系统版本校验；
  * 拦截 `DialogHostActivity` 的 `OS_VERSION_UNSUPPORTED` 提示。

#### 2. 🤖 AI 表达安全拦截解除
* **问题**：AI 润色、文案扩展、智能回复的响应中若包含 `"safety_blocked": true`，前端会丢弃生成文本。
* **方案**：挂钩 AI 响应解析器（完整 JSON 解析、正则降级解析、流式 token 解析），将安全阻断标记改写为 `false`，保留生成的文字。

#### 3. 🎙️ 语音转写风控审查解除
* **问题**：语音输入（ASR）时，敏感语义会触发 `CONTENT_MODERATION` 提示、返回 `30002` 风控错误码并挂断识别流，丢失已说内容。
* **方案**：拦截风控提示、改写 `30002` 错误码，并抑制异常挂断回调。

#### 4. ☁️ 云端黑名单词库下发拦截
* **问题**：SmartEngine 内核定期从云端下发黑名单词库（`key_blackliststr` / `PinyinCloudAttachResult`），阻断部分云端联想与热词候选。
* **方案**：拦截云端词库更新通道与反序列化，清空黑名单列表。

#### 5. 📋 剪贴板敏感标记忽略
* **问题**：Android 13+ 的剪贴板敏感标记（`android.content.extra.IS_SENSITIVE`）会让快捷粘贴栏隐藏、分词联想失效。
* **方案**：拦截 `BaseBundle.getBoolean` 对该标记的查询并返回 `false`（`PersistableBundle` 继承同一方法）。回调先比较键名，其余读取直接放行。

#### 6. ♾️ 剪贴板永久保存
* **问题**：`com.miui.phrase` 默认仅保留 20 条剪贴板记录，超过 72 小时自动清理，并截断超长文本。
* **方案**：同时 Hook `com.miui.phrase` 的持久化写入与 `com.xiaomi.type` 的读取、展示和文本处理路径，取消数量、有效期与单条长度限制；手动删除、清空应用数据和卸载系统组件仍会移除记录。
* **截图自动复制**：在「净化 → 剪贴板」独立开启。系统截图工具保存新截图后自动写入图片剪贴板，供小爱历史收录；会替换当前剪贴板内容。不补录旧截图、不收录普通照片或下载图片，锁屏时跳过。需要系统界面（`com.android.systemui`）作用域，首次启用后重启手机，此后开关即时生效。图片文件被删除或缓存被清理后可能无法继续粘贴。

#### 7. 🧩 解除 AI 功能机型限制
* **问题**：端侧大模型能力（LLM / VLM）由 AI 服务按机型 `whiteModels`、SoC `whiteSocs`、区域 `whiteRegions`、内存 `minRam` / `minRom`、电量 `minBattery` 等条件下发白名单，不满足时 AI 润色、翻译、问小爱等入口被灰掉或隐藏。
* **方案**：挂钩 `com.xiaomi.taiyi.sdk.base.data.AIDetail` 的 `isSupport()` 与 `isAvailable()`，在键盘侧放行。与“OS4 版本限制解除”构成“服务总闸 + 能力分闸”。
* **注意**：仅解除键盘侧入口；确实不满足运行条件（如模型未下发）的能力可能在下游失败，可开启详细日志查看被放开的 ability（`api` / `code`）。

#### 8. 🔒 遥测与埋点拦截
* **问题**：应用内置小米 OneTrack 统计与广告监控（`ad_monitor`），在后台采集并上报埋点。
* **方案**：挂钩 `com.xiaomi.onetrack.OneTrack.isDisable()` 恒返回 `true`，借 OneTrack 自带的全局开关关闭 `track` / `adTrack` 分发与上传。
* **注意**：仅拦截小米 OneTrack，不涉及讯飞内核自身可能的遥测。

### 二、键盘

* **按键下滑快捷操作**：按住键向下滑，字母随距离淡出、功能图标淡入，轻震后松手执行；滑回触发距离以内或向旁边滑开可取消，普通点按继续输入。
  * 可在「键盘 → 输入增强 → 自定义快捷键」为九键、26 键、18 键、14 键分别把任意字母键设为全选、复制、剪切、粘贴、段首、段尾或不设置，同一操作可放在多个键上，「恢复默认」回到下列分配。
  * 默认：拼音九键 **7 / 8 / 9** 为全选、复制、粘贴，**4 / 6** 为剪切、段尾；拼音/英文 26 键及虎码/五笔 **Z / X / C / V / M** 为全选、剪切、复制、粘贴、段尾；中文 18 键 **Z / XC / V / BN / M** 为段首、复制、粘贴、全选、段尾；中文 14 键 **ZX / CV / BN / M** 为全选、复制、粘贴、段尾。
  * 复制、剪切优先处理已有选区，无选区时先全选；全选被拒绝时停止后续操作。向下约 36dp 触发，48dp 图标完全显现。数字面板不启用。
* **快捷操作文字**：在「键盘 → 输入增强」开启「在按键上显示操作文字」，在带快捷操作的键底部显示说明，可调字号及水平、垂直位置。需同时开启下滑快捷操作。
* **键盘区域手写**：中文 26 键、虎码/五笔、18 键与 14 键可直接在字母区写汉字。
  * 在字母键上向下起笔划过约 12dp 即转为笔画并取消该次按键；轻点仍是打字，按住不动仍是长按。
  * 向上起笔（±45°）需超过「上滑起笔距离」（默认 150dp，可调 16～200dp），较短的上滑仍交给输入法输入数字和符号；向左右起笔需超过「左右起笔距离」（默认 100dp，可调 12～200dp）。开启下滑快捷操作时，从快捷键起笔的竖直下划（±30°）仍触发快捷操作。
  * 停笔约 0.5 秒后交给小爱自带的讯飞手写引擎（叠写、离线）识别，经宿主原生上屏通道提交首选，候选条点选可替换刚上屏的字。英文、九键、数字与符号面板不启用。
* **九键双拼**：「键盘 → 输入方案」开启后选择小鹤（默认）、自然码、微软、搜狗、智能 ABC、拼音加加或紫光，重启输入法后选择「拼音9键」。复用小爱原生九键解码与候选，不写入小爱自身的双拼设置。
* **18 键键盘**：7＋6＋5 分组字母布局，使用内置 65,125 条字词的离线解码，复用小爱候选栏与上屏流程。支持全拼、首字母简拼与七种双拼（长按可选准确字母），候选栏显示首选拼音，展开页显示不同读音；选中满三次的候选在完整消耗当前编码时优先。在小爱「键盘选择」或设置页「键盘类型」中选择「18键」，英文切回原生 26 键。
* **14 键键盘**：5 · 5 · 4 布局把 QWERTY 字母两两一键，底排左侧为分隔键 **'**。与 18 键共用解码、双拼、选词学习、快捷操作与长按选字母，组合词记忆分开保存。在「键盘 → 输入方案 → 14 键键盘」开启显示后，在「键盘选择」中选择「14键」。
* **形码输入（虎码 / 五笔）**：内置虎码字词，也可导入 UTF-8 五笔等静态码表，复用小爱的键盘与候选栏，在「键盘选择」中选择「虎码/五笔」。
* **上屏后联想**：「保持」（默认）使用原有联想；「关闭」后文字上屏不再显示联想词，包括后续连续联想。
* **Emoji 候选增强**：打出关键词时，根据前几项汉字候选补充相关表情，保留汉字首选；每词最多 3 个、每次最多 6 个。关键词表由 Unicode CLDR 47 / Emoji 16.0 与常用别名生成（4,955 个），来源与许可见 [SOURCE-emoji.txt](app/src/main/resources/xatype/SOURCE-emoji.txt)。仅增强尚未选词且完整编码匹配的整词；密码框与独立形码 / 18 键引擎不增强。
* **键盘底部间距**：独立开关与 **0 ~ 100 dp** 滑块，调节按键下方的原生留白；关闭恢复原生间距并保留数值。保留系统导航区域与悬浮键盘原生布局。
* **解除键盘高度上限**：解除“键盘调节”拖动与布局计算中的 50dp 增高上限，沿用原生确认、保存和重置；关闭后按原生范围显示，保留已保存的超高尺寸。

### 三、外观

* **深浅色独立配置**：圆角、背景、模糊、文字色、键帽和卡片颜色及不透明度分别保存为浅色与深色两套方案。开启“跟随系统切换深浅色配置”后随系统模式切换；关闭后固定使用所选方案。
* **圆角**：**0 ~ 40 dp** 顶部圆角，配合 `ViewOutlineProvider` 与 HyperOS AGSL 着色器裁切按键与候选栏。
* **背景不透明度**：**0% ~ 100%**。动态玻璃模式保留原生配色，只改变原生背景的 alpha；降低数值可透出底层毛玻璃。
* **自定义背景**：预设纯色（Catppuccin、AMOLED 纯黑、晨曦蓝、暗夜紫、极简白）、R/G/B 调色或背景图片。
* **面板背景统一**：动态玻璃模式下，菜单和键盘选择界面共用键盘公共背景，避免色块分界。
* **剪贴板面板同步**：剪贴板/常用语弹窗同步背景类型、透明度、模糊、圆角、文字色与菜单卡片颜色。
* **文字与键帽颜色**：未自定义时保留原生配色；自定义背景或键帽时自动适配文字对比度，也可指定按键文字、模式文字及工具栏图标颜色。常规与功能键帽可分别设色，按压态自动生成反馈色。
* **独立不透明度**：功能键键帽、菜单卡片、剪贴板卡片、字母键键帽各有 **0% ~ 100%** 不透明度滑块。
* **可读性底色**：在键盘背景下方垫一层底色，透明键盘叠在浅色或深色应用上也能看清。开关默认开启、两套方案共用；不透明度（默认 35%）与颜色按方案保存，颜色默认随字色自动取反。
* **长按选择框与按键提示框配色**、**键盘边缘高光**：按方案分别设置。
* **内置主题与命名方案**：可套用内置主题，也可把当前深浅两套外观一起保存为命名方案，随时切回。

### 四、工具

* **适配状态**：首页展示各功能在当前宿主版本上的接口解析结果，找不到的接口会标出受影响的功能。
* **输入法与外观诊断**：在设置页一键采集（需 Root）并导出输入法日志；「输出详细调试日志」开关控制 Hook 侧的详细日志。
* **一键 Root 重启输入法**：在界面中强制重启输入法进程，使需要重启的设置生效。
* **检查更新**：设置页打开时（最多每小时一次）检查 GitHub Releases，发现新版本时在概览页提示并显示更新日志；「下载并安装」按 SHA-256 校验并确认包名、版本和签名后交给系统安装程序。可在「概览 › 版本更新」手动检查或关闭自动检查。
* **开屏动画**：冷启动设置页时播放约 1.6 秒的净化动画，轻触跳过，可在「概览 › 模块设置 › 启动动画」中关闭。
* **隐藏桌面图标**：隐藏后可从 LSPosed 管理器的模块设置入口打开。

---

## 🛠️ 使用环境与要求

| 项目 | 要求 / 推荐配置 |
| :--- | :--- |
| **操作系统** | Android 8.0 ~ Android 17 (包含 HyperOS 1/2/3/4、MIUI 及第三方 ROM) |
| **CPU 架构** | ARM64-v8a / armeabi-v7a |
| **Root 环境** | KernelSU / APatch / Magisk（一键重启输入法与日志采集需要） |
| **Xposed 框架** | 支持 **libxposed API 102** 的现代框架（如 **LSPosed v2.1.0+**） |
| **作用域** | **超级小爱输入法** (`com.xiaomi.type`)、**常用语/剪贴板服务** (`com.miui.phrase`)、**系统界面** (`com.android.systemui`，用于截图自动复制与键盘弹出时的导航栏过渡) |
| **验证宿主** | 超级小爱输入法 0.2.1358.a9d5eb78 |

> [!TIP]
> **小版本自适配**：大部分依赖宿主混淆名的功能接入 [DexKit](https://github.com/LuckyPray/DexKit) 指纹与缓存。宿主或模块变化后重新扫描，结果保存到各进程自己的 `/data/user_de/0/<包名>/cache/xatype-*symbols.json`；缓存按完整 ZIP 条目元数据标识并校验，损坏时重扫。接口找不到或不唯一时跳过对应功能，并在首页适配状态中展示。指纹已在 0.2.701 ~ 0.2.1358 的 DEX 上核对，其他版本须满足结构校验，不能据此推定全部兼容。可用 `scripts/verify-host-fingerprints.ps1`（剪贴板加 `-Table PhraseSymbols`）对宿主 APK 检查。

---

## 🚀 安装与使用

```mermaid
flowchart LR
    A[下载 / 编译 APK] --> B[安装模块]
    B --> C[LSPosed 中启用模块并确认作用域]
    C --> D[打开模块配置开关与外观]
    D --> E[重启超级小爱输入法]
```

1. **获取安装包**：在 Release 页面下载最新版 APK，或自行编译。
2. **启用模块**：在 LSPosed 等管理器中启用 **超级小爱输入法净化**，确认作用域包含 `com.xiaomi.type` 与 `com.miui.phrase`；使用截图自动复制时还需 `com.android.systemui`。
3. **配置**：打开模块应用，按需开启各项功能。
4. **生效**：点击主界面底部的 **“重启超级小爱输入法”**（需 Root），或在系统设置中强制停止超级小爱输入法。

---

## 🔬 工作原理

```mermaid
graph TD
    subgraph TargetApp["超级小爱输入法 (com.xiaomi.type 进程)"]
        SYSPROP["SystemProperties.get(ro.mi.os.*)"] -->|HyperOsVersionHook| OS_PASS["version.code: 4"]
        LLM_RESP["大模型 JSON / 流式响应"] -->|AiSafetyHook| AI_CLEAN["safety_blocked: false"]
        ASR_IN["ASR 语音流"] -->|VoiceModerationHook| VOICE_PASS["重写 30002 / 丢弃审查中断"]
        CLOUD_REQ["云端词库更新"] -->|CloudBlacklistHook| CLOUD_CLEAN["清空黑名单"]
        IME["MiInputMethodService 视图树"] -->|KeyboardStyleHook| STYLE["圆角 / 背景 / 配色"]
        CACHE["ConfigManager 内存缓存"] --> TargetApp
    end

    subgraph ModuleApp["模块进程 (io.mo.xatype)"]
        UI["设置界面"] -->|写入| PREFS["SharedPreferences: settings"]
        PREFS --> PROVIDER["LogContentProvider"]
    end

    PROVIDER -->|"get_config（首次 / 深浅色切换 / 收到变更通知）"| CACHE
    PROVIDER -.->|"notifyChange(config)"| CACHE
```

### 1. 配置通道

* 设置界面只写模块自己的 `SharedPreferences("settings")`。所有配置项的键名、类型、默认值、取值范围以及是否随深浅色方案切换，集中声明在 [`ConfigSchema.kt`](app/src/main/java/io/mo/xatype/config/ConfigSchema.kt)，Provider 打包与 Hook 侧解包都由这张表生成。
* 宿主进程通过 `LogContentProvider` 的 `get_config` 取得全部配置并缓存在内存中，之后只在三种情况下重新读取：尚无缓存、系统深浅色模式变化、收到配置变更通知。
* 设置写入后，Provider 向 `content://io.mo.xatype.logprovider/config` 发出变更通知；宿主进程在独立线程上去抖后刷新缓存，键盘弹出路径上不再发起跨进程调用。变更监听注册失败时退回到每次弹出都同步。
* 同步失败会在 Hook 日志中记一条警告，并说明当前使用的是默认值还是上次同步的值。
* 四个导出的 Provider 都按调用方 UID 校验：配置与适配上报只对模块本身及作用域内的三个包开放；背景图、词库、码表只对输入法开放，且只读。

### 2. 关键 Hook 点

| 拦截模块 | 目标类与方法 | 拦截机制 | 源码位置 |
| :--- | :--- | :--- | :--- |
| **外观美化注入** | `MiInputMethodService.onCreateInputView`<br>`MiInputMethodService.onStartInputView` | 拦截根视图，注入圆角、透明度、动态玻璃及背景图/纯色。 | [`KeyboardStyleHook.kt`](app/src/main/java/io/mo/xatype/hooks/KeyboardStyleHook.kt) |
| **OS4 限制解除** | `android.os.SystemProperties.get/getInt/getLong` | 伪装 `ro.mi.os.version.code` 为 `4`，`ro.mi.os.version.name` 为 `OS4.0`。 | [`HyperOsVersionHook.kt`](app/src/main/java/io/mo/xatype/hooks/HyperOsVersionHook.kt) |
| **版本标志重置** | `z7.s0` 静态字段 `f18512a` | 反射重置版本阻断字段为 `false`。 | [`HyperOsVersionHook.kt`](app/src/main/java/io/mo/xatype/hooks/HyperOsVersionHook.kt) |
| **AI 版本支持** | `AIVersion.isOS4Service`<br>`AIVersion.isServiceSupport` | 强制返回 `true`。 | [`HyperOsVersionHook.kt`](app/src/main/java/io/mo/xatype/hooks/HyperOsVersionHook.kt) |
| **版本弹窗压制** | `DialogHostActivity.onCreate` | 拦截 `OS_VERSION_UNSUPPORTED` 弹窗并立即销毁。 | [`HyperOsVersionHook.kt`](app/src/main/java/io/mo/xatype/hooks/HyperOsVersionHook.kt) |
| **AI 表达净化** | `fb.s.h(String)`<br>`fb.s.e(String)`<br>`fb.s.f(String)` | 将 `"safety_blocked": true` 替换为 `false`。 | [`AiSafetyHook.kt`](app/src/main/java/io/mo/xatype/hooks/AiSafetyHook.kt) |
| **语音风控拦截** | `a8.n.f(Context, String, String)` | 检测到 `CONTENT_MODERATION` 时返回 `null` 压制提示。 | [`VoiceModerationHook.kt`](app/src/main/java/io/mo/xatype/hooks/VoiceModerationHook.kt) |
| **ASR 错误码重写** | `s8.f.m(int, String)` | 将风控状态码 `30002` 重写为 `-1`。 | [`VoiceModerationHook.kt`](app/src/main/java/io/mo/xatype/hooks/VoiceModerationHook.kt) |
| **ASR 会话保护** | `s8.d.e(Bundle)` | 丢弃携带 `code: 30002` 的回调，阻止引擎关闭会话。 | [`VoiceModerationHook.kt`](app/src/main/java/io/mo/xatype/hooks/VoiceModerationHook.kt) |
| **云端黑名单清空** | 引擎委托的 `onPyCloudAttachUpdate(...)` | 将 `blacklistStr` 参数置空。 | [`CloudBlacklistHook.kt`](app/src/main/java/io/mo/xatype/hooks/CloudBlacklistHook.kt) |
| **词库实体置空** | `PinyinCloudAttachResult.get/setBlackListStr`、`fromBundle` | 返回空字符串，反序列化后经公开 setter 清空。 | [`CloudBlacklistHook.kt`](app/src/main/java/io/mo/xatype/hooks/CloudBlacklistHook.kt) |
| **剪贴板敏感绕过** | `BaseBundle.getBoolean(String, boolean)` | 键为 `android.content.extra.IS_SENSITIVE` 时返回 `false`。 | [`ClipboardSensitiveHook.kt`](app/src/main/java/io/mo/xatype/hooks/ClipboardSensitiveHook.kt) |
| **AI 功能机型限制** | `AIDetail.isSupport()`<br>`AIDetail.isAvailable()` | 强制返回 `true`。 | [`AiAbilityUnlockHook.kt`](app/src/main/java/io/mo/xatype/hooks/AiAbilityUnlockHook.kt) |
| **遥测与埋点拦截** | `OneTrack.isDisable()` | 恒返回 `true`。 | [`TelemetryBlockHook.kt`](app/src/main/java/io/mo/xatype/hooks/TelemetryBlockHook.kt) |

### 3. 容错

所有 Hook 安装都被 `try-catch` 包裹。输入法更新导致方法混淆或签名变化时，模块跳过对应功能并记录日志、在首页适配状态中标出，不影响输入法的基础打字。

---

## 📂 项目结构

```text
app/src/main/
├── java/io/mo/xatype/
│   ├── XiaoAiTypeModule.kt   # libxposed 入口，按进程安装各 Hook
│   ├── compat/               # 宿主指纹、DexKit 符号表与适配状态上报
│   ├── config/               # 配置表 ConfigSchema、宿主侧缓存 ConfigManager、深浅色方案与外观预设
│   ├── hooks/                # 各功能 Hook（净化、外观、键盘、剪贴板、SystemUI）
│   ├── keyboard/             # 快捷操作、手写、Emoji、九键双拼等键盘逻辑
│   ├── keyboard18/           # 18/14 键离线解码引擎与宿主桥接
│   ├── shape/                # 虎码/五笔形码引擎与码表
│   ├── dict/                 # 在线词库下载与导入
│   ├── provider/             # 导出的 ContentProvider 与统一的调用方校验
│   ├── diagnostics/          # 日志采集与导出
│   ├── update/               # 检查与下载更新
│   ├── ui/                   # 设置界面
│   └── util/                 # 反射与日志工具
├── assets/                   # 18 键词库、虎码码表
├── resources/
│   ├── META-INF/xposed/      # java_init.list、module.prop、scope.list（三个作用域）
│   └── xatype/               # Emoji 关键词表
└── AndroidManifest.xml
scripts/                      # 宿主指纹检查、码表生成等辅助脚本
```

---

## 🏗️ 编译构建

```bash
./gradlew :app:testDebugUnitTest
```

```bash
./gradlew assembleDebug
```

```bash
./gradlew assembleRelease
```

APK 输出在 `app/build/outputs/apk/` 下。实验构建：`./gradlew :app:assembleShapePrototype`（形码）、`./gradlew :app:assembleKeyboard18Prototype`（18 键），未配置正式签名时使用开发签名。

### GitHub 自动构建

推送代码会触发 [构建安装包](https://github.com/lm060719/XiaoAiTypeUnblock/actions/workflows/build-release.yml) 工作流：

- **`main` 分支与手动运行（Run workflow）**：运行 Release 单元测试，编译并签名 Release APK，验证签名，生成 SHA-256 校验文件。
- **其他分支**：运行 Debug 单元测试并构建 Debug APK，不使用正式签名。
- 安装包在对应运行页面的 **Artifacts** 下载，保留 90 天。工作流不创建标签或 GitHub Release；应用内更新读取的是手动发布的 Release 资产。
- 安装包名称包含应用版本、构建序号和提交短哈希。`versionName`、`versionCode` 由 `app/build.gradle.kts` 管理，发布新版本时请同步递增。

#### 首次配置签名

在仓库 **Settings → Secrets and variables → Actions** 配置：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 正式签名 keystore 文件的完整 Base64 编码 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 签名密钥别名 |
| `ANDROID_KEY_PASSWORD` | 签名密钥密码 |

必须使用已发布正式 APK 的同一份签名密钥，才能覆盖安装旧版本。缺少签名配置时 Release 构建会明确报错，不会产出未签名 APK；密钥只保存在 GitHub Secrets 和构建机临时目录，构建结束后清理。

本地编译签名正式包时，设置 `ANDROID_KEYSTORE_PATH` 及上述三个密码/别名环境变量后执行 `./gradlew :app:assembleRelease`；不设置时 Release 输出未签名 APK。

---

## ⚖️ 开源协议与免责声明

### 开源协议
本项目基于 **[GNU General Public License v3.0 (GPL-3.0)](LICENSE)** 协议开源。在遵守此协议的前提下，任何人均可自由分发、修改与使用本项目源码。

### 免责声明
1. 本项目仅供 Android 逆向工程、Hook 原理研究与个人设备定制学习使用。
2. 目标应用“超级小爱输入法”的一切知识产权归属于小米科技有限责任公司及相关权利人。
3. 请在遵守当地法律法规的前提下使用本模块，严禁将本模块用于任何违法犯罪或侵犯他人合法权益的场景。作者不对使用本软件所产生的任何直接或间接后果承担法律责任。

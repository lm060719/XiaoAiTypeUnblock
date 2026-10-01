# 超级小爱输入法 0.2.1053 适配记录

模块版本：2.1.3 / versionCode 8。验证日期：2026-10-01。

## 目标与分析依据

- 包名：`com.xiaomi.type`。
- 输入法版本：`0.2.1053.06f65b7c` / versionCode `21053`。
- APK SHA-256：`c7e1e9d73fc515bb087e3677288b4de64110b5191d913d1d6c9757b3c1b5563b`。
- 入口：`com.mi.ime.SettingsActivity`、`com.mi.ime.MiInputMethodService`。
- 本次修改针对 Java / DEX 层，未修改目标 APK、JNI 或 native 库。
- APK 的依赖锚点包括 Android 视图和系统属性 API、Compose 状态和配色类、`org.json` 解析器、ASR Binder 回调以及讯飞词库实体。所需逻辑均可在 DEX 中确认。
- JADX 导出 7605 个 Java 文件，返回 145 项反编译错误；关键方法进一步以 Apktool smali 和手机 ART 反射校验，未依赖错误的反编译结果猜测签名。

## 映射

`TargetCompatibility` 通过服务的 `hyperMaterialHelper` 字段类型、材质方法签名、40 个 `long` 配色字段及配色工厂返回类型识别 `V21053`，不只依赖版本号。

| 功能 | v209 结构 | 21053 结构 |
| --- | --- | --- |
| 材质辅助类 | `bb.b0` | `ab.i0` |
| 材质支持判断 | `g(): boolean` | `h(): boolean` |
| 材质状态更新 | `j(): void` | `k(): void` |
| 材质重新应用 | `k(): void` | `l(): void` |
| 清除原生模糊和阴影 | `m(): void` | `n(): void` |
| 材质显示/隐藏 | `n(boolean): void` | `o(boolean): void` |
| 阴影渲染更新 | `bb.t1.b(): void` | `ab.e2.c(): void` |
| Compose 配色实体 | `na.j` | `ma.k` |
| Compose 配色工厂 | `na.u.z(s0.p)` | `ma.v.z(s0.p)` |
| 静态明暗配色 | `na.x.d/e` | `ma.x.d/e` |
| AI 响应解析 | `fb.t.h/e/f(String)` | `eb.s.h/e/f(String)` |
| Miclaw 错误处理 | `a8.n.g(Context, String, String)` | `z7.h.g(Context, String, String)` |
| ASR 错误码映射 | `s8.f.m(int, String)` | `r8.f.m(int, String)` |
| ASR 错误回调 | `s8.d.e(Bundle)` | `r8.d.e(Bundle)` |

新版的材质字段 `a/e/i/r/s`、配色字段 `a..N/U/Y` 与 v209 语义对应，因此复用已有外观实现，通过 `ModernKeyboardProfile` 选择方法和类名。其中新版 `g()` 是包级材质状态，`m()` 是阴影视图布局，不能继续按旧映射 Hook 或调用。

云端词库 `c1.onPyCloudAttachUpdate`、`PinyinCloudAttachResult`、系统剪贴板敏感标记及动态加载的 `com.miui.phrase` 接口保持原有实现。21053 不会写入旧版 `z7.s0` 标志，也不会 Hook 旧版 `nc.a` 元数据工具；系统属性、`AIVersion` 与版本提示入口继续可用。

## 验证

- `:app:assembleDebug`、`:app:assembleRelease` 构建成功，Release 的必要 lint 检查通过。
- `:app:testDebugUnitTest`：17 项测试通过。
- 在已连接的 Android 手机上通过 ART 反射加载真实目标 DEX：21053 通过 82 项校验（含新增的展开、收起动画接口校验）；v209 通过 65 项校验，0.2.599 的旧版结构通过 15 项校验。校验包含 UNKNOWN 分支、版本缓存、材质和配色字段、全部新版方法映射及未变动的词库接口。
- LSPosed 2.2.0 / API 102 日志识别为 `V21053`，AI 三个解析入口、语音三个入口、云端词库和外观 Hook 均成功安装。
- 实机验证中文 `nihao` 候选提交为“你好”、切换英文并输入 `test`、明暗主题键帽/文字配色、剪贴板面板显示，以及多次键盘收起/呼出。测试期间输入法进程保持运行，未发现模块或输入法崩溃。
- 剪贴板动态加载桥接日志显示 `preservedCount=384`；本次没有执行过期等待、超长文本写入或大量新增剪贴板的持久化压力测试。
- 未触发真实云端 AI 拦截或语音审核结果；这些功能验证到 Hook 安装与签名匹配，不能据此保证服务端返回被阻断的内容。
- 系统夜间模式已恢复测试前的 `no`，模块原有配置保留。

本次生成的 `XiaoAiTypeUnblock-2.1.3-21053.apk` 来自 Release 构建，使用本机 Android 调试密钥签名以便覆盖原安装，APK v2/v3 签名和 ZIP 对齐校验通过，并已安装到手机。正式发布时应使用项目的发布签名。

## AI 表达展开动画恢复

用户反馈点击 AI 表达后直接弹出面板。关闭模块外观后，21053 原生面板也直接弹出。对照 0.2.599 的 `hb.l`，旧版附加高度使用 `w.b.c`（animateTo）及阻尼 1、刚度 631 的弹簧；21053 的 `gb.p.r` 三个分支均改为 `w.b.e`（snapTo）。以上调用已核对 smali。

`PanelExpansionAnimationHook` 仅在 V21053 且外观个性化开启时，将 `gb.p` 持有的同一个 Animatable、恢复标签 1/2 的 snapTo 改回旧版弹簧。沿用原协程的挂起、恢复与取消机制；标签 3 的普通收起保留原生行为，其他 Compose 动画不受影响，系统动画倍率不变。

实机录屏中，修复前展开一步完成；修复后浅色和深色主题均出现约 330 毫秒的连续展开。视频证据位于 `work/xiaoai-21053/ai-animation/before.mp4`、`native2.mp4`（关闭外观）、`after.mp4` 和 `dark2.mp4`。模块配置与测试前逐项相同，夜间模式恢复为 `no`。

2.1.3 增加普通键盘 AI 表达的收起动画：原按钮 `lb.c.d0(service)` 会立即清空输入并切换模式；模块改为先设置 `ab.z1.n`（`A()` 的收起状态），使原 Compose 内容保留并通过标签 2 的弹簧缩回。原生 `gb.n` 的第 1 分支在高度小于等于 0.5dp 后回调，此时执行一次原按钮的清理逻辑。重复点击不会重复清理；窗口隐藏、输入结束或服务销毁会取消待完成动作。650ms 的兜底任务只在相同 AI 模式仍处于收起状态时完成清理，不会关闭已经重新展开的面板。

浅色及深色实机录屏确认内容随高度连续收回（`collapse.mp4`、`collapse-dark.mp4`）。连续点击收起、展开中点击收起、收起途中隐藏键盘并重新呼出均未造成面板卡住或输入法崩溃。浮动键盘仍使用原生收起流程；本次未验证浮动布局的收起动画。

## 2.1.4 键盘高度上限开关

模块主界面新增“解除键盘高度上限”，默认关闭，作为全局配置独立于外观及深浅色方案。开启后重启输入法，在菜单的“键盘调节”中向上拖动顶部，仍通过原生确认按钮保存，原生重置按钮恢复默认尺寸。

21053 的 `z9.e6.m(Object, Object)` 第 2 分支将顶部坐标限制在 `[-50, bottom]`；`gb.m0.a(int, MiInputMethodService, s0.p)` 又将矩形转成高度增量时限制在 `[-50, 50]`。两处均已核对 smali 中的 `ed.a.k(int, int, int)` 调用。`KeyboardHeightUnblockHook` 对这两个调用者反优化，并以线程局部作用域仅解除顶部坐标的下限和非负高度增量的上限。匹配后立即消费作用域，保留左右、底部、整体移动、最小高度和原生拖动反馈逻辑。

确认保存路径为 `gb.v0.a()` 第 24 分支 → `m9.e.F(m9.d)`，保存原始顶部坐标，无额外截断。关闭开关时 `ab.z1.l()` 将超出范围的顶部按 -50 返回给布局，不改写持久化数据；重新开启并重启后可恢复已保存的超高尺寸。关闭期间重新确认或重置会按原生操作保存新的尺寸。此功能只安装到 `V21053`，其他代际跳过。

2026-10-01 验证：

- Debug、Release 构建及 Release 必要 lint 检查通过；22 项单元测试通过，包括 5 项新增测试（增高与回缩、底部锚定、其他边界不变、异常清理、线程隔离）。
- 真实 21053 DEX 在 Android ART 中通过 93 项结构校验，含新增拖动分支字段、矩形构造与超高尺寸保持、布局调用及范围函数签名；结果位于 `work/compat-probe-95308018b13b4d8fbbf2f2c2dff687a4/result.txt`。
- 完整 Debug lint 仍报 26 项已有错误，涉及既有 Android API 兼容性、私有 API 和常量使用；逐项对照 Git HEAD，报错行均来自既有代码，本次新增 Hook 无 lint 错误。
- `XiaoAiTypeUnblock-2.1.4-21053.apk` 来自 Release 构建，使用本机 Android 调试密钥签名，v2/v3 签名及 ZIP 对齐校验通过。
- 尚未安装本次模块或在输入法进程中实测顶部拖动、确认后重启恢复、开关切换和横竖屏切换；结构校验不替代实机交互验证。

## 复测

先构建模块，然后在已授权、支持 `su` 的 Android 手机上运行：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
.\scripts\verify-target-compatibility.ps1 `
    -TargetApk '.\超级小爱输入法_0.2.1053.06f65b7c.apk' `
    -ExpectedProfile V21053
```

脚本默认从 `local.properties` 读取 SDK，使用 Android 35 平台及 Build Tools 35.0.0；也可传入 `-SdkPath`、`-Platform`、`-BuildTools`、`-AdbPath`、`-DeviceSerial` 和 `-ModuleApk`。ADB 和 `javac` 需可用。旧版 APK 可分别使用 `-ExpectedProfile V209` 或 `LEGACY`。

脚本只在 `/data/local/tmp/xatype-compat-<GUID>` 中放置测试 DEX 和 APK，通过 `app_process` 执行真实反射校验，不安装或启动这些 APK，不更改输入法与模块设置。结果存储在本机 `work/compat-probe-<GUID>/result.txt`。它验证结构兼容性，不能替代目标进程中的 Hook 行为测试。

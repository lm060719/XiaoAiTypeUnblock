# Issue 9：测试输入框、单手模式与下滑快捷键

实现版本为 **2.2.3.9 / versionCode 69**。最新正式 Release 为 2.2.3；本次在已有的 2.2.3.8 后递增第四段版本。代码已完成本地编译和单元测试；用户随后连接真机，部分运行检查已通过，见 [真机回归记录](2026-11-17_device-issue9-report.md)。范围、授权及设备限制见 [scope](2026-11-17_issue9-scope.md)。本报告为普通兼容性开发记录，`flavor = null`。

## 使用

1. 模块设置页标题下方的输入框可以直接调出当前输入法，支持多行输入和清空。切换主界面标签页时保留内容，进入页面不会主动请求弹出键盘。它测试的是当前实际输入法；需要重启生效的配置，应先重启再测试。
2. 「键盘 → 尺寸与布局 → 单手模式」可选关闭、左手、右手；宽度为 70%～90%，默认 80%，最小 240 dp。首版作用于竖屏普通键盘；横屏、全屏提取输入、悬浮键盘保留原生布局。修改后重启输入法。
3. 「键盘 → 输入增强 → 自定义快捷键」增加方向键、Esc、撤销、恢复、Tab、Enter。「自定义组合键」可选 Ctrl、Alt、Shift，以及字母、数字、方向、编辑键、F1～F12。各键盘分别保存，原有映射和默认分配继续有效。

撤销默认发送 Ctrl+Z，恢复默认发送 Ctrl+Shift+Z。若目标应用使用 Ctrl+Y，可自行配置。每次下滑发送完整组合，随后释放修饰键；并未引入修饰键锁定模式。行为最终取决于目标应用的编辑器。

正在组词时，Esc、撤销、恢复及对应组合仅取消组词，不提交候选、不继续编辑应用文本；再次触发才向应用发送按键。方向键与其他操作先结束组词，再执行。异步上屏后若编辑器已切换，不向新编辑器发送原来的操作。

## 实现与证据

### E-001：宿主原生尺寸模型

- `source_ref`：本地 DEX 索引 `work/abi-21246/idx-1053.pkl`、`work/abi-21358/idx-1246.pkl`、`work/abi-21358/idx-1358.pkl`。
- `content_hash`：n/a，索引为本地生成的离线分析缓存，未作为发布文件。
- `repro_command`：

```powershell
python scripts/verify-one-hand-host.py work/abi-21246/idx-1053.pkl work/abi-21358/idx-1246.pkl work/abi-21358/idx-1358.pkl
```

以上命令依赖对应本地缓存。可使用 `scripts/compat/abi_match/index_dex.py` 从合法持有的宿主 DEX 重新生成索引；只加载本地可信索引。

| 宿主 | 尺寸类型 | 布局调用点 | 静态结果 |
|---|---|---|---|
| 0.2.1053 | `ma.l` | `gb.m0.a` | 唯一类型、六个 int 构造参数、唯一布局调用点 |
| 0.2.1246 | `va.k` | `qb.g0.a` | 同上 |
| 0.2.1358 | `ra.k` | `mb.t0.a` | 同上 |

三者的 `KeyboardDimensionAdjustment` 均包含高度变化、左右边距、底部间距、横向和纵向偏移。`OneHandHook` 仅在原生尺寸布局的调用范围内改写构造参数 1、2、4，保留高度与纵向数据，不写入宿主的调整矩形或持久化设置。宿主类型通过字符串与构造签名解析，无硬编码混淆类名。新增符号后，符号缓存结构版本由 12 更新为 13。

### E-002：快捷键与组词处理

- `source_ref`：`ShortcutKeyChord.kt`、`KeyboardShortcutHostActions.kt`、`KeyboardShortcutsHook.kt`、`Keyboard18Hook.kt` 及相应单元测试。
- `content_hash`：n/a，可用 Git 工作树差异复核。
- `repro_command`：

```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain
```

测试覆盖组合键规范化和映射往返、普通字母不误识别为动作、修饰键按下/抬起顺序、发送被拒或抛异常时的释放尝试，以及取消组词不选词、不提交原始编码。序列化使用 `按键:` 标记，与真实字母和原生按键标签区分。旧的逗号分隔映射仍可读取。

### E-003：构建与检查

- `source_ref`：`app/build/outputs/apk/debug/output-metadata.json`、`app/build/test-results/testDebugUnitTest/`、`app/build/reports/lint-results-debug.xml`。
- `content_hash`：n/a，构建输出可由下列命令重建。
- `repro_command`：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:lintDebug --console=plain
git diff --check
```

| 检查 | 结果 |
|---|---|
| Debug 构建 | 通过 |
| JVM 单元测试 | 289 项，0 失败 |
| 三个宿主的原生尺寸结构 | 通过静态检查 |
| 差异空白检查 | 通过 |
| Lint | 未通过：34 个错误，233 个警告；34 个错误对应的源码行均已存在于 HEAD，主要涉及旧的 API 级别检查和 tint 使用；未屏蔽检查 |
| 真机运行及截图 | 初次构建时未执行；随后完成部分真机检查，见真机回归记录。未启动模拟器 |

测试输入框沿用现有深浅色资源。浅色正文/背景对比度为 16.71:1，提示和边框为 5.62:1；深色分别为 14.08:1、7.38:1。输入框、清空按钮、修饰键选项及宽度滑块触摸区至少为 48 dp。实际字体放大布局仍需真机核对。

## Findings 与调用路径

| Finding | severity | evidence_ids | confidence | location | status |
|---|---|---|---|---|---|
| F-001：可复用宿主尺寸模型实现横向缩窄与偏移 | n/a_re | E-001 | 高：静态结构；运行待验 | `OneHandHook.kt` | 已实现，待真机 |
| F-002：快捷动作需区分取消组词与上屏后执行 | n/a_re | E-002 | 高：源码和单元测试 | 两条键盘动作链路 | 已实现 |
| F-003：本地构建可交付，Lint 有历史阻断项 | n/a_re | E-003 | 高 | 构建及检查输出 | 已记录 |

`P-001 (path_type=callflow)`：宿主尺寸布局 → 线程内限定构造调用 → 调整左右边距与横向偏移 → 原生布局继续计算。依据 E-001 / F-001。

`P-002 (path_type=callflow)`：下滑手势生成带标记的动作键 → 原生或 18/14 键链路检查组词 → 取消并消费，或上屏后执行 → `InputConnection` 发送配对按键事件及修饰键释放。依据 E-002 / F-002。

## 真机待测

使用 ADB 明确指定真实设备序列号，安装与设备现有签名兼容的模块 APK，并重启输入法。分别检查左右手、70%/80%/90% 宽度、横竖屏切换、悬浮键盘、九键/26 键/形码/18/14 键，以及按键触摸、长按弹窗、候选栏和玻璃外观的位置。对原生文本框、WebView、终端等应用分别测试方向键、撤销恢复和自定义组合键。组词中优先取消的行为，应同时覆盖旧版同步宿主与 0.2.1358 的协程链路。

其中 0.2.1363 中文 26 键的左右手、70% / 80% / 90% 宽度、按键命中和部分快捷动作已验证。剩余项目见真机记录，静态匹配不能替代这些验证。

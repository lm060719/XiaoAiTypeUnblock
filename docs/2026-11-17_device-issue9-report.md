# Issue 9 真机回归记录

模块 **2.2.3.9 / versionCode 69** 已安装到用户连接的真机。在 Android 17、超级小爱输入法 **0.2.1363.60772063（21363）**、竖屏中文 26 键环境中，已确认下列 **15 项有效检查通过**。测试范围和授权见 [scope](2026-11-17_issue9-scope.md)。本次未修改功能实现，也未改变对外版本号；新增了可重复使用的真机回归入口。

## 已确认通过

| 检查 | 数量 | 依据 |
|---|---:|---|
| 测试输入框切页保留内容、清空 | 2 | 真实触摸切换「键盘」「外观」「概览」，检查同一 EditText 的文本 |
| 左右方向键、上下方向键 | 2 | 实际下滑手势；光标在三行测试文本间移动，校验选区位置 |
| 自定义 Ctrl+A | 1 | 实际下滑后，完整选中测试文本，文本内容保持不变 |
| 撤销、恢复与自定义 Ctrl+Shift+Z | 1 | 通过真实 EditText 输入连接写入字符，再下滑并核对编辑历史 |
| 空闲时 Esc、Alt+Left、Enter | 3 | 监听目标输入框收到的按键；核对 Alt 修饰状态及多行换行结果 |
| 左手、右手各 70% / 80% / 90% | 6 | 每档重启输入法；测量 Q/P 按键边界、检查贴边和输入命中 |

模块适配报告在宿主 21363 上的 `missing` 列表为空。测试时间范围内的 AndroidRuntime 日志未发现 `io.mo.xatype` 或 `com.xiaomi.type` 的致命异常记录。

截图：[自定义快捷动作](../work/issue9-device/shortcuts-configured.png)、[左手 70%](../work/issue9-device/onehand-1-70.png)、[右手 80%](../work/issue9-device/onehand-2-80.png)。这些是本机测试产物，`work/` 不进入版本控制。当前单手实现缩窄并偏移按键、候选和工具栏；宿主背景仍铺满输入法窗口。

## 未判定通过的项目

- 组词期间的取消行为：Esc 和恢复有成功观察，但撤销、自定义组合取消的探索性断言出现失败。截图中的候选栏与无障碍树采集结果存在时序差异，期间也发生前台切换，尚不足以判定功能缺陷。宿主把未上屏拼音保留在候选栏，不能以测试输入框必须出现 `ni` 作为前提。
- 通过组合键配置页完成保存：测试中前台切换到其他应用，配置页断言未完成。组合键的保存界面仍需单独复核；组合键执行已通过上述实际手势检查。
- 长按弹窗、横屏、悬浮模式、九键、形码、18/14 键、其他应用编辑器：本次未完成完整运行验证。

以上项目不计入通过数量。探索性脚本的失败记录不能等同于宿主功能失败。

## 测试方法与清理

测试只通过 ADB 连接明确的真实设备序列号 `66064e1f`；未启动模拟器。安装新模块前，保存了旧 APK 与模块设置备份。临时设置包含快捷键映射、标签、启动动画、单手方向和宽度；结束后逐键比较，六个设置均恢复原值。临时测试 APK `io.mo.xatype.test` 已卸载，手机保留 2.2.3.9 模块。

回归入口位于 `app/src/androidTest/java/io/mo/xatype/Issue9DeviceInstrumentation.java`。保留的自动化用例覆盖本次已确认的 15 项检查，重新构建通过；执行时应保持模块前台，避免同时操作手机。入口会备份和恢复测试修改的设置，并拒绝在模块失去前台时向其他应用注入手势。

```powershell
.\gradlew.bat :app:assembleDebugAndroidTest --console=plain
& 'E:/Android/Sdk/platform-tools/adb.exe' -s 66064e1f install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
& 'E:/Android/Sdk/platform-tools/adb.exe' -s 66064e1f shell am instrument -w -e case suite io.mo.xatype.test/io.mo.xatype.Issue9DeviceInstrumentation
```

## Evidence → Finding → Path

| Evidence | source_ref | repro_command | content_hash |
|---|---|---|---|
| E-101：有效真机断言、截图和尺寸测量 | 本机 `work/issue9-device/`；上述回归入口 | 上述 `am instrument` 命令；须安装目标测试版并保持前台 | n/a，离线测试产物 |
| E-102：设置恢复 | `work/issue9-device/settings-restoration.json` | 对比测试前后 XML 中六个被修改的键 | n/a |
| E-103：运行异常检查 | `work/issue9-device/runtime-errors.log` | `adb -s 66064e1f shell logcat -d -T '11-17 21:20:00.000' -v threadtime -s AndroidRuntime:E`，筛选模块及输入法进程 | n/a，日志为当次设备状态 |

F-101（severity=`n/a_re`，evidence_ids=`E-101`，confidence=高，location=中文 26 键，status=部分验证）：上述输入、快捷动作与单手尺寸行为已有运行证据；其他场景保持待验证。

F-102（severity=`n/a_re`，evidence_ids=`E-102,E-103`，confidence=高，location=测试清理，status=完成）：测试改动的设置已恢复，采集范围内未发现模块或输入法致命异常。

P-101（path_type=`callflow`）：写入临时映射 → 确认输入法进程重启且键盘可见 → 注入真实手势 → 校验目标 EditText 与按键边界 → 恢复设置并重启输入法。依据 E-101 / E-102。

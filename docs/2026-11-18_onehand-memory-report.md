# 单手模式入口合并与方向记忆

版本：`2.2.3.18 / 78`。正式基准仍为 Release `2.2.3`，在已生成的 `.17` 之上递增小修订号。

## 最终行为

- 下滑动作和键帽提示显示「左手」「右手」。既有「左手模式／右手模式」绑定在读取时兼容转换，无需重新配置；默认按键分配不变。
- 菜单只加入一个「单手模式」按钮。关闭时点击恢复上次方向，开启时点击关闭。首次没有方向记录时默认右手。
- 上次方向独立存储为 `pref_one_hand_last_side`；设置页选方向、菜单切换和下滑操作统一维护记忆。关闭不清除记忆，重启输入法后仍可恢复。旧版本正在使用左侧布局时，首次关闭会保留左侧方向。
- 继续复用原生布局即时刷新；保留编辑框文本、光标及未完成的组词。

## 验证

`:app:assembleDebug`、`:app:assembleDebugAndroidTest` 成功，304 项单元测试全部通过，`git diff --check` 通过。

ADB 真机 `66064e1f`、宿主 `0.2.1448 / 21448`、竖屏中文 26 键的六项回归全部通过：

1. 单个菜单按钮首次进入右手，再次点击关闭。
2. 下滑选择左手、关闭后，菜单恢复左手。
3. 下滑选择右手、关闭并重启输入法后，菜单恢复右手。
4. 旧绑定的左右下滑切换与关闭正常，文本及光标不变。
5. 下滑切换保留组词，缩窄后的按键命中正常。
6. 普通即时切换不重启输入法进程。

已目视核对菜单只有「单手模式」，键帽为「左手／右手」。测试结束全部设置键值与本轮开始前一致，临时测试 APK 已卸载；手机保留 `.18` 新模块。未启动模拟器。

本轮沿用既有宿主绑定，不涉及新的宿主分析。其他布局已编译验证，未追加逐布局的真机回归。此前全量 Lint 的存量错误记录见 [前一修订验证报告](2026-11-18_onehand-menu-report.md)。

## 本地产物与复现

构建与单元测试日志、真机日志、截图和设置恢复记录位于 `work/onehand-memory-20261118/`，该目录不进入版本控制。

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
adb -s 66064e1f install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 66064e1f install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s 66064e1f shell am instrument -w -e case onehand io.mo.xatype.test/io.mo.xatype.Issue9DeviceInstrumentation
```

交付：`work/onehand-memory-20261118/XiaoAiTypeUnblock-2.2.3.18-debug.apk`。

# 单手模式菜单与下滑快捷操作

版本：`2.2.3.17 / 77`。正式基准来自 GitHub 最新 Release `2.2.3`；已有工作树修订为 `.16`，本次递增为 `.17`。

## 使用方式

打开输入法菜单，点击「左手模式」或「右手模式」即可切换并返回键盘。再次触发当前方向恢复正常宽度；选择另一方向直接换边。方向自动保存，触发时无需重启输入法。

「键盘 → 输入增强 → 自定义快捷键」新增同名两个动作，九键、26 键、18 键、14 键均可分别绑定。默认按键分配保持原值。布局动作在输入收尾前处理，保留当前组词和编辑框内容。宽度仍在原单手设置中调整。

## 实现与验证

- 菜单使用宿主卡片、颜色、字号及触摸目标，在宿主分页之前加入两个入口；工具栏管理的编辑模式不加入这两项。没有新增配色。
- 宿主布局组合读取独立的快照状态；触发时更新设置并使该组合重新计算原生尺寸，按键测量、触摸位置与候选栏同步更新。
- 新增写入接口仅允许模块自身及输入法调用，并限制方向为 `0..2`。
- `:app:assembleDebug`、`:app:assembleDebugAndroidTest` 成功；303 项单元测试全部通过；`git diff --check` 通过。
- 真机 6 项回归全部通过：菜单左侧开启、菜单直接换右侧、同方向关闭、下滑左右切换与关闭且文本/光标不变、保留组词与缩窄按键命中、切换期间进程不重启。80% 宽度的按键范围由 `13..1427 px` 变为左侧 `13..1137 px` 或右侧 `303..1427 px`。
- 0.2.1053、0.2.1246、0.2.1448 的完整指纹探针及缓存重载通过，包含四个新增菜单符号。
- 最终 Lint 仍报 37 项错误、235 项警告。37 项错误全部位于本次未修改的文件，例如 `KeyboardStyleHook.kt` 的 API 等级检查；本次涉及文件无新增 Lint 错误。
- 临时测试设置与任务开始时逐键比较后完全恢复；测试 APK `io.mo.xatype.test` 已卸载。真机保留新模块。

运行覆盖限于竖屏原生中文 26 键。其他布局的接入已编译验证，尚未逐一做真机交互回归；横屏、全屏提取和悬浮键盘沿用原单手布局策略。设置页修改方向或宽度仍按原流程重启应用，菜单与下滑动作可即时切换。

## 范围与证据链

授权和限制见 [scope](2026-11-18_onehand-menu-scope.md)。类型为普通宿主适配分析，`flavor = null`，不涉及漏洞或攻击结论。`work/` 为本地验证产物，不进入版本控制。

| Evidence | source_ref | repro_command | content_hash |
|---|---|---|---|
| E-101：构建与单元测试 | `work/onehand-build-final.log`；`app/build/test-results/testDebugUnitTest/` | `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest` | n/a |
| E-102：真机六项回归 | `work/onehand-device-test.log`；`work/onehand-menu-20261118/results.json`；同目录 `menu-left.png`、`menu-right.png`、`swipe-right.png` | 安装 debug 与 androidTest APK 后执行 `adb -s 66064e1f shell am instrument -w -e case onehand io.mo.xatype.test/io.mo.xatype.Issue9DeviceInstrumentation` | results.json SHA-256：`5cfd479c8caea059e35b659c3c0baad0d36445c381280521b917ccabff2df649` |
| E-103：宿主绑定 | `work/onehand-fingerprints-legacy.log`；`work/onehand-fingerprints-21448.log` | `scripts/verify-host-fingerprints.ps1 -TargetApk <本地宿主APK> -DeviceSerial 66064e1f -RequireKey keyboard.oneHandMenuGrid,keyboard.oneHandMenuCard,keyboard.oneHandMenuPanel,keyboard.oneHandMenuChunk`；本地 APK 不随仓库分发 | n/a |
| E-104：静态检查及设置恢复 | `work/onehand-lint-final.log`；`work/onehand-menu-20261118/settings-restoration.json` | `.\gradlew.bat :app:lintDebug`；设置恢复结果为所有键值相同 | n/a |

F-101：`severity=n/a_re`，`evidence_ids=E-101,E-102,E-103`，`confidence=高`，`location=OneHandHook.kt / OneHandMenuHook.kt / 快捷键处理器`，`status=validated`：真机菜单与下滑动作共用方向切换，立即更新原生布局并保留组词。

P-101：`path_type=callflow`：菜单卡片或下滑动作 → `OneHandHook.perform` → 受限 Provider 保存方向 → 更新独立快照状态 → 宿主布局重组 → 原生尺寸模型与触摸位置同步（E-102 / F-101）。

时间线（2026-11-18，UTC+8）：21:54 开始检查现有实现；22:18 首次构建与安装；22:23 完成菜单分页及当前宿主绑定验证；22:31 完成旧宿主绑定验证；22:33 真机六项回归通过；22:34 恢复全部设置、卸载测试 APK并核对最终 Lint。

交付 APK：`work/onehand-menu-20261118/XiaoAiTypeUnblock-2.2.3.17-debug.apk`，SHA-256：`88f636cd2f632bb98a4cac3d9bce3588739f95ca8dc3e18e985763d64ee5b6eb`。

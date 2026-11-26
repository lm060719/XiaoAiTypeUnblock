# 原生振动增强真机测试

结论：原生滑块扩展、200 保存恢复、真实按键发送 2.0 强度及关闭后限制为 1.0 均已验证。用户在手持设备进行 100/200 对比后表示“好像大了一点点，感觉不是很明显”。因此本机结论为：功能链路生效，主观体感可能略增强，但提升不明显；没有实际振幅测量，不能描述为振动力度翻倍。

## 环境与范围

| 项目 | 值 |
|---|---|
| 日期 / 设备 | 2026-11-18；ADB 真机 `66064e1f`，`25019PNF3C` / `xuanyuan` |
| 已安装模块 | `2.2.3.16` / versionCode 76，未覆盖安装旧版本 |
| 输入法 | `0.2.1448.5cf769fe` / versionCode 21448 |
| 系统触觉反馈 | `haptic_feedback_enabled=1`，保持不变 |
| 模块适配 | 宿主上报 `missing=[]`；强度读取符号为 `Lj9/e;->b(ILjava/lang/String;)I` |
| 测试前设置 | 模块增强 false；原生模式 custom，保存强度 200 |

授权来自用户本轮真机测试请求及随后开始 A/B 对比的明确回复。范围限于当前模块、输入法设置和振动链路，不写入振动驱动参数，不操作其他应用内容，不启动模拟器。离线范围记录为 `work/device-haptics-20261118/scope.md`。

## 过程与结果

1. 开启模块增强并重启输入法。原生输入框的实际按键记录为 `intensity=2.0`，系统记录 `finished`。
2. 在原生按键反馈页将滑块置于中点，原生保存值变为 100；真实键盘连续三个按键均记录为 `intensity=1.0`、`Prebaked=7`、`finished`，持续约 63～65 ms。
3. 将同一原生滑块拖到右端，保存值变为 200；退出页面再进入，右端位置和保存值恢复。未改为独立振动算法。
4. 用户第一次未手持手机，因此不把前面的操作作为体感证据。用户选择开始对比后，使用系统命令分别重放同一个 7 号预置效果：100 对应 1.0，200 对应 2.0，各三次，间隔约 0.8 秒。重放记录的调用者是 `com.android.shell`，与实际键盘 `com.xiaomi.type` 记录分开保存。
5. 用户反馈第二组可能略强，但差别不明显。
6. 关闭模块增强，保留原生保存值 200。再次真实按键请求变为 `intensity=1.0`，正常播放，确认关闭回退生效。恢复原设置并清空测试框。

系统服务的 Java 实现解析强度标记后，直接执行 `mHalExt.setAmplitudeExt(intensity2, 1)`。此处未见把 2.0 裁剪为 1.0；但该代码不能证明 HAL/驱动内部的增益曲线、限值或马达的实际振幅。100 与 200 的预置效果编号和持续时间近似，不能仅据持续时间判定振幅相同或翻倍。

## Evidence → Finding → Path

以下产物位于本机忽略的 `work/device-haptics-20261118/`，不随仓库分发。`content_hash=n/a` 表示可按命令重新采集的日志/截图或交互观察，不作独立样本哈希证明。

| E-id | source_ref | repro_command / 采集方式 | content_hash |
|---|---|---|---|
| E-001 | `enabled-200-vibrations.txt` | 开启增强、原生值 200 后真实按键；`adb -s 66064e1f shell dumpsys vibrator_manager`，仅保存宿主记录 | n/a |
| E-002 | `100-keypresses.txt` | 原生值 100 时真实连续按键，记录 21:58:22.783、21:58:23.459、21:58:24.122 的三次 1.0 / finished | n/a |
| E-003 | `200-restored-confirmed.png` 与原生首选项读取 | 原生滑块右端后退出再进入；读取 `/data/user_de/0/com.xiaomi.type/shared_prefs/mi_ime_prefs.xml` 的振动键 | n/a |
| E-004 | `ab-native-effect-replay.txt`、用户回复 | 下方系统命令分别以 1.0 / 2.0 各重放三次；用户反馈“好像大了一点点，感觉不是很明显” | n/a |
| E-005 | `disabled-saved-200.txt`、`toggle-off-confirmed.png` | 关闭模块增强，保留保存值 200 后真实按键；22:06:44.306 记录为 1.0 / finished | n/a |
| E-006 | `services-jadx/sources/com/android/server/vibrator/VibratorManagerServiceImpl.java` | 从本机拉取 `/system_ext/framework/miui-services.jar`，用 JADX 单类反编译对应系统服务 | n/a |

| F-id | severity | evidence_ids | confidence | location | status |
|---|---|---|---|---|---|
| F-001 | n/a_re | E-001、E-002、E-003 | high | 宿主原生滑块与实际按键 | 已验证：0～200 范围可用，保存恢复，100/200 分别请求 1.0/2.0 |
| F-002 | n/a_re | E-005 | high | 原生强度读取器 | 已验证：关闭后保存 200 仍限制实际请求为 1.0 |
| F-003 | n/a_re | E-004、E-006 | medium | 厂商反馈链路及用户手感 | 已观察：用户可能感到略强，提升不明显；实际振幅和驱动是否限幅未定量验证 |

P-001（path_type=callflow）：原生滑块保存 100/200 → 原生强度读取器 → 原生管理器 /100.0 → 系统振动请求理由含 1.0/2.0 → 系统服务传入厂商振动扩展接口 → 系统播放记录 finished → 用户主观对比。关闭模块开关时，保存 200 的读取结果被限制为 100，之后请求恢复为 1.0。

## 关键复现命令

在已获授权的同一真机上执行；先通过原生页面设置强度，再在模块测试框实际按键。以下读取命令只保留振动相关键与宿主事件：

```powershell
adb -s 66064e1f shell su -c 'cat /data/user/0/io.mo.xatype/shared_prefs/settings.xml' | Select-String 'pref_native_vibration_limit'
adb -s 66064e1f shell su -c 'cat /data/user_de/0/com.xiaomi.type/shared_prefs/mi_ime_prefs.xml' | Select-String 'vibration'
adb -s 66064e1f shell dumpsys vibrator_manager | Select-String 'com.xiaomi.type' | ForEach-Object { $_.Line } | Sort-Object | Select-Object -Last 10
```

手感对比使用下方命令，以相同预置效果和 usage 重放。该命令不是键盘输入，也不用于代替真实按键链路验证：

```powershell
adb -s 66064e1f shell cmd vibrator_manager synced -u 18 -d 'TAG=haptic_feedback_infinite_intensity\;intensity=1.0\;reason=USAGE_PHYSICAL_EMULATION\;keyword=MIUI_SDK' prebaked 7
adb -s 66064e1f shell cmd vibrator_manager synced -u 18 -d 'TAG=haptic_feedback_infinite_intensity\;intensity=2.0\;reason=USAGE_PHYSICAL_EMULATION\;keyword=MIUI_SDK' prebaked 7
```

## 时间线与收尾

21:54～21:56：确认版本、原始设置及增强后的真实 2.0 请求。21:57～21:59：原生 100 实际按键、200 保存恢复。22:03：用户准备后进行同效果 100/200 各三次手感对比。22:06：关闭后实际请求回到 1.0，原生保存值仍为 200。

已恢复：模块增强 false，原生 custom / 200，系统触觉反馈仍为 1，测试文字清空。未改动源代码或版本号；ReconBridge 会话已关闭，未安装额外 Hook。

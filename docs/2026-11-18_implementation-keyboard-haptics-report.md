# 提高原生振动滑块上限

模块新增“键盘 → 按键反馈 → 提高原生振动上限”开关。开启后，输入法原有振动滑块从 0～100 扩展为 0～200；原有区间含义不变，原生算法、振动开关、模式选择和滑块试振继续生效。模块不创建独立振动效果，也不提供另一组强度、时长滑块。版本 `2.2.3.15`，内部构建号 `75`；正式 Release 基准仍为 `2.2.3`。

## 使用方法与范围

安装模块后，在模块的键盘页开启“提高原生振动上限”，重启输入法，然后到输入法自己的按键反馈设置中选择“自定义”振动模式，调整原有滑块。关闭模块开关后重新打开原生页面，滑块恢复原上限；实际读出的强度也限制到 100，避免此前保存的 150、200 继续生效。原生保存值保留，重新开启时可继续使用。

用户明确要求提高原生滑块上限。本次范围为当前模块与已有本地 APK/DEX 索引的功能开发和静态验证；不包含独立振动播放、系统范围的振动拦截或其他应用的反馈修改。未修改宿主 APK；当前无已连接真机，未启动模拟器。离线工作目录另有 `work/keyboard-haptics-20261118/scope.md`，该目录不随仓库分发。

## 原生路径与实现

原生页面查找 `kf_vibration_intensity`，读取 `vibration_intensity`，再通过原生回调保存并试振。原生管理器在自定义模式下使用 `保存值 / 100.0` 调用 MIUI 触觉反馈。因此扩展后的 150、200 分别继续传入 1.5、2.0；不将原有 50 重新映射成其他强度。

`NativeVibrationSlider` 在原生 `SeekBarPreference(Context, AttributeSet)` 构造完成、保存值恢复之前，核对 preference key，只将该对象唯一值为 100 的整数最大值字段改为 200。该时点 value/min/increment 均不是 100；如字段不唯一则拒绝修改并报告不可用。音量等其他滑块保持原样。

`HostFingerprints` 根据原生分发方法的三个设置字符串、两个参数和 void 返回类型定位强度读取器。`KeyboardHapticsHook` 对读取键 `vibration_intensity` 限定范围：开启时 0～200，关闭时 0～100。原生分发仍完整执行；不覆盖原生振动开关或模式，不 Hook 系统振动服务。模块无新增振动权限。

## 证据链

| E-id | source_ref | repro_command | content_hash / 限制 |
|---|---|---|---|
| E-001 | `work/keyboard-haptics-20261118/latest/sources/y9/b.java` | 下方 JADX 命令，`--single-class y9.b` | SHA-256：`EFD14D54EBB923C7B6AD07E4D203FBC0FD7E7CA1CD5ACE879D742EEBCBAF6EC0`；需本地宿主 APK |
| E-002 | `work/keyboard-haptics-20261118/latest/sources/androidx/preference/SeekBarPreference.java` 与 APK `res/Im.xml` | 下方 JADX / aapt 命令 | n/a，可重新生成；确认原生最大值 100、最小值 0，并在构造后用最大值裁剪保存值 |
| E-003 | `work/keyboard-haptics-20261118/host-verification.txt` | 下方 Python 命令 | n/a；需可信本地 DEX 索引，检查强度读取器唯一匹配、原生构造函数及设置页面 |
| E-004 | `app/build/test-results/testDebugUnitTest/` 与 `app/build/reports/lint-results-debug.xml` | 下方 Gradle 命令 | n/a，报告可重新生成 |

当前 `21448` 宿主 APK SHA-256：`9F07D968FEAB0E5C4DEBDBF7D8F91980FF5993003AC2877194784169C820B357`。

| F-id | severity | evidence_ids | confidence | location | status |
|---|---|---|---|---|---|
| F-001 | n/a_re | E-001、E-002 | high | 原生振动页面、强度读取与管理器 | 确认：原生范围 0～100，保存值按 /100.0 参与原生反馈 |
| F-002 | n/a_re | E-003 | high | `keyboard.hapticIntensity` / 原生 SeekBarPreference | 八版本静态匹配条件通过，真机 DexKit 扫描待验证 |
| F-003 | n/a_re | E-004 | high | 模块构建和单测 | Debug 编译及 299 项单测通过；全项目 Lint 仍有 37 项原有错误、234 项警告，新增振动实现无 Lint 发现 |

P-001（path_type=callflow）：原生页面创建振动滑块 → 验证 key 并扩展 max → 原生设置恢复保存值 → 原生滑块回调保存/试振 → 原生分发读取强度 → 模块根据开关限定范围 → 原生 /100.0 计算和播放。依据 E-001～E-004。

## 复现命令

在项目根目录执行；本地 APK 和索引属于离线分析产物，不随仓库分发：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
python scripts/verify-keyboard-haptics-host.py work/idx/0.2.701.pkl work/idx/0.2.790.pkl work/idx/0.2.910.pkl work/idx/0.2.974.pkl work/idx/0.2.1053.pkl work/abi-21358/idx-1246.pkl work/abi-21358/idx-1358.pkl work/abi-21448/idx-21448.pkl
& 'C:\Users\limo2\Tools\jadx\bin\jadx.bat' --single-class y9.b --no-res -d work/keyboard-haptics-20261118/latest 'C:\Users\limo2\AppData\Local\ReconBridge\work\com.xiaomi.type\apk\base.apk'
& 'C:\Users\limo2\Tools\jadx\bin\jadx.bat' --single-class androidx.preference.SeekBarPreference --no-res -d work/keyboard-haptics-20261118/latest 'C:\Users\limo2\AppData\Local\ReconBridge\work\com.xiaomi.type\apk\base.apk'
& 'E:\Android\Sdk\build-tools\34.0.0\aapt.exe' dump xmltree 'C:\Users\limo2\AppData\Local\ReconBridge\work\com.xiaomi.type\apk\base.apk' res/Im.xml
adb devices -l
```

静态检查覆盖 0.2.701、0.2.790、0.2.910、0.2.974、0.2.1053、0.2.1246、0.2.1358、0.2.1448。`lintDebug` 因原有问题失败，首项是 `KeyboardStyleHook.kt:410` 的 API 33 `RuntimeShader`；编译和单测任务成功。APK 为 `app/build/outputs/apk/debug/app-debug.apk`。

## 验证边界与时间线

2026-11-18：根据用户要求确认原生滑块、保存和反馈路径；实现 max 扩展及关闭时的读取上限；完成八版本静态检查、构建、299 项单测和 Lint 检查。仍需在真实设备验证原生页面显示 200、试振与保存恢复，并确认该系统是否接受大于 1.0 的强度请求。超过 100 不保证突破系统/马达的物理上限，也不保证体感线性翻倍。

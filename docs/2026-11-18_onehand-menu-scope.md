# 单手模式菜单与下滑快捷键：范围

- auth：用户本轮要求单手模式可在键盘菜单点击触发，并在下滑快捷操作中提供左、右两个方向。
- in_scope：当前项目菜单集成、单手布局即时刷新、快捷动作、设置保存、版本修订、构建及现有 ADB 真机验证。
- targets：本地宿主 APK 和已连接真机 `66064e1f` 上的 `com.xiaomi.type`、模块 `io.mo.xatype`。真机宿主为 `0.2.1448.5cf769fe / 21448`。
- network_profile：GitHub Release 只读查询；不发布、不推送、不修改远程记录。
- constraints：禁止启动模拟器；真机使用明确序列号。安装前备份旧 APK 和设置；测试结束恢复设置并卸载临时测试 APK。
- preserved：保留任务开始时已有的 `docs/2026-11-18_device-keyboard-haptics-report.md`，不作修改。

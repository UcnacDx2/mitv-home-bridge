# MiTV Home Bridge

独立维护的 MiTV 桌面桥接 APK。它只执行已经在目标 ROM 上验证过的包/组件操作；
Binder 能力不足时才回退到 `su`。

## 桌面护栏

从 `com.mitv.tvhome` 打开本应用时，流程固定为：

1. 先处理 `com.xiaomi.mitv.settings/.entry.FallbackHome`；
2. 枚举可响应 `HOME` 且可以启动的第三方桌面；
3. 没有候选桌面时恢复 FallbackHome，并保留原厂桌面；
4. 让用户选择一个可启动的第三方桌面；
5. 先通过 TvService 禁用 `com.mitv.tvhome/.MainActivityUserMode`，再通过同一链路设置默认 Home 并启动它。

从其他入口打开时，bridge 会重新启用原厂桌面并尝试启动它。

目标 ROM 的主路径是 `service call TvService 4400`：由 `misysdiagnose` 以临时
`uid=0` 执行脚本，脚本再调用 `/system/bin/service call package`。已在 finch/Android 14
上验证 transaction 83、86、80 分别可用于组件禁用、包禁用和设置 Home。桌面主流程优先使用
TvService；只有 TvService 和直接 Binder 都明确失败时，才使用 `su` 回退。恢复小米桌面时，
若组件已通过 TvService 启用但受保护 Activity 仍不能启动，则提示用户按遥控器主页键。

## 已确认组件

`com.xiaomi.mitv.upgrade` 在 bridge 启动时直接设置为 disabled。该行为不依赖桌面选择，
Binder 失败时回退到 `pm disable-user`，并保留恢复命令供调试使用。

## 构建

Windows：

```powershell
.\build.ps1
```

脚本优先使用 `ANDROID_SDK_ROOT`，否则使用 `%LOCALAPPDATA%\Android\Sdk`，并自动选择
android-35 或 android-36 平台。产物为 `MiTVHomeBridge.apk`。

设备兼容性、Binder 权限和 ROM 行为必须通过上层工程的 ADB PoC 单独确认；本仓库不把
未验证的 ROM 假设写成兼容性承诺。

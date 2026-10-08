# MiTV Home Bridge

独立维护的 MiTV 桌面桥接 APK。它只执行已经在目标 ROM 上验证过的包/组件操作；
主路径是 TvService 临时 root，失败时回退到 `su`。

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
TvService；TvService 失败时才使用 `su` 回退。应用进程直接调用 package Binder 的路径在本
ROM 上必然被拒绝（PMS 要求 `CHANGE_COMPONENT_ENABLED_STATE`，普通应用无法持有），因此
不保留该路径。恢复小米桌面时，
若组件已通过 TvService 启用但受保护 Activity 仍不能启动，则提示用户按遥控器主页键。

## 已确认组件

`com.xiaomi.mitv.upgrade` 在 bridge 启动时直接设置为 disabled。该行为不依赖桌面选择，
TvService 失败时回退到 `su` 执行 `pm disable-user`，并保留恢复命令供调试使用。

## 安装器限制

`com.android.packageinstaller` 受厂商 `pi_config` 开关约束，开关打开时原厂安装器会拒绝
侧载。bridge 启动时（同样不依赖桌面选择）按固定顺序执行：

```
appops set --user 0 com.android.packageinstaller WRITE_SETTINGS deny
settings --user 0 delete system pi_config
settings --user 0 put system pi_config '{"pi_intercept_switch":false,"app_pi_control":false}'
am force-stop --user 0 com.android.packageinstaller
```

先 deny `WRITE_SETTINGS`，安装器才无法把 `pi_config` 写回拦截状态；先 delete 再 put，
避免旧值在写入被拒绝时残留；最后重启安装器进程使其重新读取配置。

这四条**只有 root 能执行**：appop 属于别的包，需要 `MANAGE_APP_OPS_MODES`；
`settings` / `am --user 0` 需要 `MANAGE_USERS`。普通应用在 shell 档被拒，TvService 档也被拒
（该通道只能转发 Binder 调用，无法执行 `cmd` 分发）。因此这里直接走 `su`，**没有回退链路**：
设备未提供 su 或被拒绝时整段跳过，只写日志，不弹提示。想让它稳定生效，用
[mitv-optimizer](https://github.com/UcnacDx2/mitv-optimizer) 的 root service——它会在开机时
执行同样的序列。

未验证的 ROM 不适用以上假设；该行为需要在目标设备上通过 ADB 单独确认。

## 构建

Windows：

```powershell
.\build.ps1
```

脚本优先使用 `ANDROID_SDK_ROOT`，否则使用 `%LOCALAPPDATA%\Android\Sdk`，并自动选择
android-35 或 android-36 平台。产物为 `MiTVHomeBridge.apk`。

设备兼容性、Binder 权限和 ROM 行为必须通过上层工程的 ADB PoC 单独确认；本仓库不把
未验证的 ROM 假设写成兼容性承诺。

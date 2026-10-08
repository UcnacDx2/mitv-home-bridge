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

有 root 时这四条一次走 `su` 完成。没有 root 时分成两档：

- `appops` 与 `am force-stop` 换成 TvService 域内的原生 Binder 调用，不需要 root：
  `service call appops 31 i32 23 i32 <安装器 uid> s16 com.android.packageinstaller i32 2`
  和 `service call activity 83 s16 com.android.packageinstaller i32 0`。两条在
  finch / OS3.0.115.0.UFFMATV 上实测有效：appop 改完后用 `dumpsys appops` 回读为
  `WRITE_SETTINGS (deny)`，force-stop 调用被框架接受。注意 `forceStopPackage` 返回 void，
  它的回执无法证明进程真被杀（对一个不存在的包调用时回执一模一样），只有 appop 那一步
  有真回读。
- `settings delete/put system pi_config` 在这个域里**没有**第二条链路：`settings` 只是
  `/system/bin/cmd` 的包装，而这个域既不能执行 `cmd`，也取不到 settings 的 Binder
  （服务描述符为空）。所以没有 root 时这一步 bridge 自己做不到，只能由用户手动补，见下一节。

### 没有 root 时手动清 pi_config

`pi_config`（`system` 命名空间）是厂商下发的安装器拦截配置，里面带一份来源黑名单列表，
开关打开时原厂安装器会按该列表拒绝侧载。它由安装器经 `appstore-upgrade.tv.mi.com`
拉取刷新，**不是设备本地生成的**——在 finch 上，mitv-optimizer 改动它之前
`settings get system pi_config` 回的是 `null`，说明本机原本没有这一项，vendor 缺省下拦截
是开着的。所以「只删不写回」并不等于解除拦截，必须写进一个关闭拦截的值。

这一步和上面两条相反：**普通 adb shell 就能写，不需要 root**。2026-10-08 在
finch / OS3.0.115.0.UFFMATV 上以 uid 2000 的 adb shell 实测——写进一个安装器不识别的
附加键，回读能看到该键，说明写入真的落盘，而不是被静默忽略：

```sh
adb shell settings --user 0 put system pi_config '{"pi_intercept_switch":false,"app_pi_control":false}'
```

不要把这两类混起来：`appops` 与 `am force-stop` 在 TvService 域里能做、在 adb shell 里做不了；
`pi_config` 反过来，在 TvService 域里做不了、在 adb shell 里能做。装过
[mitv-optimizer](https://github.com/UcnacDx2/mitv-optimizer)（有 Magisk）的话，它的 root
service 每次开机都会替用户做掉这一步，不需要手动补。

两个 transaction 号（31 / 83）与 op 下标（23）取自本机型，会随 ROM 版本漂移，所以 su 路径
始终优先。事务号写错时框架回 `Result: Parcel(Error: ... "Not a data message")`，脚本据此判失败，
这一点已实测。设备未提供 su 时不再弹提示，只写日志。

脚本的 stdout 与退出码都不回传，所以脚本要把结论写进 `/sdcard/Download/` 下的结果文件，应用
再轮询读回。每次调用都会分配一对**独立**文件名（`mitv-home-bridge.<n>.sh` / `.result`）：
Home 切换与安装器限制在两个线程上并发调用 TvService，早期版本用固定文件名，实测出现过一个
线程的清理删掉另一个线程的结论文件，让已经生效的 force-stop 被读成失败。0.3.5 起按调用编号
隔离。

0.3.5 在 finch / OS3.0.115.0.UFFMATV 上以"应用无 su"状态实测（把该 uid 的 Magisk su 策略置为
deny）：appop 从 `allow` 改到 `deny` 并由宿主回读确认，两步都拿到各自的 `OK` 结论，
`pi_config` 未被改动。

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

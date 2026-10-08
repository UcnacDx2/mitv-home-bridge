package com.ucnacdx2.mitvhomebridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {
    private static final String TAG = "MiTVHomeBridge";
    private static final String TVHOME_PACKAGE = "com.mitv.tvhome";
    private static final String FALLBACK_PACKAGE = "com.xiaomi.mitv.settings";
    private static final String UPGRADE_PACKAGE = "com.xiaomi.mitv.upgrade";
    private static final String INSTALLER_PACKAGE = "com.android.packageinstaller";
    private static final String PI_CONFIG_KEY = "pi_config";
    private static final String PI_CONFIG_VALUE =
        "{\"pi_intercept_switch\":false,\"app_pi_control\":false}";
    private static final String HOME_PERMISSION = "com.mitv.tvhome.permission.HOME_STATE";

    private static final ComponentName TVHOME = new ComponentName(
        TVHOME_PACKAGE, "com.mitv.tvhome.MainActivityUserMode");
    private static final ComponentName FALLBACK = new ComponentName(
        FALLBACK_PACKAGE, "com.xiaomi.mitv.settings.entry.FallbackHome");
    private static final int STORAGE_REQUEST = 1001;
    private boolean bridgeStarted;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // The bridge is a short-lived controller. Keep the active Home visible
        // while the Binder operation runs and suppress ROM window transitions.
        getWindow().setWindowAnimations(0);
        getWindow().setDimAmount(0f);
        if (Build.VERSION.SDK_INT >= 23
            && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, STORAGE_REQUEST);
            return;
        }
        startBridge();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == STORAGE_REQUEST) startBridge();
    }

    private void startBridge() {
        if (bridgeStarted) return;
        bridgeStarted = true;
        final boolean fromTvHome = isLaunchedFromTvHome();
        new Thread(() -> {
            disableUpgrade();
            if (fromTvHome) runOnUiThread(this::prepareAlternativeHome);
            else openTvHome();
        }, "MiTvHomeBridge").start();
        // A first su call can block on the Magisk grant prompt, which must not
        // hold up the Home switch.
        new Thread(this::removeInstallerRestriction, "MiTvHomeBridge-Installer").start();
    }

    private void prepareAlternativeHome() {
        // FallbackHome is handled first, but is restored if safety checks fail.
        if (!setComponent(FALLBACK, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)) {
            finishOnUi("无法禁用 FallbackHome，未修改原厂桌面");
            return;
        }
        List<ResolveInfo> homes = findAlternativeHomes();
        if (homes.isEmpty()) {
            setComponent(FALLBACK, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
            finishOnUi("未找到可用的第三方桌面，已保留原厂桌面");
            return;
        }
        if (homes.size() > 1) {
            setComponent(FALLBACK, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
            new AlertDialog.Builder(this)
                .setMessage("请仅保留一个第三方桌面后重试")
                .setPositiveButton("确定", (dialog, which) -> finishWithoutTransition())
                .setOnCancelListener(dialog -> finishWithoutTransition())
                .show();
            return;
        }
        String[] labels = new String[homes.size()];
        for (int i = 0; i < homes.size(); i++) {
            ActivityInfo info = homes.get(i).activityInfo;
            CharSequence label = info.loadLabel(getPackageManager());
            labels[i] = (label == null ? info.packageName : label.toString())
                + "\n" + info.packageName;
        }
        AlertDialog.Builder chooser = new AlertDialog.Builder(this)
            .setTitle("选择默认桌面");
        chooser.setCancelable(false)
            .setSingleChoiceItems(labels, -1, (dialog, which) -> {
                dialog.dismiss();
                activateAlternativeHome(homes.get(which));
            })
            .setNegativeButton("取消", (dialog, which) -> {
                setComponent(FALLBACK, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
                finishWithoutTransition();
            }).show();
    }

    private void activateAlternativeHome(ResolveInfo resolveInfo) {
        ComponentName home = new ComponentName(resolveInfo.activityInfo.packageName,
            resolveInfo.activityInfo.name);
        if (!canStartHome(home)) {
            setComponent(FALLBACK, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
            finishOnUi("所选桌面无法启动，已保留原厂桌面");
            return;
        }
        // This ROM ignores setHomeActivity while the vendor Home component is
        // still enabled. A usable third-party Home is the safety gate; disable
        // the vendor component first, then set the preferred Home.
        if (!setComponent(TVHOME, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)) {
            setComponent(FALLBACK, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
            finishOnUi("TvService 无法禁用原厂桌面，已保留原厂桌面");
            return;
        }
        boolean defaultSet = runTvServiceHome(home);
        if (!defaultSet) {
            // TvService failed. Only now is a su fallback justified; a stale
            // resolver result must not reach it.
            defaultSet = runRoot("cmd package set-home-activity --user 0 "
                + shellQuote(home.flattenToString()));
        }
        if (!defaultSet) {
            setComponent(TVHOME, PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
            setComponent(FALLBACK, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
            finishOnUi("TvService 无法设置默认桌面，已恢复原厂桌面");
            return;
        }
        try {
            Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setComponent(home).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(launch);
        } catch (RuntimeException error) {
            Log.w(TAG, "alternative Home launch failed", error);
            setComponent(FALLBACK, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
            finishOnUi("所选桌面启动失败，已保留原厂桌面");
            return;
        }
        finishWithoutTransition();
    }

    private List<ResolveInfo> findAlternativeHomes() {
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> resolved = getPackageManager().queryIntentActivities(intent,
            PackageManager.MATCH_DEFAULT_ONLY);
        Map<String, ResolveInfo> unique = new LinkedHashMap<>();
        for (ResolveInfo item : resolved) {
            if (item.activityInfo == null) continue;
            String pkg = item.activityInfo.packageName;
            if (TVHOME_PACKAGE.equals(pkg) || FALLBACK_PACKAGE.equals(pkg)
                || getPackageName().equals(pkg)) continue;
            if (isPackageUsable(pkg)) unique.put(pkg, item);
        }
        return new ArrayList<>(unique.values());
    }

    private boolean isPackageUsable(String packageName) {
        try {
            getPackageManager().getApplicationInfo(packageName, 0);
            return getPackageManager().getLaunchIntentForPackage(packageName) != null;
        } catch (PackageManager.NameNotFoundException error) {
            return false;
        }
    }

    private boolean canStartHome(ComponentName home) {
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .setComponent(home);
        return getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null;
    }

    private boolean isResolvedHome(ComponentName expected) {
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo resolved = getPackageManager().resolveActivity(intent,
            PackageManager.MATCH_DEFAULT_ONLY);
        return resolved != null && resolved.activityInfo != null
            && expected.getPackageName().equals(resolved.activityInfo.packageName);
    }

    private void openTvHome() {
        if (!setComponent(TVHOME, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)) {
            finishOnUi("TvService 无法启用小米桌面");
            return;
        }
        try {
            Intent intent = new Intent("com.mitv.tvhome.HOME_PAGE")
                .setComponent(TVHOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            if (checkSelfPermission(HOME_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
                startActivity(intent);
            } else {
                // TvService can restore the component, but this ROM rejects
                // ActivityManager transactions from misysdiagnose. Try su for
                // the launch itself, then give the user a reliable recovery.
                if (!runRoot("am start -a com.mitv.tvhome.HOME_PAGE -n "
                    + shellQuote(TVHOME.flattenToString()))) {
                    finishOnUi("小米桌面已启用，请按一下遥控器主页键启动");
                    return;
                }
            }
            finishWithoutTransition();
        } catch (RuntimeException error) {
            Log.w(TAG, "TvService-enabled Xiaomi Home could not be started", error);
            finishOnUi("小米桌面已启用，请按一下遥控器主页键启动");
        }
    }

    private void disableUpgrade() {
        setPackageEnabled(UPGRADE_PACKAGE, PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
    }

    // The vendor installer carries its own intercept switch in the pi_config
    // system setting; while it is set, the stock PackageInstaller refuses
    // sideloads. Denying the installer's WRITE_SETTINGS appop stops it from
    // rewriting the setting, which is what lets the cleared value stick.
    //
    // Every command below needs root: the appop belongs to another package, so
    // MANAGE_APP_OPS_MODES is required, and `settings`/`am --user 0` need
    // MANAGE_USERS. A normal app is refused on the plain-shell and TvService
    // tiers, which reach Binder services but not `cmd` dispatch, so there is no
    // fallback to fall back to. When su is absent or declined the sequence is
    // skipped; mitv-optimizer applies the same sequence from its root service.
    private void removeInstallerRestriction() {
        boolean applied = runRoot("appops set --user 0 " + INSTALLER_PACKAGE
            + " WRITE_SETTINGS deny");
        // Delete before the put so a stale value cannot survive if the ROM
        // rejects the replacement.
        applied &= runRoot("settings --user 0 delete system " + PI_CONFIG_KEY);
        applied &= runRoot("settings --user 0 put system " + PI_CONFIG_KEY + " "
            + shellQuote(PI_CONFIG_VALUE));
        applied &= runRoot("am force-stop --user 0 " + INSTALLER_PACKAGE);
        if (!applied) Log.i(TAG, "no su; installer restriction left to mitv-optimizer");
    }

    private static boolean passed(OperationCheck check) {
        return check == null || check.passed();
    }

    private boolean setComponent(ComponentName component, int state) {
        if (runTvServiceComponent(component, state)) return true;
        Log.w(TAG, "TvService component operation failed; trying su for " + component);
        return setComponentViaRoot(component, state);
    }

    // Last resort. `pm disable` writes COMPONENT_ENABLED_STATE_DISABLED (2) rather
    // than `disable-user` (3), because this ROM rejects 3 for the vendor Home with
    // "invalid new component state: 3" even for uid 0 - the su path would report
    // success and change nothing. The exit code proves nothing either, since `pm`
    // exits 0 for a change the framework refused, so the state is read back the
    // way the TvService path does.
    private boolean setComponentViaRoot(ComponentName component, int state) {
        String command = "pm " + (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            ? "enable" : state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            ? "disable" : "default-state") + " --user 0 "
            + shellQuote(component.flattenToString());
        if (!runRoot(command)) return false;
        int applied = getComponentState(component);
        Log.i(TAG, "su component state=" + applied + " wanted=" + state + " for " + component);
        return applied == state;
    }

    private boolean setPackageEnabled(String packageName, int state) {
        if (runTvServicePackage(packageName, state)) return true;
        Log.w(TAG, "TvService package operation failed; trying su for " + packageName);
        return runRoot("pm disable-user --user 0 " + shellQuote(packageName));
    }

    private boolean runTvServiceComponent(ComponentName component, int state) {
        String script = "#!/system/bin/sh\n"
            + "/system/bin/service call package 83 i32 1 s16 "
            + shellQuote(component.getPackageName()) + " s16 "
            + shellQuote(component.getClassName()) + " i32 " + state
            + " i32 0 i32 0 s16 " + shellQuote(getPackageName()) + "\n";
        return runTvServiceScript(script, () -> getComponentState(component) == state);
    }

    private boolean runTvServicePackage(String packageName, int state) {
        String script = "#!/system/bin/sh\n"
            + "/system/bin/service call package 86 s16 " + shellQuote(packageName)
            + " i32 " + state + " i32 0 i32 0 s16 " + shellQuote(getPackageName()) + "\n";
        return runTvServiceScript(script, () -> {
            try {
                return getPackageManager().getApplicationEnabledSetting(packageName) == state;
            } catch (RuntimeException error) {
                return false;
            }
        });
    }

    private boolean runTvServiceHome(ComponentName home) {
        String script = "#!/system/bin/sh\n"
            + "/system/bin/service call package 80 i32 1 s16 "
            + shellQuote(home.getPackageName()) + " s16 "
            + shellQuote(home.getClassName()) + " i32 0\n";
        return runTvServiceScript(script, () -> canStartHome(home));
    }

    private interface OperationCheck {
        boolean passed();
    }

    private boolean runTvServiceScript(String script, OperationCheck check) {
        File scriptFile = new File(Environment.getExternalStorageDirectory(),
            "Download/mitv-home-bridge.sh");
        try {
            File parent = scriptFile.getParentFile();
            if (parent != null) parent.mkdirs();
            try (FileOutputStream output = new FileOutputStream(scriptFile, false)) {
                output.write(script.getBytes("UTF-8"));
            }
            Process process = new ProcessBuilder("/system/bin/service", "call", "TvService",
                "4400", "s16", "s", "s16", scriptFile.getAbsolutePath())
                .redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null && output.length() < 2048) {
                    output.append(line).append('\n');
                }
            }
            int exit = process.waitFor();
            Thread.sleep(400);
            boolean ok = exit == 0 && passed(check);
            Log.i(TAG, "TvService operation exit=" + exit + " passing=" + ok
                + " output=" + output.toString().trim());
            return ok;
        } catch (Throwable error) {
            Log.w(TAG, "TvService operation unavailable", error);
            return false;
        } finally {
            // The file is intentionally overwritten for every operation. Do not
            // leave a reusable root script containing stale package names.
            if (scriptFile.exists()) scriptFile.delete();
        }
    }

    private int getComponentState(ComponentName component) {
        try {
            return getPackageManager().getComponentEnabledSetting(component);
        } catch (RuntimeException error) {
            return -1;
        }
    }

    private boolean isLaunchedFromTvHome() {
        if (TVHOME_PACKAGE.equals(getCallingPackage())) return true;
        if (isTvHomeReferrer(getReferrer())) return true;
        Intent intent = getIntent();
        if (intent == null) return false;
        try {
            if (isTvHomeReferrer(intent.getParcelableExtra(Intent.EXTRA_REFERRER))) return true;
        } catch (RuntimeException ignored) { }
        String name = intent.getStringExtra(Intent.EXTRA_REFERRER_NAME);
        return name != null && name.contains(TVHOME_PACKAGE);
    }

    private boolean isTvHomeReferrer(Uri referrer) {
        return referrer != null && (TVHOME_PACKAGE.equals(referrer.getHost())
            || referrer.toString().contains(TVHOME_PACKAGE));
    }

    private boolean runRoot(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null && output.length() < 4096) {
                    output.append(line).append('\n');
                }
            }
            int exit = process.waitFor();
            Log.i(TAG, "root exit=" + exit + " output=" + output.toString().trim());
            return exit == 0;
        } catch (Exception error) {
            Log.w(TAG, "root command failed", error);
            return false;
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private void finishOnUi(String message) {
        runOnUiThread(() -> {
            if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            finishWithoutTransition();
        });
    }

    private void finishWithoutTransition() {
        finish();
        overridePendingTransition(0, 0);
    }
}

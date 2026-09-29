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
import android.os.IBinder;
import android.os.Parcel;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {
    private static final String TAG = "MiTVHomeBridge";
    private static final String TVHOME_PACKAGE = "com.mitv.tvhome";
    private static final String FALLBACK_PACKAGE = "com.xiaomi.mitv.settings";
    private static final String UPGRADE_PACKAGE = "com.xiaomi.mitv.upgrade";
    private static final String HOME_PERMISSION = "com.mitv.tvhome.permission.HOME_STATE";

    private static final ComponentName TVHOME = new ComponentName(
        TVHOME_PACKAGE, "com.mitv.tvhome.MainActivityUserMode");
    private static final ComponentName FALLBACK = new ComponentName(
        FALLBACK_PACKAGE, "com.xiaomi.mitv.settings.entry.FallbackHome");

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // The bridge is a short-lived controller. Keep the active Home visible
        // while the Binder operation runs and suppress ROM window transitions.
        getWindow().setWindowAnimations(0);
        getWindow().setDimAmount(0f);
        final boolean fromTvHome = isLaunchedFromTvHome();
        new Thread(() -> {
            disableUpgrade();
            if (fromTvHome) runOnUiThread(this::prepareAlternativeHome);
            else openTvHome();
        }, "MiTvHomeBridge").start();
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
        String[] labels = new String[homes.size()];
        for (int i = 0; i < homes.size(); i++) {
            ActivityInfo info = homes.get(i).activityInfo;
            CharSequence label = info.loadLabel(getPackageManager());
            labels[i] = (label == null ? info.packageName : label.toString())
                + "\n" + info.packageName;
        }
        new AlertDialog.Builder(this)
            .setTitle("选择默认桌面")
            .setCancelable(false)
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
        boolean defaultSet = setHomeViaPackageService(home);
        if (!defaultSet) {
            // TvService and the direct Binder path both failed. Only now is a
            // su fallback justified; a stale resolver result must not reach it.
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

    private boolean setComponent(ComponentName component, int state) {
        if (runTvServiceComponent(component, state)) return true;
        if (setComponentViaPackageService(component, state)) return true;
        Log.w(TAG, "TvService and package Binder failed; trying su for " + component);
        return runRoot("pm " + (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            ? "enable" : state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            ? "default-state" : "disable-user") + " --user 0 "
            + shellQuote(component.flattenToString()));
    }

    private boolean setPackageEnabled(String packageName, int state) {
        if (runTvServicePackage(packageName, state)) return true;
        if (setApplicationViaPackageService(packageName, state)) return true;
        Log.w(TAG, "TvService and package Binder failed; trying su for " + packageName);
        return runRoot("pm disable-user --user 0 " + shellQuote(packageName));
    }

    private boolean setHomeViaPackageService(ComponentName home) {
        if (runTvServiceHome(home)) return true;
        try {
            Class<?> stub = Class.forName("android.content.pm.IPackageManager$Stub");
            Method asInterface = stub.getDeclaredMethod("asInterface", IBinder.class);
            asInterface.setAccessible(true);
            Object service = asInterface.invoke(null, packageBinder());
            Method method = service.getClass().getMethod("setHomeActivity", ComponentName.class, int.class);
            method.setAccessible(true);
            Object result = method.invoke(service, home, 0);
            return !(result instanceof Boolean) || (Boolean) result;
        } catch (Throwable error) {
            Log.w(TAG, "Binder setHomeActivity unavailable", error);
            return false;
        }
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
            boolean passed = exit == 0 && check.passed();
            Log.i(TAG, "TvService operation exit=" + exit + " passed=" + passed
                + " output=" + output.toString().trim());
            return passed;
        } catch (Throwable error) {
            Log.w(TAG, "TvService operation unavailable", error);
            return false;
        } finally {
            // The file is intentionally overwritten for every operation. Do not
            // leave a reusable root script containing stale package names.
            if (scriptFile.exists()) scriptFile.delete();
        }
    }

    private boolean setComponentViaPackageService(ComponentName component, int state) {
        Parcel data = null;
        Parcel reply = null;
        try {
            Class<?> stub = Class.forName("android.content.pm.IPackageManager$Stub");
            Field transaction = stub.getDeclaredField("TRANSACTION_setComponentEnabledSetting");
            transaction.setAccessible(true);
            data = Parcel.obtain();
            reply = Parcel.obtain();
            data.writeInterfaceToken("android.content.pm.IPackageManager");
            data.writeInt(1);
            component.writeToParcel(data, 0);
            data.writeInt(state);
            data.writeInt(0);
            data.writeInt(0);
            if (Build.VERSION.SDK_INT >= 34) data.writeString(getPackageName());
            IBinder binder = packageBinder();
            if (!binder.transact(transaction.getInt(null), data, reply, 0)) return false;
            reply.readException();
            return getComponentState(component) == state;
        } catch (Throwable error) {
            Log.w(TAG, "Binder component operation failed: " + component, error);
            return false;
        } finally {
            if (reply != null) reply.recycle();
            if (data != null) data.recycle();
        }
    }

    private boolean setApplicationViaPackageService(String packageName, int state) {
        try {
            Class<?> stub = Class.forName("android.content.pm.IPackageManager$Stub");
            Method asInterface = stub.getDeclaredMethod("asInterface", IBinder.class);
            asInterface.setAccessible(true);
            Object service = asInterface.invoke(null, packageBinder());
            Method method = service.getClass().getMethod("setApplicationEnabledSetting",
                String.class, int.class, int.class, int.class, String.class);
            method.setAccessible(true);
            method.invoke(service, packageName, state, 0, 0, getPackageName());
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "Binder package operation failed: " + packageName, error);
            return false;
        }
    }

    private IBinder packageBinder() throws Exception {
        Class<?> manager = Class.forName("android.os.ServiceManager");
        Method getService = manager.getDeclaredMethod("getService", String.class);
        getService.setAccessible(true);
        IBinder binder = (IBinder) getService.invoke(null, "package");
        if (binder == null) throw new IllegalStateException("package service unavailable");
        return binder;
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

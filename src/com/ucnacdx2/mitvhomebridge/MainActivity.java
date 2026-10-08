package com.ucnacdx2.mitvhomebridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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

    // Raw Binder transactions for the installer-restriction steps that have no
    // `cmd` front end in the misysdiagnose domain: IAppOpsService.setMode and
    // IActivityManager.forceStopPackage. The two numbers and the op index below
    // were measured on this ROM (finch, OS3.0.115.0.UFFMATV); they drift between
    // releases, which is why the su path stays first.
    private static final int TRANSACTION_APP_OPS_SET_MODE = 31;
    private static final int TRANSACTION_FORCE_STOP_PACKAGE = 83;
    private static final int OP_WRITE_SETTINGS = 23;
    // Every TvService script gets its own pair of files, named "<prefix><n>.sh"
    // and "<prefix><n>.result". Two threads drive TvService at once - the Home
    // switch and the installer restriction - and they share nothing else: a fixed
    // name let one thread's cleanup delete the other's verdict (measured on the
    // TV: force-stop read back empty because the Home thread had just removed the
    // file), and let one thread's script overwrite the other's.
    private static final String FILE_PREFIX = "mitv-home-bridge.";
    private static final AtomicInteger OPERATION_SEQUENCE = new AtomicInteger();
    // Script stdout never comes back through TvService - the reply is always an
    // empty Parcel - so a script that has to report an outcome writes it into
    // this file and the app reads it back.
    private static final String VERDICT_SUFFIX = ".result";
    private static final String SCRIPT_SUFFIX = ".sh";
    private static final String DOWNLOAD_DIR = "Download/";
    // TvService copies the script to /data/diagnosis/command.sh before running it,
    // so a script cannot derive its own directory from $0 and has to name the
    // verdict file outright. The domain reaches external storage as /sdcard, where
    // the app sees the same file under its Environment download directory.
    private static final String DEVICE_DOWNLOAD_DIR = "/sdcard/Download/";

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
    // With root the whole sequence runs at once, because the settings provider -
    // reachable only as root - has to be written between the appop and the
    // force-stop. Without root the appop and the force-stop still work through
    // TvService (see below); the setting itself is left to the user and to
    // mitv-optimizer's root service.
    private void removeInstallerRestriction() {
        if (applyInstallerRestrictionAsRoot()) return;
        applyInstallerRestrictionViaTvService();
    }

    private boolean applyInstallerRestrictionAsRoot() {
        if (!runRoot("appops set --user 0 " + INSTALLER_PACKAGE
            + " WRITE_SETTINGS deny")) {
            return false;
        }
        // Delete before the put so a stale value cannot survive if the ROM
        // rejects the replacement.
        boolean configured = runRoot("settings --user 0 delete system " + PI_CONFIG_KEY);
        configured &= runRoot("settings --user 0 put system " + PI_CONFIG_KEY + " "
            + shellQuote(PI_CONFIG_VALUE));
        boolean stopped = runRoot("am force-stop --user 0 " + INSTALLER_PACKAGE);
        if (!configured || !stopped) {
            Log.w(TAG, "su installer restriction incomplete: configured=" + configured
                + " stopped=" + stopped);
        }
        return true;
    }

    // `settings`, `appops` and `am` are all thin wrappers around `cmd` here, and
    // the misysdiagnose domain cannot run `cmd`: it fails with rc=127 before the
    // request reaches the framework. The same operations as raw Binder calls do
    // go through, because that domain is uid 0.
    private void applyInstallerRestrictionViaTvService() {
        int uid;
        try {
            uid = getPackageManager().getApplicationInfo(INSTALLER_PACKAGE, 0).uid;
        } catch (PackageManager.NameNotFoundException error) {
            Log.w(TAG, "no " + INSTALLER_PACKAGE + " on this ROM", error);
            return;
        }
        boolean denied = denyInstallerWriteSettings(uid);
        boolean stopped = forceStopInstaller();
        Log.i(TAG, "TvService installer restriction appop=" + denied
            + " forceStop=" + stopped + "; " + PI_CONFIG_KEY + " left to mitv-optimizer");
    }

    private boolean denyInstallerWriteSettings(int uid) {
        String verdict = runTvServiceScript(verdictPath -> tvServiceScript(verdictPath)
            + "OUT=$(/system/bin/service call appops " + TRANSACTION_APP_OPS_SET_MODE
            + " i32 " + OP_WRITE_SETTINGS + " i32 " + uid + " s16 " + INSTALLER_PACKAGE
            + " i32 " + AppOpsManager.MODE_ERRORED + " 2>&1)\n"
            // A transaction number this ROM no longer uses answers "Not a data
            // message" instead of reaching the service, which is the failure the
            // guard is here for.
            + "case \"$OUT\" in\n"
            + "  *Error*|*'does not exist'*) echo \"FAIL call=[$OUT]\" > $V; exit;;\n"
            + "esac\n"
            // appops prints one block per package, and WRITE_SETTINGS is the only
            // op this package overrides, so the line under its header is a readback
            // of what was just set rather than a restatement of the call.
            + "STATE=$(/system/bin/dumpsys appops 2>/dev/null"
            + " | /system/bin/grep -A1 'Package " + INSTALLER_PACKAGE + ":'"
            + " | /system/bin/grep WRITE_SETTINGS)\n"
            + "case \"$STATE\" in\n"
            + "  *deny*) echo \"OK $STATE\" > $V;;\n"
            + "  *) echo \"FAIL state=[$STATE]\" > $V;;\n"
            + "esac\n", true);
        return acceptsVerdict(verdict);
    }

    private boolean forceStopInstaller() {
        String verdict = runTvServiceScript(verdictPath -> tvServiceScript(verdictPath)
            + "OUT=$(/system/bin/service call activity " + TRANSACTION_FORCE_STOP_PACKAGE
            + " s16 " + INSTALLER_PACKAGE + " i32 0 2>&1)\n"
            // forceStopPackage returns void, so its reply cannot say whether the
            // process was killed - a force-stop of a package that does not exist
            // answers exactly like a real one. The appop readback above is the one
            // step here that is verified; this only catches a call the framework
            // refused or a transaction number the ROM moved.
            + "case \"$OUT\" in\n"
            + "  *Error*|*'does not exist'*) echo \"FAIL $OUT\" > $V;;\n"
            + "  *) echo \"OK $OUT\" > $V;;\n"
            + "esac\n", true);
        return acceptsVerdict(verdict);
    }

    private static String tvServiceScript(String verdictPath) {
        return "#!/system/bin/sh\nV=" + verdictPath + "\n";
    }

    private static boolean acceptsVerdict(String verdict) {
        return verdict != null && verdict.startsWith("OK ");
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

    // Scripts that write a verdict need the path of their own result file, which
    // only exists once the call has been given its sequence number.
    private interface TvServiceScript {
        String build(String verdictPath);
    }

    private boolean runTvServiceScript(String script, OperationCheck check) {
        String verdict = runTvServiceScript(verdictPath -> script, false);
        boolean ok = verdict != null && passed(check);
        Log.i(TAG, "TvService operation passing=" + ok);
        return ok;
    }

    // Runs the script as uid 0 inside the misysdiagnose domain. Returns the
    // verdict it wrote, or null when the call itself could not be made; an empty
    // string means the script wrote nothing, which is normal for the operations
    // the app verifies itself.
    private String runTvServiceScript(TvServiceScript script, boolean waitForVerdict) {
        String operation = FILE_PREFIX + OPERATION_SEQUENCE.incrementAndGet();
        String verdictPath = DEVICE_DOWNLOAD_DIR + operation + VERDICT_SUFFIX;
        File scriptFile = new File(Environment.getExternalStorageDirectory(),
            DOWNLOAD_DIR + operation + SCRIPT_SUFFIX);
        File verdictFile = new File(Environment.getExternalStorageDirectory(),
            DOWNLOAD_DIR + operation + VERDICT_SUFFIX);
        try {
            File parent = scriptFile.getParentFile();
            if (parent != null) parent.mkdirs();
            try (FileOutputStream output = new FileOutputStream(scriptFile, false)) {
                output.write(script.build(verdictPath).getBytes("UTF-8"));
            }
            // Cleared first, so a script that dies early leaves an empty verdict
            // rather than the previous operation's answer.
            try (FileOutputStream ignored = new FileOutputStream(verdictFile, false)) { }
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
            String verdict = exit == 0 ? readVerdict(verdictFile, waitForVerdict) : null;
            Log.i(TAG, "TvService operation exit=" + exit + " verdict="
                + (verdict == null ? "none" : "\"" + verdict + "\"")
                + " output=" + output.toString().trim());
            return verdict;
        } catch (Throwable error) {
            Log.w(TAG, "TvService operation unavailable", error);
            return null;
        } finally {
            // Both names belong to this call alone, so removing them cannot touch
            // a concurrent operation's script or verdict. Do not leave a reusable
            // root script containing stale package names.
            if (scriptFile.exists()) scriptFile.delete();
            if (verdictFile.exists()) verdictFile.delete();
        }
    }

    // The appop readback dumps the whole ops table, which is slow enough that the
    // verdict is polled rather than slept on. Scripts with nothing to report only
    // need the beat the app checks its own state in.
    private static String readVerdict(File verdictFile, boolean waitForVerdict)
        throws InterruptedException {
        for (int attempt = 0; attempt < (waitForVerdict ? 20 : 3); attempt++) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(verdictFile), "UTF-8"))) {
                String line = reader.readLine();
                if (line != null) return line.trim();
            } catch (IOException missing) {
                // Not flushed yet, or never written at all.
            }
            Thread.sleep(150);
        }
        return "";
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

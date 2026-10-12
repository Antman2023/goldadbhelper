package io.github.devicebridge.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

/** Pure fixture tests. No shell, ADB, network, downloads, installs, or real devices. */
public final class ActivationTests {
    private static int assertions;
    private ActivationTests() { }
    public static void main(String[] args) throws Exception { run(); }
    public static void run() throws Exception {
        assertions = 0;
        catalogue(); commands(); probes(); guards(); results();
        System.out.println("ActivationTests: " + assertions + " assertions passed (no device actions)");
    }
    private static void catalogue() throws Exception {
        equal(7, ActivationPresets.all().size());
        Set<String> ids = new HashSet<String>();
        Set<String> packages = new HashSet<String>();
        int supported = 0;
        for (ActivationPresets.Preset p : ActivationPresets.all()) {
            check(ids.add(p.id), "unique IDs"); check(packages.add(p.packageName), "unique packages");
            check(p == ActivationPresets.get(p.id), "canonical preset instance");
            check(!p.name.isEmpty() && !p.versionScope.isEmpty(), "name and version scope");
            check(!p.requiredConditions.isEmpty() && !p.consequence.isEmpty(), "conditions and effect");
            check(!p.verificationInstructions.isEmpty(), "verification guidance");
            check(p.sourceUrl.startsWith("https://"), "primary source or explicitly limited context");
            check(p.historicalScope.contains("1.3.1") && p.historicalScope.contains("版本未知"), "historical scope honest");
            check(!p.historicalCommand.isEmpty(), "all original interface strings preserved as history only");
            check(p.serviceVerificationCommands.isEmpty(), "no guessed service-health commands");
            rejects(() -> p.requiredConditions.clear(), UnsupportedOperationException.class);
            if (p.isSupported()) {
                supported++;
                check(!p.command.isEmpty(), "documented command present");
                equal("", p.unavailableReason);
            } else {
                equal("", p.command); equal("", p.scriptProbeCommand());
                check(!p.unavailableReason.isEmpty(), "block reason present");
            }
        }
        equal(4, supported);
        equal("moe.shizuku.privileged.api", ActivationPresets.get("shizuku").packageName);
        equal("com.catchingnow.icebox", ActivationPresets.get("icebox").packageName);
        equal("me.piebridge.brevent", ActivationPresets.get("brevent").packageName);
        equal("web1n.stopapp", ActivationPresets.get("stopapp").packageName);
        equal("com.web1n.permissiondog", ActivationPresets.get("permissiondog").packageName);
        equal("com.modosa.apkinstaller", ActivationPresets.get("apkinstaller").packageName);
        equal("com.omarea.vtools", ActivationPresets.get("scene5").packageName);
        rejects(() -> ActivationPresets.get(null), IllegalArgumentException.class);
        rejects(() -> ActivationPresets.get("shizuku; reboot"), IllegalArgumentException.class);
        rejects(() -> ActivationPresets.get("Shizuku"), IllegalArgumentException.class);
        rejects(() -> ActivationPresets.all().clear(), UnsupportedOperationException.class);
        for (Constructor<?> c : ActivationPresets.Preset.class.getDeclaredConstructors())
            check(c.isSynthetic() || Modifier.isPrivate(c.getModifiers()), "callers cannot create unreviewed presets");
        for (Field f : ActivationPresets.Preset.class.getFields())
            check(Modifier.isFinal(f.getModifiers()), "metadata immutable");
    }
    private static void commands() {
        equal("sh '/sdcard/Android/data/moe.shizuku.privileged.api/start.sh'", ActivationPresets.get("shizuku").buildCommand());
        equal("sh '/sdcard/Android/data/com.catchingnow.icebox/files/start.sh'", ActivationPresets.get("icebox").buildCommand());
        equal("sh '/sdcard/Android/data/web1n.stopapp/files/demon.sh'", ActivationPresets.get("stopapp").buildCommand());
        check(!ActivationPresets.get("stopapp").command.contains("starter.sh"), "no silent legacy fallback");
        String b = ActivationPresets.get("brevent").buildCommand();
        check(b.startsWith("output=$(pm path 'me.piebridge.brevent') || exit $?;"), "package query guarded");
        check(b.contains("*'\n'*|*'\r'*) exit 64"), "multiple paths and CR rejected");
        check(b.contains("package:/*) ;; *) exit 64"), "absolute pm path only");
        check(b.contains("export CLASSPATH=\"${output#package:}\""), "path stays quoted data");
        check(b.contains("[ -r \"$CLASSPATH\" ] && app_process"), "APK readability checked");
        check(b.contains("'me.piebridge.brevent.server.BreventServer' 'bootstrap' && /system/bin/sh '/data/local/tmp/brevent.sh'"), "failed bootstrap stops chain");
        check(!b.contains("/data/data/me.piebridge"), "does not pretend old path current");
        for (ActivationPresets.Preset p : ActivationPresets.all()) {
            check(!p.command.contains("wget") && !p.command.contains("curl"), "no script download");
            check(!p.command.contains("monkey") && !p.command.contains("am start"), "no automatic app launch");
            check(!p.command.contains("set-device-owner"), "no executable owner enrollment");
            check(p.command.indexOf('\0') < 0, "no NUL");
        }
    }
    private static void probes() {
        ActivationPresets.Preset p = ActivationPresets.get("shizuku");
        equal("pm list packages --user 0 'moe.shizuku.privileged.api'", p.packageProbeCommand());
        rejects(() -> p.packageProbeCommand(10), IllegalArgumentException.class);
        rejects(() -> p.packageProbeCommand(-1), IllegalArgumentException.class);
        equal(ActivationPresets.PackagePresence.INSTALLED, pkg(p, 0, "package:moe.shizuku.privileged.api\n", ""));
        equal(ActivationPresets.PackagePresence.INSTALLED, pkg(p, 0, "package:moe.shizuku.privileged.api\r\n", ""));
        equal(ActivationPresets.PackagePresence.NOT_FOUND, pkg(p, 0, "", ""));
        equal(ActivationPresets.PackagePresence.NOT_FOUND, pkg(p, 0, "package:moe.shizuku.privileged.api.fake\n", ""));
        equal(ActivationPresets.PackagePresence.NOT_FOUND, pkg(p, 0, "package:moe.shizuku.privileged\n", ""));
        equal(ActivationPresets.PackagePresence.UNKNOWN, pkg(p, -1, "package:moe.shizuku.privileged.api\n", ""));
        equal(ActivationPresets.PackagePresence.UNKNOWN, pkg(p, 1, "", ""));
        equal(ActivationPresets.PackagePresence.UNKNOWN, pkg(p, 0, null, ""));
        equal(ActivationPresets.PackagePresence.UNKNOWN, pkg(p, 0, "package:moe.shizuku.privileged.api\n", "Permission denied"));
        equal(ActivationPresets.PackagePresence.UNKNOWN, pkg(p, 0, "Error: unknown option --user\n", ""));
        equal(ActivationPresets.PackagePresence.UNKNOWN, pkg(p, 0, "package:moe.shizuku.privileged.api\nError: transport lost", ""));
        equal(ActivationPresets.PackagePresence.UNKNOWN, pkg(p, 0, "package:moe.shizuku.privileged.api;reboot\n", ""));
        equal(ActivationPresets.PackagePresence.UNKNOWN, pkg(p, 0, "package:moe.shizuku.privileged.api\0\n", ""));
        rejects(() -> pkg(null, 0, "", ""), IllegalArgumentException.class);
        check(p.scriptProbeCommand().contains("[ -f '/sdcard/Android/data/moe.shizuku.privileged.api/start.sh' ] && [ -r "), "file existence and readability separately checked");
        check(!p.scriptProbeCommand().contains("sh '/"), "read-only probe never invokes script");
        check(!p.scriptProbeCommand().contains("printf"), "old Android read-only probe needs no printf");
        equal(ActivationPresets.ScriptPresence.READABLE, script(0, "READABLE\n", ""));
        equal(ActivationPresets.ScriptPresence.READABLE, script(0, "READABLE\r\n", ""));
        equal(ActivationPresets.ScriptPresence.READABLE, script(0, "READABLE", ""));
        equal(ActivationPresets.ScriptPresence.MISSING_OR_INACCESSIBLE, script(0, "MISSING_OR_INACCESSIBLE\n", ""));
        equal(ActivationPresets.ScriptPresence.UNKNOWN, script(-1, "READABLE\n", ""));
        equal(ActivationPresets.ScriptPresence.UNKNOWN, script(0, "READABLE\n", "Permission denied"));
        equal(ActivationPresets.ScriptPresence.UNKNOWN, script(0, "READABLE\nREADABLE\n", ""));
        equal(ActivationPresets.ScriptPresence.UNKNOWN, script(0, "READABLE\nextra", ""));
        equal(ActivationPresets.ScriptPresence.UNKNOWN, script(0, "\nREADABLE\n", ""));
        equal(ActivationPresets.ScriptPresence.UNKNOWN, script(0, "readable\n", ""));
        equal(ActivationPresets.ScriptPresence.UNKNOWN, script(1, "READABLE\n", ""));
        equal(ActivationPresets.ScriptPresence.UNKNOWN, script(0, null, ""));
        equal(ActivationPresets.ScriptPresence.UNKNOWN, script(0, "", ""));
    }
    private static void guards() {
        for (ActivationPresets.Preset p : ActivationPresets.all()) {
            if (!p.isSupported()) {
                rejects(() -> p.buildCommand(), IllegalStateException.class);
                rejects(() -> p.buildCommand(ActivationPresets.PackagePresence.INSTALLED, ActivationPresets.ScriptPresence.READABLE, true), IllegalStateException.class);
                continue;
            }
            equal(p.command, p.buildCommand(ActivationPresets.PackagePresence.INSTALLED, ActivationPresets.ScriptPresence.READABLE, true));
            rejects(() -> p.buildCommand(ActivationPresets.PackagePresence.UNKNOWN, ActivationPresets.ScriptPresence.READABLE, true), IllegalStateException.class);
            rejects(() -> p.buildCommand(ActivationPresets.PackagePresence.NOT_FOUND, ActivationPresets.ScriptPresence.READABLE, true), IllegalStateException.class);
            rejects(() -> p.buildCommand(null, ActivationPresets.ScriptPresence.READABLE, true), IllegalStateException.class);
            rejects(() -> p.buildCommand(ActivationPresets.PackagePresence.INSTALLED, ActivationPresets.ScriptPresence.READABLE, false), IllegalStateException.class);
            if (p.scriptRequirement == ActivationPresets.ScriptRequirement.PREEXISTING) {
                for (ActivationPresets.ScriptPresence script : ActivationPresets.ScriptPresence.values()) {
                    if (script != ActivationPresets.ScriptPresence.READABLE)
                        rejects(() -> p.buildCommand(ActivationPresets.PackagePresence.INSTALLED, script, true), IllegalStateException.class);
                }
                rejects(() -> p.buildCommand(ActivationPresets.PackagePresence.INSTALLED, null, true), IllegalStateException.class);
            }
        }
        ActivationPresets.Preset b = ActivationPresets.get("brevent");
        equal(ActivationPresets.ScriptRequirement.CREATED_BY_BOOTSTRAP, b.scriptRequirement);
        equal(b.command, b.buildCommand(ActivationPresets.PackagePresence.INSTALLED, ActivationPresets.ScriptPresence.MISSING_OR_INACCESSIBLE, true));
        ActivationPresets.Preset owner = ActivationPresets.get("apkinstaller");
        check(owner.deviceOwner && !owner.isSupported(), "device owner disabled");
        check(owner.consequence.contains("广泛") && owner.consequence.contains("持久"), "broad persistent impact disclosed");
        check(owner.verificationInstructions.contains("testOnly"), "no misleading universal rollback");
        check(owner.requiredConditions.toString().contains("备份"), "backup warning");
        check(owner.requiredConditions.toString().contains("无账户"), "device preconditions warning");
        check(owner.requiredConditions.toString().contains("单独"), "separate owner confirmation warning");
    }
    private static void results() {
        for (ActivationPresets.Preset p : ActivationPresets.all()) {
            equal(ActivationPresets.ShellOutcome.COMPLETED_SERVICE_UNVERIFIED, p.assessShellResult(0));
            equal(ActivationPresets.ShellOutcome.FAILED, p.assessShellResult(1));
            equal(ActivationPresets.ShellOutcome.FAILED, p.assessShellResult(64));
            equal(ActivationPresets.ShellOutcome.FAILED, p.assessShellResult(255));
            equal(ActivationPresets.ShellOutcome.UNKNOWN, p.assessShellResult(-1));
        }
    }
    private static ActivationPresets.PackagePresence pkg(ActivationPresets.Preset p, int e, String o, String r) {
        return ActivationPresets.parsePackageProbe(p, e, o, r);
    }
    private static ActivationPresets.ScriptPresence script(int e, String o, String r) {
        return ActivationPresets.parseScriptProbe(e, o, r);
    }
    private static void check(boolean value, String why) { assertions++; if (!value) throw new AssertionError(why); }
    private static void equal(Object expected, Object actual) {
        assertions++; if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    private static void rejects(Runnable action, Class<? extends RuntimeException> expected) {
        assertions++;
        try { action.run(); } catch (RuntimeException e) { if (expected.isInstance(e)) return; throw e; }
        throw new AssertionError("Expected " + expected.getSimpleName());
    }
}

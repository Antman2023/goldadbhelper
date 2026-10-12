package io.github.devicebridge.android;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Ordinary synthetic package metadata only. No device, APK opening or adversarial filesystem tests. */
public final class LocalAppsTest {
    private static int passed;
    private static void ok(boolean test, String name) {
        if (!test) throw new AssertionError(name);
        passed++; System.out.println("PASS " + name);
    }
    interface Job { void run() throws Exception; }
    private static void rejects(Job job, String name) throws Exception {
        try { job.run(); throw new AssertionError(name); }
        catch (IOException expected) { ok(true, name); }
    }
    private static LocalAppSelection selection(String pkg, String version, long code, long update,
            int uid, String... paths) throws IOException {
        return new LocalAppSelection(pkg, "Fixture", version, code, update, uid, Arrays.asList(paths));
    }
    private static ApplicationInfo app(String pkg, String base, String... splits) {
        ApplicationInfo app = new ApplicationInfo(); app.packageName = pkg; app.sourceDir = base;
        app.splitSourceDirs = splits; app.uid = 10001; return app;
    }
    private static final class FakePackages extends PackageManager {
        List<ApplicationInfo> apps = new ArrayList<>();
        RuntimeException queryError, infoError;
        PackageInfo info;
        public List<ApplicationInfo> getInstalledApplications(int flags) {
            if (queryError != null) throw queryError; return apps;
        }
        public CharSequence getApplicationLabel(ApplicationInfo app) {
            if (app.packageName.equals("fixture.broken_label")) throw new IllegalArgumentException("fixture");
            if (app.packageName.equals("fixture.empty_label")) return "";
            return app.packageName.equals("fixture.a") ? "Alpha" : "Zulu";
        }
        public PackageInfo getPackageInfo(String pkg, int flags) throws NameNotFoundException {
            if (infoError != null) throw infoError;
            if (info == null) throw new NameNotFoundException(); return info;
        }
    }
    public static void main(String[] args) throws Exception {
        String base = "/ordinary-fixture/base.apk", split = "/ordinary-fixture/config.en.apk";
        LocalAppSelection selected = selection("fixture.app", "1.2", 4294967298L, 1000, 10001, base, split);
        selected.requireUnchanged(selection("fixture.app", "1.2", 4294967298L, 1000, 10001, base, split));
        ok(true, "identical package/version/full split metadata accepted");
        selected.requireBaseArchive("fixture.app", 4294967298L);
        ok(true, "64-bit base archive version matches");
        rejects(() -> selected.requireUnchanged(selection("fixture.other", "1.2", 4294967298L, 1000, 10001, base, split)), "different package rejected");
        rejects(() -> selected.requireUnchanged(selection("fixture.app", "1.2", 4294967299L, 1000, 10001, base, split)), "updated version code rejected");
        rejects(() -> selected.requireUnchanged(selection("fixture.app", "1.3", 4294967298L, 1000, 10001, base, split)), "updated version name rejected");
        rejects(() -> selected.requireUnchanged(selection("fixture.app", "1.2", 4294967298L, 1001, 10001, base, split)), "same-version reinstall metadata rejected");
        rejects(() -> selected.requireUnchanged(selection("fixture.app", "1.2", 4294967298L, 1000, 10002, base, split)), "new installation identity rejected");
        rejects(() -> selected.requireUnchanged(selection("fixture.app", "1.2", 4294967298L, 1000, 10001, base)), "missing split rejected");
        rejects(() -> selected.requireUnchanged(selection("fixture.app", "1.2", 4294967298L, 1000, 10001, base, split, "/ordinary-fixture/config.fr.apk")), "new split rejected");
        rejects(() -> selected.requireUnchanged(null), "missing package metadata rejected");
        rejects(() -> selected.requireBaseArchive("fixture.other", 4294967298L), "archive package mismatch rejected");
        rejects(() -> selected.requireBaseArchive("fixture.app", 2L), "truncated 64-bit archive version rejected");
        rejects(() -> selection("fixture.app", "1", 1, 1, 1, base, base), "duplicate source metadata rejected");
        rejects(() -> selection("fixture.app", "1", 1, 1, 1, (String)null), "absent APK metadata rejected");
        List<String> originals = new ArrayList<>(Arrays.asList(base, split));
        LocalAppSelection copied = new LocalAppSelection("fixture.app", "", null, 1, 1, 1, originals);
        originals.clear();ok(copied.apkPaths.size() == 2 && copied.label.equals("fixture.app"), "defensive copy and label fallback");
        try { copied.apkPaths.clear(); throw new AssertionError(); }
        catch (UnsupportedOperationException expected) { ok(true, "selected source metadata is immutable"); }

        FakePackages pm = new FakePackages();
        ok(LocalApps.load(pm).entries.isEmpty() && LocalApps.load(pm).failure.isEmpty(), "empty list remains distinguishable from denied access");
        pm.queryError = new SecurityException("fixture");
        ok(LocalApps.load(pm).failure.contains("系统拒绝"), "permission denial yields actionable status");
        pm.queryError = new IllegalStateException("fixture");
        ok(LocalApps.load(pm).failure.contains("读取失败"), "package service failure yields explicit status");
        pm.queryError = null; pm.apps = null;
        ok(!LocalApps.load(pm).failure.isEmpty(), "null OEM result yields explicit status");
        pm.apps = Arrays.asList(app("fixture.z", base), app("fixture.a", base),
                app("fixture.broken_label", base), app("fixture.empty_label", base));
        LocalApps.Catalog catalog = LocalApps.load(pm);
        ok(catalog.entries.size() == 4 && catalog.entries.get(0).packageName.equals("fixture.a"), "label sorting retains every application");
        ok(catalog.entries.get(1).label.equals("fixture.broken_label"), "single label failure falls back to package name");
        ok(catalog.entries.get(2).label.equals("fixture.empty_label"), "empty label falls back to package name");
        List<LocalApps.Entry> searchable=Arrays.asList(new LocalApps.Entry("fixture.chinese", "超长中文应用名称测试"),new LocalApps.Entry("fixture.second", "Sample App"));
        ok(LocalApps.filter(searchable,"中文").size()==1,"Chinese application-name search");
        ok(LocalApps.filter(searchable,"  SAMPLE ").size()==1,"case-insensitive trimmed application search");
        ok(LocalApps.filter(searchable,"FIXTURE.SECOND").size()==1,"case-insensitive package-name search");
        ok(LocalApps.filter(searchable,"").size()==2&&LocalApps.filter(searchable,"missing").isEmpty(),"clear and no-match search states");
        Thread.currentThread().interrupt();
        try { LocalApps.load(pm); throw new AssertionError("cancelled query"); }
        catch (InterruptedIOException expected) { ok(true, "catalog iteration cancellation is preserved"); }
        finally { Thread.interrupted(); }

        rejects(() -> LocalApps.read(pm, "fixture.app"), "removed application gives explicit checked error");
        pm.infoError = new SecurityException("fixture");
        rejects(() -> LocalApps.read(pm, "fixture.app"), "selected package access denial handled");
        pm.infoError = null; pm.info = new PackageInfo();
        pm.info.packageName = "fixture.app";pm.info.applicationInfo = app("fixture.app", base, split);
        pm.info.versionName = "1.2";pm.info.versionCode = 2;pm.info.longVersionCode = 4294967298L;pm.info.lastUpdateTime = 1000;
        Build.VERSION.SDK_INT = 35;
        LocalAppSelection live = LocalApps.read(pm, "fixture.app");
        ok(live.versionCode == 4294967298L && live.apkPaths.equals(Arrays.asList(base, split)), "modern package includes long version and complete splits");
        Build.VERSION.SDK_INT = 19;
        LocalAppSelection legacy = LocalApps.read(pm, "fixture.app");
        ok(legacy.versionCode == 2 && legacy.apkPaths.equals(Collections.singletonList(base)), "API19 avoids modern version and split APIs");
        System.out.println("LocalAppsTest: " + passed + " checks passed (synthetic package metadata; no device/OEM verification)");
    }
}

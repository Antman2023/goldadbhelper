package io.github.devicebridge.android;
import android.content.pm.ApplicationInfo;
final class Compatibility {
    static String[] splitSourceDirs(ApplicationInfo info) { return android.os.Build.VERSION.SDK_INT >= 21 ? info.splitSourceDirs : null; }
}

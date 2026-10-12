package android.content.pm;
public class PackageInfo {
    public String packageName, versionName;
    public int versionCode;
    public long lastUpdateTime, longVersionCode;
    public ApplicationInfo applicationInfo;
    public long getLongVersionCode() { return longVersionCode; }
}

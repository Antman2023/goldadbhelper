package android.content.pm;
import java.util.List;
public abstract class PackageManager {
    public static class NameNotFoundException extends Exception { }
    public abstract List<ApplicationInfo> getInstalledApplications(int flags);
    public abstract CharSequence getApplicationLabel(ApplicationInfo app);
    public abstract PackageInfo getPackageInfo(String packageName, int flags) throws NameNotFoundException;
}

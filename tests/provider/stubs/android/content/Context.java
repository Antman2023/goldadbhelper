package android.content;
import java.io.File;
public final class Context {
    private final File cache;
    public Context(File cache) { this.cache = cache; }
    public File getCacheDir() { return cache; }
}

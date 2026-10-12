package android.app;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;
/** Memory-only preferences and lifecycle flags. */
public class Activity {
    public static final int MODE_PRIVATE = 0;
    public boolean finishing, destroyed;
    public boolean isFinishing() { return finishing; }
    public boolean isDestroyed() { return destroyed; }
    private final Map<String, String> values = new HashMap<>();
    private final SharedPreferences preferences = new SharedPreferences() {
        public String getString(String key, String fallback) { return values.containsKey(key) ? values.get(key) : fallback; }
        public Editor edit() { return new Editor() {
            private final Map<String, String> pending = new HashMap<>();
            public Editor putString(String key, String value) { pending.put(key, value); return this; }
            public void apply() { values.putAll(pending); }
        }; }
    };
    public SharedPreferences getSharedPreferences(String name, int mode) { return preferences; }
}

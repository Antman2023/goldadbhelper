package android.net;
import java.net.URI;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
/** Minimal encoded-path seam, including encoded slash and literal plus handling. */
public final class Uri {
    private final URI value;
    private Uri(String text) { value = URI.create(text); }
    public static Uri parse(String text) { return new Uri(text); }
    public String getScheme() { return value.getScheme(); }
    public String getAuthority() { return value.getRawAuthority(); }
    public List<String> getPathSegments() {
        List<String> result = new ArrayList<>();
        String path = value.getRawPath();
        if (path != null) for (String part : path.split("/")) if (!part.isEmpty()) {
            try { result.add(URLDecoder.decode(part.replace("+", "%2B"), "UTF-8")); }
            catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
        }
        return result;
    }
    public String getLastPathSegment() {
        List<String> segments = getPathSegments();
        return segments.isEmpty() ? null : segments.get(segments.size() - 1);
    }
}

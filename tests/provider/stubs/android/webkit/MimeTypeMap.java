package android.webkit;
public final class MimeTypeMap {
    private static final MimeTypeMap INSTANCE = new MimeTypeMap();
    public static MimeTypeMap getSingleton() { return INSTANCE; }
    public String getMimeTypeFromExtension(String extension) {
        if ("gif".equals(extension)) return "image/gif";
        if ("txt".equals(extension)) return "text/plain";
        return null;
    }
}

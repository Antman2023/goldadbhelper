package android.os;
import java.io.*;
public final class ParcelFileDescriptor implements Closeable {
    public static final int MODE_READ_ONLY = 0x10000000;
    public final File file;
    public final int mode;
    private final FileInputStream input;
    private ParcelFileDescriptor(File file, int mode) throws FileNotFoundException {
        this.file = file; this.mode = mode; input = new FileInputStream(file);
    }
    public static ParcelFileDescriptor open(File file, int mode) throws FileNotFoundException {
        if (mode != MODE_READ_ONLY) throw new AssertionError("writable descriptor requested");
        return new ParcelFileDescriptor(file, mode);
    }
    public int read() throws IOException { return input.read(); }
    public void close() throws IOException { input.close(); }
}

package io.github.devicebridge.core;

import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Kernel-backed Android file operations, including KitKat's original libcore boundary.
 * Reflection is used only to bridge the API-21 package move. There is no java.io or shell
 * approximation of O_EXCL/O_NOFOLLOW. Missing OEM APIs fail closed before opening a file.
 */
public final class AndroidFileAccess {
    public static final class Stat {
        public final long dev, ino, size, mode, uid, gid, modifiedSeconds;
        public final boolean regular, directory;
        public final String revision;
        Stat(long dev, long ino, long size, boolean regular, boolean directory, String revision, long mode, long uid, long gid, long modifiedSeconds) {
            this.dev = dev; this.ino = ino; this.size = size; this.regular = regular; this.directory = directory; this.revision = revision;
            this.mode = mode; this.uid = uid; this.gid = gid; this.modifiedSeconds = modifiedSeconds;
        }
        public String token() { return dev + ":" + ino + ":" + size + ":" + regular + ":" + revision; }
        public String directoryToken() { return dev + ":" + ino + ":" + directory; }
    }
    private final Object receiver;
    private final boolean legacyFcntl;
    private final Method open, read, write, fstat, lstat, fsync, close, lseek, mkdir, remove, fcntl;
    private final int readFlags, directoryFlags, createFlags, enoent, eexist, regularMask, regularType, directoryType, seekSet, setFd, closeOnExec;

    public static boolean isAndroid() {
        try { Class.forName("android.os.Build$VERSION"); return true; }
        catch (ClassNotFoundException absent) { return false; }
    }
    public AndroidFileAccess() throws IOException {
        try {
            int sdk = Class.forName("android.os.Build$VERSION").getField("SDK_INT").getInt(null);
            legacyFcntl = sdk < 21;
            if (sdk < 19) throw new IOException("Kernel-backed file access requires API 19");
            Class<?> os, constants;
            if (sdk >= 21) {
                os = Class.forName("android.system.Os"); constants = Class.forName("android.system.OsConstants"); receiver = null;
            } else {
                os = Class.forName("libcore.io.Os"); constants = Class.forName("libcore.io.OsConstants");
                receiver = Class.forName("libcore.io.Libcore").getField("os").get(null);
            }
            // KitKat exposes neither O_CLOEXEC nor O_DIRECTORY. fcntl(F_SETFD) and
            // fstat's directory predicate provide their required effects without guessed flags.
            int atomicCloseOnExec = optionalConstant(constants, "O_CLOEXEC");
            readFlags = constant(constants, "O_RDONLY") | atomicCloseOnExec
                    | constant(constants, "O_NOFOLLOW") | constant(constants, "O_NONBLOCK");
            createFlags = constant(constants, "O_CREAT") | constant(constants, "O_EXCL")
                    | constant(constants, "O_WRONLY") | atomicCloseOnExec;
            directoryFlags = readFlags | optionalConstant(constants, "O_DIRECTORY");
            enoent = constant(constants, "ENOENT"); eexist = constant(constants, "EEXIST");
            regularMask = constant(constants, "S_IFMT"); regularType = constant(constants, "S_IFREG");
            directoryType = constant(constants, "S_IFDIR"); seekSet = constant(constants, "SEEK_SET");
            setFd = constant(constants, "F_SETFD"); closeOnExec = constant(constants, "FD_CLOEXEC");
            open = os.getMethod("open", String.class, int.class, int.class);
            read = os.getMethod("read", FileDescriptor.class, byte[].class, int.class, int.class);
            write = os.getMethod("write", FileDescriptor.class, byte[].class, int.class, int.class);
            fstat = os.getMethod("fstat", FileDescriptor.class); lstat = os.getMethod("lstat", String.class);
            fsync = os.getMethod("fsync", FileDescriptor.class); close = os.getMethod("close", FileDescriptor.class);
            lseek = os.getMethod("lseek", FileDescriptor.class, long.class, int.class);
            mkdir = os.getMethod("mkdir", String.class, int.class); remove = os.getMethod("remove", String.class);
            fcntl = os.getMethod(legacyFcntl ? "fcntlLong" : "fcntlInt", FileDescriptor.class, int.class, legacyFcntl ? long.class : int.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IOException("This Android build does not expose safe kernel-backed file operations", e);
        }
    }
    private static int constant(Class<?> type, String name) throws ReflectiveOperationException { return type.getField(name).getInt(null); }
    private static int optionalConstant(Class<?> type, String name) throws ReflectiveOperationException {
        try { return constant(type, name); } catch (NoSuchFieldException missingOnKitKat) { return 0; }
    }
    private Object call(Method method, Object... args) throws IOException {
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            try {
                int errno = cause.getClass().getField("errno").getInt(cause);
                if (errno == enoent) throw new FileNotFoundException(cause.getMessage());
                if (errno == eexist) throw new IOException("Destination already exists: " + cause.getMessage(), cause);
            } catch (ReflectiveOperationException ignored) { }
            if (cause instanceof IOException) throw (IOException) cause;
            throw new IOException("Kernel file operation failed: " + cause.getMessage(), cause);
        } catch (ReflectiveOperationException | LinkageError e) { throw new IOException("Kernel file operation unavailable", e); }
    }
    private FileDescriptor open(String path, int flags, int mode, boolean directory) throws IOException {
        FileDescriptor fd = (FileDescriptor) call(open, path, flags, mode);
        try {
            if (legacyFcntl) call(fcntl, fd, setFd, (long) closeOnExec);
            else call(fcntl, fd, setFd, closeOnExec);
            if (directory && !fstat(fd).directory) throw new IOException("Not a directory: " + path);
            return fd;
        } catch (IOException | RuntimeException failure) {
            try { close(fd); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }
    public FileDescriptor readOnly(String path) throws IOException { return open(path, readFlags, 0, false); }
    public FileDescriptor openDirectory(String path) throws IOException { return open(path, directoryFlags, 0, true); }
    public FileDescriptor createExclusive(String path) throws IOException { return open(path, createFlags, 0644, false); }
    public int read(FileDescriptor fd, byte[] bytes) throws IOException { int n = (Integer) call(read, fd, bytes, 0, bytes.length); return n == 0 ? -1 : n; }
    public int write(FileDescriptor fd, byte[] bytes, int offset, int count) throws IOException { return (Integer) call(write, fd, bytes, offset, count); }
    public void fsync(FileDescriptor fd) throws IOException { call(fsync, fd); }
    public void close(FileDescriptor fd) throws IOException { call(close, fd); }
    public void rewind(FileDescriptor fd) throws IOException { if (((Number) call(lseek, fd, 0L, seekSet)).longValue() != 0) throw new IOException("Unable to rewind source"); }
    public void mkdir(String path) throws IOException { call(mkdir, path, 0700); }
    public void remove(String path) throws IOException { call(remove, path); }
    public Stat fstat(FileDescriptor fd) throws IOException { return stat(call(fstat, fd)); }
    public Stat lstat(String path) throws IOException { return stat(call(lstat, path)); }
    private Stat stat(Object value) throws IOException {
        try {
            Class<?> type = value.getClass();
            long dev = number(type, value, "st_dev"), ino = number(type, value, "st_ino"), size = number(type, value, "st_size");
            long mode = number(type, value, "st_mode");
            String revision;
            try {
                Object m = type.getField("st_mtim").get(value), c = type.getField("st_ctim").get(value);
                revision = time(m) + ":" + time(c);
            } catch (NoSuchFieldException legacy) {
                revision = number(type, value, "st_mtime") + ":" + number(type, value, "st_ctime");
            }
            return new Stat(dev, ino, size, (mode & regularMask) == regularType, (mode & regularMask) == directoryType, revision,
                    mode, number(type, value, "st_uid"), number(type, value, "st_gid"), number(type, value, "st_mtime"));
        } catch (ReflectiveOperationException | LinkageError e) { throw new IOException("Kernel file identity unavailable", e); }
    }
    private static long number(Class<?> type, Object value, String name) throws ReflectiveOperationException {
        return ((Number) type.getField(name).get(value)).longValue();
    }
    private static String time(Object value) throws ReflectiveOperationException {
        return number(value.getClass(), value, "tv_sec") + ":" + number(value.getClass(), value, "tv_nsec");
    }
}

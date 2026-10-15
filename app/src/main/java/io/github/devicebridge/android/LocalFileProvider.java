package io.github.devicebridge.android;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Read-only, temporary grants for completed downloads. No arbitrary-path access. */
public final class LocalFileProvider extends ContentProvider {
    private static final String AUTHORITY = "io.github.devicebridge.goldenbeetle.files";
    public boolean onCreate() { return true; }

    private static String filename(Uri uri) throws FileNotFoundException {
        if (uri == null || !"content".equals(uri.getScheme()) || !AUTHORITY.equals(uri.getAuthority()))
            throw new FileNotFoundException("Invalid export URI");
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 1) throw new FileNotFoundException("Invalid export path");
        String name = segments.get(0);
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0)
            throw new FileNotFoundException("Invalid export filename");
        return name;
    }

    private File file(Uri uri) throws FileNotFoundException {
        String name = filename(uri);
        try {
            File root = new File(getContext().getCacheDir(), "exports").getCanonicalFile();
            File file = new File(root, name).getCanonicalFile();
            if (!root.equals(file.getParentFile()) || !file.isFile()) throw new FileNotFoundException();
            return file;
        } catch (IOException unavailable) { throw new FileNotFoundException("File unavailable"); }
    }

    public String getType(Uri uri) {
        try {
            String name = filename(uri);
            String extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
            String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            return type == null ? "application/octet-stream" : type;
        } catch (FileNotFoundException invalid) { return null; }
    }

    /** Supported columns are returned in the caller's requested order. */
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        try {
            File file = file(uri);
            String[] requested = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            List<String> columns = new ArrayList<>();
            List<Object> values = new ArrayList<>();
            long size = file.length();
            for (String column : requested) {
                if (OpenableColumns.DISPLAY_NAME.equals(column)) { columns.add(column); values.add(file.getName()); }
                else if (OpenableColumns.SIZE.equals(column)) { columns.add(column); values.add(size); }
            }
            MatrixCursor cursor = new MatrixCursor(columns.toArray(new String[0]), 1);
            cursor.addRow(values.toArray());
            return cursor;
        } catch (IOException unavailable) { return null; }
    }

    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only provider");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}

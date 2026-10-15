package io.github.devicebridge.android;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Actual provider with framework stand-ins and private host files; no Android grants. */
public final class LocalFileProviderTests {
    private static final String BASE = "content://io.github.devicebridge.goldenbeetle.files";
    private static int assertions;
    public static void main(String[] args) throws Exception {
        Path cache = Files.createTempDirectory("golden-provider-");
        Path exports = Files.createDirectory(cache.resolve("exports"));
        Path file = exports.resolve("open-file.GIF");
        Path named = exports.resolve("space + 中文.txt");
        Path directory = Files.createDirectory(exports.resolve("folder"));
        Files.write(file, new byte[]{42, 43, 44}); Files.write(named, new byte[]{7});
        LocalFileProvider provider = new LocalFileProvider(); provider.attachContext(new Context(cache.toFile()));
        try {
            check(provider.onCreate(), "provider starts");
            projection(provider); namesAndTypes(provider); invalidUris(provider);
            try (ParcelFileDescriptor descriptor = provider.openFile(Uri.parse(BASE + "/open-file.GIF"), "r")) {
                check(descriptor.mode == ParcelFileDescriptor.MODE_READ_ONLY && descriptor.read() == 42, "opened descriptor reads intended file");
            }
            for (String mode : new String[]{null, "w", "rw", "rwt", "wa"})
                missing(() -> provider.openFile(Uri.parse(BASE + "/open-file.GIF"), mode));
            missing(() -> provider.openFile(Uri.parse(BASE + "/folder"), "r"));
            missing(() -> provider.openFile(Uri.parse(BASE + "/missing.txt"), "r"));
            check(provider.query(Uri.parse(BASE + "/missing.txt"), null, null, null, null) == null, "missing file has no metadata row");
        } finally {
            Files.deleteIfExists(file); Files.deleteIfExists(named); Files.deleteIfExists(directory);
            Files.deleteIfExists(exports); Files.deleteIfExists(cache);
        }
        System.out.println("LocalFileProviderTests: " + assertions + " assertions passed (framework stand-ins; no URI grant validation)");
    }

    private static void projection(LocalFileProvider provider) {
        Uri uri = Uri.parse(BASE + "/open-file.GIF");
        String[][] requests = {{OpenableColumns.SIZE}, {OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME},
                {"unknown", OpenableColumns.DISPLAY_NAME}, {}, {"unknown"},
                {OpenableColumns.SIZE, OpenableColumns.SIZE}, null};
        for (String[] request : requests) {
            List<String> expected = new ArrayList<>();
            if (request == null) expected.addAll(Arrays.asList(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE));
            else for (String column : request) if (OpenableColumns.SIZE.equals(column) || OpenableColumns.DISPLAY_NAME.equals(column)) expected.add(column);
            try (Cursor cursor = provider.query(uri, request, null, null, null)) {
                check(Arrays.equals(expected.toArray(new String[0]), cursor.getColumnNames()), "query honors requested column order and subset");
                check(cursor.getCount() == 1 && cursor.moveToFirst(), "existing export supplies one row");
                for (int i = 0; i < expected.size(); i++) {
                    if (OpenableColumns.SIZE.equals(expected.get(i))) check(cursor.getLong(i) == 3, "size column remains numeric");
                    else check("open-file.GIF".equals(cursor.getString(i)), "display-name column matches exported filename");
                }
            }
        }
    }

    private static void namesAndTypes(LocalFileProvider provider) throws Exception {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            check("image/gif".equals(provider.getType(Uri.parse(BASE + "/open-file.GIF"))), "MIME extension is locale-independent");
        } finally { Locale.setDefault(previous); }
        check("application/octet-stream".equals(provider.getType(Uri.parse(BASE + "/unknown.ext"))), "unknown MIME uses binary fallback");
        Uri named = Uri.parse(BASE + "/space%20+%20%E4%B8%AD%E6%96%87.txt");
        try (Cursor cursor = provider.query(named, null, null, null, null)) {
            check(cursor.moveToFirst() && "space + 中文.txt".equals(cursor.getString(0)), "encoded filename and literal plus preserved");
        }
        try (ParcelFileDescriptor descriptor = provider.openFile(named, "r")) {
            check(descriptor.read() == 7, "encoded URI opens the matching export");
        }
    }

    private static void invalidUris(LocalFileProvider provider) throws Exception {
        for (String value : new String[]{null, BASE, BASE + "/", BASE + "/.", BASE + "/..", BASE + "/nested/open-file.GIF",
                BASE + "/%2E%2E%2Fopen-file.GIF", BASE + "/%00", BASE + "/bad%5Cname", "file:///open-file.GIF",
                "content://other.files/open-file.GIF"}) {
            Uri uri = value == null ? null : Uri.parse(value);
            missing(() -> provider.openFile(uri, "r"));
            check(provider.query(uri, null, null, null, null) == null, "invalid URI has no metadata row");
            check(provider.getType(uri) == null, "invalid URI has no MIME type");
        }
    }

    private interface Action { void run() throws Exception; }
    private static void missing(Action action) throws Exception {
        try { action.run(); throw new AssertionError("Expected FileNotFoundException"); }
        catch (FileNotFoundException expected) { assertions++; }
    }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
}

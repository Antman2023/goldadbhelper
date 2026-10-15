package android.database;
public interface Cursor extends java.io.Closeable {
    String[] getColumnNames();
    int getCount();
    boolean moveToFirst();
    String getString(int column);
    long getLong(int column);
    void close();
}

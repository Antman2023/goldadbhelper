package android.database;
import java.util.ArrayList;
import java.util.List;
public final class MatrixCursor implements Cursor {
    private final String[] columns;
    private final List<Object[]> rows = new ArrayList<>();
    private int position = -1;
    public MatrixCursor(String[] columns) { this.columns = columns.clone(); }
    public MatrixCursor(String[] columns, int capacity) { this(columns); }
    public void addRow(Object[] values) {
        if (values.length != columns.length) throw new IllegalArgumentException("column count mismatch");
        rows.add(values.clone());
    }
    public String[] getColumnNames() { return columns.clone(); }
    public int getCount() { return rows.size(); }
    public boolean moveToFirst() { position = 0; return !rows.isEmpty(); }
    public String getString(int column) { return String.valueOf(rows.get(position)[column]); }
    public long getLong(int column) { return ((Number)rows.get(position)[column]).longValue(); }
    public void close() { }
}

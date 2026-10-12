package android.content;
/** In-memory listener shape only, not Android dispatch. */
public interface DialogInterface {
    interface OnClickListener { void onClick(DialogInterface dialog, int which); }
}

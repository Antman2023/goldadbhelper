package android.app;
import android.content.DialogInterface;
public class Dialog implements DialogInterface {
    private boolean showing;
    public void show() { showing = true; }
    public void dismiss() { showing = false; }
    public boolean isShowing() { return showing; }
    public void cancel() { dismiss(); }
}

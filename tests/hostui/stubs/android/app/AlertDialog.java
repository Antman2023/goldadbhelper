package android.app;
import android.content.DialogInterface;
import java.util.ArrayList;
import java.util.List;
/** Captures controller-created UI text and listener wiring; no pixels or event loop. */
public class AlertDialog extends Dialog {
    public static final List<AlertDialog> SHOWN = new ArrayList<>();
    public String title = "", message = "", positive = "", negative = "", neutral = "";
    public String[] items = new String[0];
    private DialogInterface.OnClickListener itemAction, positiveAction, negativeAction, neutralAction;
    @Override public void show() { super.show(); SHOWN.add(this); }
    public void choose(int index) { dismiss(); if (itemAction != null) itemAction.onClick(this, index); }
    public void positive() { dismiss(); if (positiveAction != null) positiveAction.onClick(this, -1); }
    public void negative() { dismiss(); if (negativeAction != null) negativeAction.onClick(this, -2); }
    public void neutral() { dismiss(); if (neutralAction != null) neutralAction.onClick(this, -3); }
    public static final class Builder {
        private final AlertDialog dialog = new AlertDialog();
        public Builder(Activity activity) { }
        public Builder setTitle(String title) { dialog.title = title; return this; }
        public Builder setMessage(String message) { dialog.message = message; return this; }
        public Builder setItems(String[] items, DialogInterface.OnClickListener action) { dialog.items = items.clone(); dialog.itemAction = action; return this; }
        public Builder setPositiveButton(String label, DialogInterface.OnClickListener action) { dialog.positive = label; dialog.positiveAction = action; return this; }
        public Builder setNegativeButton(String label, DialogInterface.OnClickListener action) { dialog.negative = label; dialog.negativeAction = action; return this; }
        public Builder setNeutralButton(String label, DialogInterface.OnClickListener action) { dialog.neutral = label; dialog.neutralAction = action; return this; }
        public AlertDialog show() { dialog.show(); return dialog; }
    }
}

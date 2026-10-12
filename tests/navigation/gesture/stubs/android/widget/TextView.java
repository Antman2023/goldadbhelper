package android.widget;
import android.content.Context;
import android.view.View;
public class TextView extends View {
    private boolean selectable;
    public TextView(Context context){super(context);} public boolean isTextSelectable(){return selectable;}
    public void setTextIsSelectable(boolean value){selectable=value;}
}

package android.widget;
import android.content.Context;import android.view.View;import java.util.*;
public class FrameLayout extends View {
    public static class LayoutParams {public LayoutParams(int width,int height){}}
    private final List<View> children=new ArrayList<>();
    public FrameLayout(Context context){super(context);} public void setClipChildren(boolean value){} public void setClipToPadding(boolean value){}
    public void addView(View view,LayoutParams params){if(view.getParent()!=null)throw new IllegalStateException("Already attached");children.add(view);view.parent(this);}
    public int getChildCount(){return children.size();}public View getChildAt(int index){return children.get(index);}
    public void removeViewAt(int index){children.remove(index).parent(null);}public void removeAllViews(){while(!children.isEmpty())removeViewAt(children.size()-1);}
}

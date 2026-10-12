package android.view;
import android.content.Context;
public class View {
    public static final int VISIBLE=0,GONE=8;
    private final Context context; private int visibility=VISIBLE,left,top,right=720,bottom=1100;
    private ViewGroup parent; private boolean windowFocus=true; private View focus;
    public View(Context context){this.context=context;} public Context getContext(){return context;}
    public void layout(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}
    public int getLeft(){return left;} public int getRight(){return right;} public int getTop(){return top;} public int getBottom(){return bottom;}
    public int getScrollX(){return 0;} public int getScrollY(){return 0;}
    public int getVisibility(){return visibility;} public void setVisibility(int v){visibility=v;}
    public boolean isShown(){return visibility==VISIBLE&&(parent==null||parent.isShown());}
    public View findFocus(){return focus;} public void focus(View view){focus=view;}
    public boolean hasWindowFocus(){return windowFocus;} public void onWindowFocusChanged(boolean focused){windowFocus=focused;}
    protected void onWindowVisibilityChanged(int visibility){} protected void onDetachedFromWindow(){}
    public boolean canScrollHorizontally(int direction){return false;}
    public ViewGroup getParent(){return parent;} void parent(ViewGroup group){parent=group;}
    public boolean dispatchTouchEvent(MotionEvent event){return onTouchEvent(event);}
    public boolean onTouchEvent(MotionEvent event){return false;}
}

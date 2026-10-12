package android.view;
import android.content.Context;
public class View {
    public static final int VISIBLE=0,GONE=8,IMPORTANT_FOR_ACCESSIBILITY_AUTO=0,IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS=4;
    private Object tag,parent; private int visibility=VISIBLE,important,width=320,height=480;private float translation;
    public View(Context context) {}
    public void clearFocus(){} public void setSelected(boolean selected){} public boolean requestFocus(){return true;}
    public Object getWindowToken(){return this;} public void setBackgroundColor(int color){}
    public void setTag(Object value){tag=value;} public Object getTag(){return tag;}
    public Object getParent(){return parent;} public void parent(Object value){parent=value;}
    public void setVisibility(int value){visibility=value;} public int getVisibility(){return visibility;}
    public void setImportantForAccessibility(int value){important=value;} public int getImportantForAccessibility(){return important;}
    public void setTranslationX(float value){translation=value;} public float getTranslationX(){return translation;}
    public int getWidth(){return width;} public int getHeight(){return height;}
    public void setFocusable(boolean value){} public void setFocusableInTouchMode(boolean value){}
    public void resize(int w,int h){int oldw=width,oldh=height;width=w;height=h;onSizeChanged(w,h,oldw,oldh);}
    protected void onSizeChanged(int w,int h,int oldw,int oldh){} protected void onDetachedFromWindow(){}
}

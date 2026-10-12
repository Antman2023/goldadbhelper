package android.view;
import android.content.Context;
import java.util.ArrayList;
/** Bounded host model of Android's target retention and disallow-intercept dispatch rules. */
public class ViewGroup extends View {
    private final ArrayList<View> children=new ArrayList<View>(); private View target; private boolean disallow;
    public ViewGroup(Context context){super(context);}
    public void addView(View child){children.add(child);child.parent(this);}
    public int getChildCount(){return children.size();} public View getChildAt(int i){return children.get(i);}
    public void requestDisallowInterceptTouchEvent(boolean value){disallow=value;if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(value);}
    public boolean onInterceptTouchEvent(MotionEvent event){return false;}
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){target=null;disallow=false;}
        boolean intercept=action!=MotionEvent.ACTION_DOWN&&target==null;
        if(action==MotionEvent.ACTION_DOWN||target!=null)intercept=!disallow&&onInterceptTouchEvent(event);
        boolean handled=false;
        if(!intercept&&action==MotionEvent.ACTION_DOWN){
            for(int i=children.size()-1;i>=0;i--){View child=children.get(i);
                if(child.getVisibility()==VISIBLE&&event.getX()>=child.getLeft()&&event.getX()<child.getRight()
                        &&event.getY()>=child.getTop()&&event.getY()<child.getBottom()
                        &&child.dispatchTouchEvent(event.child(action,-child.getLeft(),-child.getTop()))){target=child;handled=true;break;}
            }
        }
        if(target==null){handled=onTouchEvent(event);}
        else if(action!=MotionEvent.ACTION_DOWN){
            handled=target.dispatchTouchEvent(event.child(intercept?MotionEvent.ACTION_CANCEL:action,-target.getLeft(),-target.getTop()));
            if(intercept)target=null;
        }
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){target=null;disallow=false;}
        return handled;
    }
}

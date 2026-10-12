package android.view;
/** Immutable synthetic events only. */
public final class MotionEvent {
    public static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3,ACTION_POINTER_DOWN=5,ACTION_POINTER_UP=6;
    private final int action,pointers; private final float x,y; private final long time;
    public MotionEvent(int action,float x,float y,long time,int pointers){this.action=action;this.x=x;this.y=y;this.time=time;this.pointers=pointers;}
    public int getActionMasked(){return action;} public int getPointerCount(){return pointers;}
    public float getX(){return x;} public float getY(){return y;} public long getEventTime(){return time;}
    public MotionEvent child(int action,float dx,float dy){return new MotionEvent(action,x+dx,y+dy,time,pointers);}
}

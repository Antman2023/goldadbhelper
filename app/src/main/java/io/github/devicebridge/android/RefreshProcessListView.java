package io.github.devicebridge.android;
import android.content.Context;import android.view.MotionEvent;import android.widget.ExpandableListView;
final class RefreshProcessListView extends ExpandableListView {
 private final Runnable refresh;private float x,y;private boolean eligible,pulling;private long last;
 RefreshProcessListView(Context c,Runnable action){super(c);refresh=action;}
 public boolean onTouchEvent(MotionEvent e){int action=e.getActionMasked();if(action==MotionEvent.ACTION_DOWN){x=e.getX();y=e.getY();eligible=getFirstVisiblePosition()==0&&(getChildCount()==0||getChildAt(0).getTop()>=getPaddingTop());pulling=false;}else if(action==MotionEvent.ACTION_MOVE&&eligible&&e.getY()-y>Ui.dp(getContext(),74)&&Math.abs(e.getX()-x)<Ui.dp(getContext(),48)){if(!pulling){MotionEvent cancel=MotionEvent.obtain(e);cancel.setAction(MotionEvent.ACTION_CANCEL);super.onTouchEvent(cancel);cancel.recycle();pulling=true;}return true;}else if(action==MotionEvent.ACTION_UP&&pulling){pulling=false;long now=android.os.SystemClock.elapsedRealtime();if(now-last>1200){last=now;refresh.run();}return true;}else if(action==MotionEvent.ACTION_CANCEL){pulling=false;eligible=false;}return pulling||super.onTouchEvent(e);}
}

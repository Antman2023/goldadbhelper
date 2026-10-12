package android.animation;
import java.util.*;
public class ValueAnimator extends Animator {
    public interface AnimatorUpdateListener {void onAnimationUpdate(ValueAnimator animation);}
    public static ValueAnimator latest;private float from,to,value;private boolean running;
    private final List<AnimatorUpdateListener> updates=new ArrayList<>();private final List<AnimatorListenerAdapter> listeners=new ArrayList<>();
    public static ValueAnimator ofFloat(float start,float end){ValueAnimator a=new ValueAnimator();a.from=start;a.to=end;a.value=start;return a;}
    public void setDuration(long value){if(value<0)throw new AssertionError();}public void setInterpolator(Object value){}
    public void addUpdateListener(AnimatorUpdateListener value){updates.add(value);}public void addListener(AnimatorListenerAdapter value){listeners.add(value);}
    public Object getAnimatedValue(){return value;}public void start(){latest=this;running=true;}
    public void step(float progress){if(!running)return;value=from+(to-from)*progress;for(AnimatorUpdateListener l:new ArrayList<>(updates))l.onAnimationUpdate(this);}
    public void finish(){if(!running)return;step(1);if(!running)return;running=false;for(AnimatorListenerAdapter l:new ArrayList<>(listeners))l.onAnimationEnd(this);}
    public void removeAllUpdateListeners(){updates.clear();}public void removeAllListeners(){listeners.clear();}
    public void cancel(){running=false;for(AnimatorListenerAdapter l:new ArrayList<>(listeners))l.onAnimationEnd(this);}
}

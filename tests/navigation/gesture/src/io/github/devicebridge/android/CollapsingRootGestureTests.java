package io.github.devicebridge.android;

import android.content.Context;
import android.view.*;
import android.widget.*;
import java.util.ArrayList;

/** Exercises production CollapsingRoot against ordinary synthetic ViewGroup dispatch. */
public final class CollapsingRootGestureTests {
    private static int checks;
    private static final Context CONTEXT=new Context();
    private static final int DOWN=MotionEvent.ACTION_DOWN, MOVE=MotionEvent.ACTION_MOVE, UP=MotionEvent.ACTION_UP;
    private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;}
    private static final class Modules implements CollapsingRoot.Modules {
        int page=2,drags,releases,cancels,last=-1;long revision=1;boolean enabled=true;
        public boolean enabled(){return enabled;} public int current(){return page;} public int count(){return 7;}
        public long revision(){return revision;} public void drag(float distance){drags++;}
        public void release(int next){last=next;releases++;} public void cancel(){cancels++;}
    }
    private static final class ScrollChild extends View {
        final ArrayList<MotionEvent> received=new ArrayList<MotionEvent>();
        boolean disallowOnDown;int cancels;float previous,scrolled;
        ScrollChild(){super(CONTEXT);}
        @Override public boolean onTouchEvent(MotionEvent event){
            received.add(event);
            if(event.getActionMasked()==DOWN){previous=event.getY();if(disallowOnDown)getParent().requestDisallowInterceptTouchEvent(true);}
            if(event.getActionMasked()==MOVE){
                if(Math.abs(event.getY()-previous)>8)getParent().requestDisallowInterceptTouchEvent(true);
                scrolled+=previous-event.getY();previous=event.getY();
            }
            if(event.getActionMasked()==MotionEvent.ACTION_CANCEL)cancels++;
            return true;
        }
    }
    private static final class Fixture {
        final CollapsingRoot root=new CollapsingRoot(CONTEXT);
        final View tabs=new View(CONTEXT);final ViewGroup content=new ViewGroup(CONTEXT);
        final ScrollChild child=new ScrollChild();final Modules modules=new Modules();
        long time=100;
        Fixture(){root.layout(0,0,720,1100);tabs.layout(0,0,720,100);content.layout(0,100,720,1100);child.layout(0,0,720,1000);
            root.addView(tabs);root.addView(content);content.addView(child);root.tabs(tabs);root.modules(content,modules);}
        void event(int action,float x,float y){event(action,x,y,1);}
        void event(int action,float x,float y,int pointers){root.dispatchTouchEvent(new MotionEvent(action,x,y,time,pointers));time+=100;}
        void down(){event(DOWN,360,650);} void move(float y){event(MOVE,360,y);}
        void small(){down();move(640);}
        void unchanged(String label){check(tabs.getVisibility()==View.VISIBLE&&modules.releases==0&&modules.drags==0,label);}
    }
    public static void main(String[] args){
        slowVertical();fastVertical();horizontal();directionAndChildProtection();interruption();sensitiveAndBounds();
        System.out.println("COLLAPSING ROOT GESTURE TESTS: "+checks+" passed");
    }
    private static void slowVertical(){
        Fixture f=new Fixture();f.down();
        int[] ys={640,630,610,580,520,430,330};
        for(int i=0;i<ys.length;i++){
            f.move(ys[i]);
            check(f.tabs.getVisibility()==(i<2?View.VISIBLE:View.GONE),"slow up folds only after threshold "+i);
        }
        f.event(UP,360,330);
        check(f.child.cancels==0&&f.child.scrolled==320,"slow vertical child scroll is uninterrupted");
        check(f.child.received.size()==9,"child receives complete slow stream");
        for(int i=0;i<f.child.received.size();i++){
            MotionEvent e=f.child.received.get(i);
            check(e.getActionMasked()==(i==0?DOWN:i==8?UP:MOVE),"child event action preserved "+i);
            check(e.getX()==360&&e.getY()==(i==0?650:i==8?330:ys[i-1])-100,"child coordinates preserved "+i);
            check(e.getEventTime()==100+i*100&&e.getPointerCount()==1,"child time and pointers preserved "+i);
        }
        check(f.modules.drags==0&&f.modules.releases==0,"vertical stream never activates or previews a page");
        f.event(DOWN,360,330);for(int y:new int[]{340,350,370,400,460,550,650})f.move(y);f.event(UP,360,650);
        check(f.tabs.getVisibility()==View.VISIBLE,"slow down restores tabs after disallow");
        check(f.child.cancels==0&&f.child.scrolled==0,"restore keeps child scrolling");
        check(f.modules.drags==0&&f.modules.releases==0,"restore never activates modules");
    }
    private static void fastVertical(){
        Fixture f=new Fixture();f.down();f.move(600);f.event(UP,360,600);
        check(f.tabs.getVisibility()==View.GONE,"fast up retains fold");
        f.event(DOWN,360,500);f.move(550);f.event(UP,360,550);
        check(f.tabs.getVisibility()==View.VISIBLE&&f.child.cancels==0,"fast down retains restore");
    }
    private static void horizontal(){
        Fixture f=new Fixture();f.down();f.event(MOVE,350,650);f.time+=1000;f.event(MOVE,200,651);f.event(UP,200,651);
        check(f.modules.drags>0&&f.modules.releases==1&&f.modules.last==3,"owned slow horizontal drag still commits adjacent page");
        check(f.child.cancels==1&&f.tabs.getVisibility()==View.VISIBLE,"horizontal owns exactly one child cancellation and never folds");
        check(f.modules.cancels==1,"finishing dispatch does not cancel release animation"); // DOWN only; binding starts without a previous listener
        f=new Fixture();f.down();f.event(MOVE,350,650);f.event(MotionEvent.ACTION_CANCEL,300,650);f.event(UP,100,650);
        check(f.modules.releases==0,"horizontal cancel never releases a page");
        f=new Fixture();f.down();f.event(MOVE,350,650);f.event(MotionEvent.ACTION_POINTER_DOWN,300,650,2);f.event(UP,100,650);
        check(f.modules.releases==0&&f.modules.cancels>1,"horizontal multi-touch cancels preview and never releases a page");
        f=new Fixture();f.down();f.event(MOVE,350,650);f.root.onWindowFocusChanged(false);f.root.onWindowFocusChanged(true);f.event(UP,100,650);
        check(f.modules.releases==0&&f.modules.cancels>1,"horizontal focus loss cancels preview and cannot release");
        f=new Fixture();f.down();f.event(MOVE,350,650);f.modules.revision++;f.event(UP,100,650);
        check(f.modules.releases==0&&f.modules.cancels>1,"horizontal activation revision at UP cancels preview");
        f=new Fixture();f.down();f.event(MOVE,350,650);f.modules.enabled=false;f.event(MOVE,100,650);f.modules.enabled=true;f.event(UP,100,650);
        check(f.modules.releases==0&&f.modules.cancels>1,"horizontal lifecycle gate cancels preview without revival");
    }
    private static void directionAndChildProtection(){
        Fixture f=new Fixture();f.small();f.event(MOVE,100,630);f.event(UP,100,630);
        f.unchanged("vertical axis cannot convert to horizontal after child disallows");
        f=new Fixture();f.child.disallowOnDown=true;f.down();f.event(MOVE,340,650);
        f.content.requestDisallowInterceptTouchEvent(false);f.event(MOVE,100,650);f.move(500);f.event(UP,100,500);
        f.unchanged("child disallow remains a horizontal veto even after reallow");
        check(f.child.cancels==0,"child-owned horizontal stream is never stolen");
        f=new Fixture();f.down();f.event(MOVE,348,640);f.move(400);f.event(UP,360,400);
        f.unchanged("ambiguous diagonal does not later fold");
        f=new Fixture();f.down();f.time+=600;f.move(400);f.unchanged("stationary long press remains protected");
        f=new Fixture();f.down();f.event(MOVE,350,650);f.event(MOVE,300,400);f.event(UP,300,400);
        check(f.tabs.getVisibility()==View.VISIBLE&&f.modules.last==2,"horizontal axis never converts to vertical");
    }
    private static void interruption(){
        Fixture f=new Fixture();f.small();f.event(MotionEvent.ACTION_CANCEL,360,640);f.move(400);f.unchanged("cancel stops passive observation");
        f=new Fixture();f.small();f.event(MotionEvent.ACTION_POINTER_DOWN,360,640,2);f.event(MotionEvent.ACTION_POINTER_UP,360,640,1);f.move(400);f.unchanged("multi-touch permanently stops passive observation");
        f=new Fixture();f.small();f.root.onWindowFocusChanged(false);f.root.onWindowFocusChanged(true);f.move(400);f.unchanged("focus loss cannot revive old stream");
        f.event(UP,360,400);f.down();f.move(600);check(f.tabs.getVisibility()==View.GONE,"fresh stream works after focus restoration");
        f=new Fixture();f.small();f.modules.page++;f.move(400);f.modules.page--;f.move(300);f.unchanged("page change cannot target a new page or revive old stream");
        f=new Fixture();f.small();f.modules.revision++;f.move(400);f.modules.revision--;f.move(300);f.unchanged("activation revision invalidates old stream");
        f=new Fixture();f.small();f.modules.enabled=false;f.move(400);f.modules.enabled=true;f.move(300);f.unchanged("disabled lifecycle gate cannot revive stream");
        f=new Fixture();f.small();f.root.modules(f.content,f.modules);f.move(400);f.unchanged("module binding replacement cancels stream");
        f=new Fixture();f.small();f.root.onWindowVisibilityChanged(View.GONE);f.root.onWindowVisibilityChanged(View.VISIBLE);f.move(400);f.unchanged("hidden window cancels stream");
        f=new Fixture();f.small();f.root.onDetachedFromWindow();f.move(400);f.unchanged("detached root cancels stream");
        f=new Fixture();f.small();f.event(UP,360,640);f.move(400);f.unchanged("completed vertical stream cannot later fold");
    }
    private static void sensitiveAndBounds(){
        Fixture f=new Fixture();f.root.focus(new EditText(CONTEXT));f.down();f.move(400);f.unchanged("focused editable input protected");
        f=new Fixture();f.small();f.root.focus(new EditText(CONTEXT));f.move(400);f.root.focus(null);f.move(300);f.unchanged("new sensitive focus invalidates stream permanently");
        f=new Fixture();TextView text=new TextView(CONTEXT);text.setTextIsSelectable(true);f.root.focus(text);f.down();f.move(400);f.unchanged("focused selectable text protected");
        for(View protectedView:new View[]{new EditText(CONTEXT),new SeekBar(CONTEXT),new CompoundButton(CONTEXT),new HorizontalScrollView(CONTEXT),new View(CONTEXT){@Override public boolean canScrollHorizontally(int d){return true;}}}){
            f=new Fixture();protectedView.layout(0,0,720,1000);f.content.addView(protectedView);f.down();f.move(400);f.unchanged("touched protected widget: "+protectedView.getClass().getName());
        }
        f=new Fixture();f.event(DOWN,360,50);f.move(400);f.unchanged("tabs-origin gesture never folds content");
        f=new Fixture();f.event(DOWN,750,650);f.move(400);f.unchanged("outside-content gesture never gains eligibility");
        f=new Fixture();f.root.onWindowFocusChanged(false);f.down();f.root.onWindowFocusChanged(true);f.move(400);f.unchanged("down without focus cannot gain eligibility");
        f=new Fixture();f.modules.enabled=false;f.down();f.modules.enabled=true;f.move(400);f.unchanged("down while inactive cannot gain eligibility");
    }
}

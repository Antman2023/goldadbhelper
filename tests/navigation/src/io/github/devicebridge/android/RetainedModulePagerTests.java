package io.github.devicebridge.android;
import android.content.Context;import android.view.View;import android.animation.ValueAnimator;
/** Deterministic stand-in animation/view plumbing. Does not claim Android input-dispatch coverage. */
public final class RetainedModulePagerTests {
    private static int checks;
    private static final class Fixture implements RetainedModulePager.Pages {
        final Context context=new Context();final View[] views=new View[7];int commits=-1,commitsCount,created;boolean allowed=true;
        public View view(int index){if(views[index]==null){views[index]=new View(context);views[index].setTag(index);created++;}return views[index];}
        public boolean canNavigate(){return allowed;}public void committed(int index){commits=index;commitsCount++;}
        final RetainedModulePager pager=new RetainedModulePager(context,7,this);
    }
    public static void main(String[] args){
        Fixture f=new Fixture();f.pager.show(2);View original=f.views[2];
        check(f.created==3&&f.pager.selected()==2,"initial page and both real neighbor views prepared");
        f.pager.drag(-80);check(f.created==3&&f.commitsCount==0,"first drag reuses prepared neighbor, never activates");
        check(original.getTranslationX()==-80&&f.views[3].getTranslationX()==240,"adjacent pages track finger continuously");
        check(f.views[3].getImportantForAccessibility()==View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,"preview excluded from accessibility focus");
        f.pager.release(2);check(f.pager.settling(),"short drag starts spring back");ValueAnimator.latest.step(.5f);
        check(original.getTranslationX()==-40,"spring back intermediate frame");ValueAnimator.latest.finish();
        check(f.pager.selected()==2&&original.getTranslationX()==0&&f.views[3].getVisibility()==View.GONE&&f.commitsCount==0,"spring back keeps old active page");
        f.pager.drag(-100);f.pager.release(3);ValueAnimator forward=ValueAnimator.latest;forward.step(.5f);
        check(original.getTranslationX()==-210&&f.views[3].getTranslationX()==110,"settle intermediate two-page frame");forward.finish();
        check(f.pager.selected()==3&&f.commits==3&&f.commitsCount==1,"commit once after full settle");
        check(original.getVisibility()==View.GONE&&f.views[3].getTranslationX()==0,"inactive view retained offscreen");
        f.pager.drag(90);f.pager.release(2);ValueAnimator.latest.finish();
        check(f.pager.selected()==2&&f.views[2]==original&&f.created==4,"revisit uses original view and prepares next neighbor");
        f.pager.drag(-90);f.pager.drag(30);check(f.views[3].getVisibility()==View.GONE&&f.views[1].getVisibility()==View.VISIBLE,"direction reversal swaps preview");
        f.pager.cancelMotion();check(original.getTranslationX()==0&&f.pager.selected()==2,"explicit cancel normalizes source");
        f.pager.select(3,true);ValueAnimator old=ValueAnimator.latest;old.step(.3f);f.pager.select(1,true);old.finish();
        check(f.pager.selected()==2,"superseded animation cannot commit");ValueAnimator.latest.finish();check(f.pager.selected()==1,"replacement animation commits requested destination");
        f.pager.select(2,true);ValueAnimator pending=ValueAnimator.latest;f.allowed=false;pending.step(.5f);pending.finish();
        check(f.pager.selected()==1&&!f.pager.settling()&&f.views[1].getTranslationX()==0,"lost session eligibility cancels in-flight animation");
        f.allowed=true;f.pager.select(2,true);pending=ValueAnimator.latest;f.pager.resize(480,320);pending.finish();
        check(f.pager.selected()==1&&!f.pager.settling(),"rotation/resize cannot commit old animation");
        f.pager.show(0);f.pager.drag(100);check(f.views[0].getTranslationX()==22,"edge drag damped");f.pager.release(0);ValueAnimator.latest.finish();
        check(f.pager.selected()==0&&f.views[0].getTranslationX()==0,"edge settles in bounds");
        f.pager.show(6);f.pager.drag(-100);check(f.views[6].getTranslationX()==-22,"last edge drag damped");f.pager.release(6);ValueAnimator.latest.finish();
        f.pager.show(2);View replacement=new View(f.context);replacement.setTag(2);f.views[2]=replacement;f.pager.replace(2);
        check(original.getParent()==null&&replacement.getParent()==f.pager&&replacement.getVisibility()==View.VISIBLE,"refresh removes only old selected view");
        int commits=f.commitsCount;f.pager.select(3,true);pending=ValueAnimator.latest;f.pager.dispose();pending.finish();
        check(f.pager.getChildCount()==0&&f.pager.selected()==-1&&f.commitsCount==commits,"dispose detaches all views and prevents stale commit");
        f.pager.select(1,true);f.pager.drag(-50);check(f.pager.getChildCount()==0,"disposed pager cannot reattach");
        System.out.println("RETAINED MODULE PAGER HOST TESTS: "+checks+" passed");
    }
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
}

package io.github.devicebridge.android;
import android.app.*;import android.content.*;import android.graphics.Color;import android.graphics.Typeface;import android.graphics.drawable.GradientDrawable;import android.view.*;import android.widget.*;
final class Ui {
    /** The Activity closes owned windows before Android tears down its window token. */
    interface DialogOwner { void ownDialog(Dialog dialog); }
    private static <T extends Dialog> T own(Context context,T dialog){if(context instanceof DialogOwner)((DialogOwner)context).ownDialog(dialog);return dialog;}

    static final int BLUE=0xff2196f3,INK=0xff263238,MUTED=0xff607d8b,BG=0xfffafafa;
    static int dp(Context c,float v){return (int)(c.getResources().getDisplayMetrics().density*v+.5f);}
    static LinearLayout col(Context c){LinearLayout v=new LinearLayout(c);v.setOrientation(LinearLayout.VERTICAL);return v;}
    static LinearLayout row(Context c){LinearLayout v=new LinearLayout(c);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    static TextView text(Context c,String s,int sp){TextView v=new TextView(c);v.setText(s);v.setTextSize(sp);v.setTextColor(INK);v.setPadding(dp(c,16),dp(c,10),dp(c,16),dp(c,10));return v;}
    static Button button(Context c,String s,Runnable r){Button b=new Button(c);b.setText(s);b.setAllCaps(false);b.setOnClickListener(v->r.run());return b;}
    static EditText edit(Context c,String hint){EditText e=new EditText(c);e.setTextSize(15);e.setSingleLine(true);e.setHint(hint);return e;}
    static void expanded(LinearLayout row,View v){row.addView(v,new LinearLayout.LayoutParams(0,-2,1));}
    static void fill(LinearLayout col,View v){col.addView(v,new LinearLayout.LayoutParams(-1,0,1));}
    static void inset(Activity a,View root){Compatibility.inset(root);}
    static void message(Context c,String title,String message){own(c,new AlertDialog.Builder(c).setTitle(title).setMessage(message).setPositiveButton("确定",null).show());}
    static void confirm(Context c,String title,String message,Runnable action,Runnable cancelled){
        final boolean[] resolved={false};Runnable reject=()->{if(!resolved[0]){resolved[0]=true;cancelled.run();}};
        own(c,new AlertDialog.Builder(c).setTitle(title).setMessage(message).setNegativeButton("取消",(d,w)->reject.run()).setOnCancelListener(d->reject.run()).setPositiveButton("确认",(d,w)->{if(!resolved[0]){resolved[0]=true;action.run();}}).show());
    }
    static void confirm(Context c,String title,String message,Runnable action){confirm(c,title,message,action,()->{});}
    interface Input {void accept(String text);}
    static void prompt(Context c,String title,String value,Input action){EditText e=edit(c,title);e.setText(value);e.setSelection(e.length());LinearLayout l=col(c);l.setPadding(dp(c,20),0,dp(c,20),0);l.addView(e);AlertDialog d=new AlertDialog.Builder(c).setTitle(title).setView(l).setNegativeButton("取消",null).setPositiveButton("确定",null).create();d.setOnShowListener(x->d.getButton(-1).setOnClickListener(v->{if(e.getText().toString().trim().isEmpty()){e.setError("不能为空");return;}d.dismiss();action.accept(e.getText().toString());}));d.show();own(c,d);}
    static View divider(Context c){View v=new View(c);v.setBackgroundColor(0xffe4e8ea);v.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(c,1)));return v;}
    static GradientDrawable rounded(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
}

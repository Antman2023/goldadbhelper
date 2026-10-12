package io.github.devicebridge.android;
import android.content.Context;import android.graphics.*;import android.view.View;
/** Independent geometric icons; no reference application art is bundled. */
final class IconView extends View {
 enum Kind{HELP,LINK,BOLT,MORE,HISTORY,SEARCH,MENU,FILTER,SORT,BOOKMARK}
 private final Kind kind;private final Paint p=new Paint(3);private final int color;
 IconView(Context c,Kind kind,int color,String description,Runnable action){super(c);this.kind=kind;this.color=color;setContentDescription(description);setFocusable(true);setClickable(true);setOnClickListener(v->action.run());setMinimumHeight(Ui.dp(c,48));setMinimumWidth(Ui.dp(c,48));}
 protected void onDraw(Canvas canvas){super.onDraw(canvas);canvas.save();float scale=Math.min(getWidth(),getHeight())/48f;canvas.translate(getWidth()/2f,getHeight()/2f);canvas.scale(scale,scale);p.setColor(color);p.setStrokeWidth(1.8f);p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);
  switch(kind){case MENU:for(int i=-1;i<=1;i++)canvas.drawLine(-8,i*5,8,i*5,p);break;case HELP:canvas.drawCircle(0,0,9,p);p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(15);canvas.drawText("?",0,5.5f,p);break;
   case LINK:canvas.drawArc(new RectF(-10,-4,0,4),90,180,false,p);canvas.drawArc(new RectF(0,-4,10,4),-90,180,false,p);canvas.drawLine(-4,-4,1,-4,p);canvas.drawLine(-1,4,4,4,p);canvas.drawLine(-4,0,4,0,p);break;
   case BOLT:p.setStyle(Paint.Style.FILL);Path b=new Path();b.moveTo(-5,-10);b.lineTo(5,-10);b.lineTo(0,-2);b.lineTo(5,-2);b.lineTo(-4,11);b.lineTo(-2,1);b.lineTo(-5,1);b.close();canvas.drawPath(b,p);break;
   case MORE:p.setStyle(Paint.Style.FILL);for(int i=-1;i<=1;i++)canvas.drawCircle(0,i*6,1.8f,p);break;
   case HISTORY:canvas.drawArc(new RectF(-8,-8,8,8),195,320,false,p);canvas.drawLine(-8,1,-11,1,p);canvas.drawLine(-11,1,-8,5,p);canvas.drawLine(-8,5,-5,1,p);canvas.drawLine(0,-4,0,0,p);canvas.drawLine(0,0,3,2,p);break;
   case FILTER:canvas.drawLine(-9,-6,9,-6,p);canvas.drawLine(-6,0,6,0,p);canvas.drawLine(-2,6,2,6,p);break;
   case SORT:canvas.drawLine(-7,-7,-7,8,p);canvas.drawLine(-10,5,-7,8,p);canvas.drawLine(-7,8,-4,5,p);canvas.drawLine(0,-6,9,-6,p);canvas.drawLine(0,0,6,0,p);canvas.drawLine(0,6,3,6,p);break;
   case BOOKMARK:Path mark=new Path();mark.moveTo(-6,-9);mark.lineTo(6,-9);mark.lineTo(6,9);mark.lineTo(0,5);mark.lineTo(-6,9);mark.close();canvas.drawPath(mark,p);break;
   case SEARCH:canvas.drawCircle(-2,-2,5.5f,p);canvas.drawLine(2.5f,2.5f,8,8,p);break;
  }canvas.restore();}
}

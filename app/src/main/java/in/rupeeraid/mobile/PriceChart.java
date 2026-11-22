package in.rupeeraid.mobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import static in.rupeeraid.mobile.MarketWidgets.*;

/** Plots native prices in timestamp order. Unavailable offers break the step line. */
final class PriceChart extends View {
    private final MarketWidgets w;
    private final List<JSONObject> points=new ArrayList<>();
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private long min=Long.MAX_VALUE,max,first,last;
    private int selected;
    private TextView caption;
    private SeekBar seek;
    PriceChart(Context c,MarketWidgets w,List<JSONObject> records){
        super(c);this.w=w;
        for(JSONObject row:records){
            if(time(row)<=0)continue;
            long amount=amount(row);
            if(amount>=0){min=Math.min(min,amount);max=Math.max(max,amount);}
            points.add(row);
        }
        if(!points.isEmpty()){first=time(points.get(0));last=time(points.get(points.size()-1));selected=points.size()-1;}
        setBackground(w.shape(PANEL,7));setContentDescription("Regional store price history. Use the slider below to explore individual dates.");
    }
    static long time(JSONObject row){try{return Instant.parse(row.getString("timestamp")).getEpochSecond();}catch(Exception e){return 0;}}
    private static long amount(JSONObject row){JSONObject deal=row.optJSONObject("deal");return deal==null?-1:Market.nativeAmount(row.optJSONObject("shop"),deal.optJSONObject("price"));}
    void attach(LinearLayout parent){
        if(points.isEmpty() || min==Long.MAX_VALUE){parent.addView(w.text("No dated prices available.",13,MUTED,false));return;}
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,w.dp(145));lp.topMargin=w.dp(9);parent.addView(this,lp);
        caption=w.text("",14,WHITE,true);caption.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);w.pad(caption,0,8,0,0);parent.addView(caption);
        seek=new SeekBar(getContext());seek.setMax(points.size()-1);seek.setProgress(selected);seek.setContentDescription("Select a price observation by date");
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){}
            public void onProgressChanged(SeekBar b,int pos,boolean user){select(pos);}
        });
        parent.addView(seek);
        LinearLayout dates=w.row();parent.addView(dates);dates.addView(w.text(GamePage.date(Market.text(points.get(0),"timestamp")),11,MUTED,false),w.flex());
        dates.addView(w.text(GamePage.date(Market.text(points.get(points.size()-1),"timestamp")),11,MUTED,false));
        TextView range=w.text("Period low  "+money(min)+"     High  "+money(max),12,MUTED,false);parent.addView(range);select(selected);
    }
    private void select(int index){
        selected=index;JSONObject row=points.get(index);long value=amount(row);
        caption.setText(GamePage.date(Market.text(row,"timestamp"))+"   ·   "+(value<0?"Offer unavailable":money(value)));invalidate();
    }
    private float x(JSONObject row){return w.dp(12)+(getWidth()-2*w.dp(12))*(last==first?.5f:(float)(time(row)-first)/(last-first));}
    private float y(long value){return getHeight()-w.dp(14)-(getHeight()-2*w.dp(14))*(max==min?.5f:(float)(value-min)/(max-min));}
    @Override protected void onDraw(Canvas canvas){
        if(points.isEmpty() || min==Long.MAX_VALUE)return;
        paint.setColor(LINE);paint.setStrokeWidth(w.dp(1));for(int i=0;i<3;i++){float y=w.dp(14)+i*(getHeight()-2*w.dp(14))*.5f;canvas.drawLine(w.dp(12),y,getWidth()-w.dp(12),y,paint);}
        long prior=-1;float before=0;
        paint.setColor(GREEN);paint.setStrokeWidth(w.dp(2));
        for(JSONObject row:points){
            long value=amount(row);float x=x(row);
            if(prior>=0){canvas.drawLine(before,y(prior),x,y(prior),paint);if(value>=0)canvas.drawLine(x,y(prior),x,y(value),paint);}
            if(value>=0)canvas.drawCircle(x,y(value),w.dp(2),paint);prior=value;before=x;
        }
        JSONObject chosen=points.get(selected);float x=x(chosen);paint.setColor(LILAC);paint.setStrokeWidth(w.dp(1));
        canvas.drawLine(x,w.dp(6),x,getHeight()-w.dp(6),paint);
        if(amount(chosen)>=0)canvas.drawCircle(x,y(amount(chosen)),w.dp(4),paint);
    }
    @Override public boolean performClick(){super.performClick();return true;}
    @Override public boolean onTouchEvent(MotionEvent event){
        if(points.isEmpty() || seek==null)return false;
        if(event.getAction()==MotionEvent.ACTION_DOWN || event.getAction()==MotionEvent.ACTION_MOVE || event.getAction()==MotionEvent.ACTION_UP){
            int nearest=0;float dist=Float.MAX_VALUE;
            for(int i=0;i<points.size();i++){float delta=Math.abs(event.getX()-x(points.get(i)));if(delta<dist){dist=delta;nearest=i;}}
            seek.setProgress(nearest);if(event.getAction()==MotionEvent.ACTION_UP)performClick();return true;
        }
        return false;
    }
}

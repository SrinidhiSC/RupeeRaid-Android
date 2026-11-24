package in.rupeeraid.mobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import static in.rupeeraid.mobile.MarketWidgets.*;

/** Offline artwork for setup: a moonlit mountain level in the active theme. */
final class WelcomeLandscape extends View {
    private final Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path distant=ridge(new int[]{0,143,42,129,70,142,120,102,147,124,204,81,258,127,306,99,360,124});
    private final Path near=ridge(new int[]{0,152,28,141,57,157,91,148,133,168,177,135,200,150,243,125,270,138,310,117,360,139});
    private final RadialGradient halo=new RadialGradient(236,87,86,new int[]{ACCENT_SURFACE,PANEL,BG},null,Shader.TileMode.CLAMP);
    private final LinearGradient moon=new LinearGradient(0,50,0,123,SECONDARY,ACCENT,Shader.TileMode.CLAMP);
    private final LinearGradient valley=new LinearGradient(0,125,0,211,ACCENT_SURFACE,PANEL,Shader.TileMode.CLAMP);
    private final boolean animate;
    WelcomeLandscape(Context context){
        super(context);animate=motion(context);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    private static Path ridge(int[] points){
        Path path=new Path();path.moveTo(-400,240);path.lineTo(-400,points[1]);
        for(int i=0;i<points.length;i+=2)path.lineTo(points[i],points[i+1]);
        path.lineTo(760,points[points.length-1]);path.lineTo(760,240);path.close();return path;
    }
    @Override protected void onDraw(Canvas canvas){
        float scale=Math.min(getWidth()/360f,getHeight()/215f);
        canvas.save();
        canvas.translate((getWidth()-360*scale)/2,(getHeight()-215*scale)/2);
        canvas.scale(scale,scale);
        ink.setColor(-1);ink.setShader(halo);canvas.drawRect(-400,-200,760,240,ink);
        ink.setShader(null);
        double phase=animate?android.os.SystemClock.uptimeMillis()/1700.0:0;
        for(int i=0;i<54;i++){
            int x=(i*73+17)%360,y=(i*i*17+23)%136;
            ink.setColor(MUTED);ink.setAlpha((int)(114+54*Math.sin(phase+i)));
            canvas.drawCircle(x,y,i%7==0?1.2f:.65f,ink);
        }
        ink.setColor(-1);ink.setShader(moon);canvas.drawCircle(236,87,36,ink);
        ink.setShader(null);ink.setColor(ACCENT_SURFACE);canvas.drawPath(distant,ink);
        ink.setColor(-1);ink.setShader(valley);canvas.drawPath(near,ink);ink.setShader(null);
        // A line of small conifers gives the foreground a sense of scale.
        ink.setColor(PANEL);
        for(int i=0;i<34;i++){
            float x=i*12-16,y=179+(float)Math.sin(i*.42)*10,h=6+(i*7)%19;
            for(int j=0;j<4;j++)canvas.drawRect(x-j-1,y-h+j*h/5,x+j+1,y+2,ink);
            canvas.drawRect(x-1,y,x+1,y+6,ink);
        }
        canvas.restore();
        if(animate && isShown() && getWindowVisibility()==VISIBLE)postInvalidateDelayed(80);
    }
}

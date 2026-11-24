package in.rupeeraid.mobile;

import android.Manifest;
import android.app.*;
import android.app.job.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public final class AlertJob extends JobService {
    // Foreground writes, token rotation and background checks share a single ordered queue.
    static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private static final int JOB=8141;
    private static final String CHANNEL="store-price-alerts";
    private volatile int generation;
    static void schedule(Context c,boolean enabled){
        JobScheduler jobs=c.getSystemService(JobScheduler.class);
        if(!enabled){jobs.cancel(JOB);return;}
        if(jobs.getPendingJob(JOB)==null)jobs.schedule(new JobInfo.Builder(JOB,new ComponentName(c,AlertJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(30*60*1000L,5*60*1000L).build());
    }
    static String label(String mode){
        if(PriceAlerts.LOW.equals(mode))return "Historical low";
        if(PriceAlerts.YEAR_LOW.equals(mode))return "One-year low";
        if(PriceAlerts.QUARTER_LOW.equals(mode))return "Three-month low";
        if(PriceAlerts.STORE_LOW.equals(mode))return "Store historical low";
        return PriceAlerts.TARGET.equals(mode)?"Target price reached":"Price drop";
    }
    static void deliver(Context c,PriceAlerts alerts) throws Exception{
        NotificationManager manager=c.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"Game price alerts",NotificationManager.IMPORTANCE_DEFAULT));
        if(Build.VERSION.SDK_INT>=33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;
        if(!manager.areNotificationsEnabled())return;
        java.util.List<JSONObject> fresh=new java.util.ArrayList<>();
        java.util.Map<String,String> games=new java.util.LinkedHashMap<>();
        Notification.InboxStyle lines=new Notification.InboxStyle();
        for(JSONObject event:Market.objects(alerts.inbox)){
            if(event.optBoolean("delivered") || event.optBoolean("read"))continue;
            JSONObject game=event.getJSONObject("game");
            String title=Market.text(game,"title");
            games.put(game.getString("id"),title);
            if(fresh.size()<6)lines.addLine(label(event.getString("mode"))+" · "+title+" · "+MarketWidgets.money(event.getLong("amount")));
            fresh.add(event);
        }
        if(fresh.isEmpty())return;
        JSONObject first=fresh.get(0);
        java.util.List<String> titles=new java.util.ArrayList<>(games.values());
        String summary=String.join(", ",titles.subList(0,Math.min(2,titles.size())));
        if(titles.size()>2)summary+=" and "+(titles.size()-2)+" more";
        Intent view=new Intent(c,MainActivity.class).setAction("in.rupeeraid.mobile.PRICE_ALERT");
        String heading="Price alerts for "+titles.size()+(titles.size()==1?" game":" games");
        if(fresh.size()==1){
            heading=label(first.getString("mode"))+" · "+titles.get(0);
            summary=MarketWidgets.money(first.getLong("amount"))+" at "+Market.text(first,"shop");
            view.putExtra("alertId",first.getString("id"));
        }
        PendingIntent pending=PendingIntent.getActivity(c,JOB,view,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        android.graphics.Bitmap badge=android.graphics.Bitmap.createBitmap(128,128,android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas=new android.graphics.Canvas(badge);
        canvas.drawColor(android.graphics.Color.BLACK);
        android.graphics.drawable.Drawable mark=c.getDrawable(R.drawable.logo_amoled_pitch);
        mark.setBounds(8,8,120,120);mark.draw(canvas);
        Notification note=new Notification.Builder(c,CHANNEL).setSmallIcon(R.drawable.logo_amoled_pitch).setLargeIcon(badge)
            .setColor(android.graphics.Color.BLACK).setContentTitle(heading).setContentText(summary)
            .setStyle(lines.setBigContentTitle(heading).setSummaryText(summary)).setTimeoutAfter(30L*24*60*60*1000)
            .setAutoCancel(true).setContentIntent(pending).build();
        manager.notify(JOB,note);
        for(JSONObject event:fresh)event.put("delivered",true);
        alerts.save();
    }
    @Override public boolean onStartJob(JobParameters params){
        final int run=++generation;
        IO.execute(()->{
            boolean retry=false;
            try{
                Vault vault=new Vault(this);PriceAlerts alerts=new PriceAlerts(vault);
                if(run!=generation || vault.read("oauth").isEmpty() || !alerts.monitoring()){if(run==generation)jobFinished(params,false);return;}
                String client=getSharedPreferences("MainActivity",MODE_PRIVATE).getString("clientId",BuildConfig.ITAD_CLIENT_ID);
                MobileApi api=new MobileApi(vault,client,MobileApi::send);
                try{JSONObject prices=api.monitoredPrices(alerts);if(run!=generation)return;
                    alerts.observe(prices,System.currentTimeMillis()/1000);deliver(this,alerts);
                }catch(Exception failure){
                    if(run!=generation)return;
                    alerts.document.put("error",failure instanceof MobileApi.Failure?failure.getMessage():"Price check deferred until a connection is available.");alerts.save();
                    retry=!(failure instanceof MobileApi.Failure) || ((MobileApi.Failure)failure).status>=500;
                }
            }catch(Exception e){retry=true;}
            if(run==generation)jobFinished(params,retry);
        });return true;
    }
    @Override public boolean onStopJob(JobParameters params){generation++;return true;}
}

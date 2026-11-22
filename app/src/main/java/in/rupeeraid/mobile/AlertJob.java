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
        boolean changed=false;
        for(JSONObject event:Market.objects(alerts.inbox)){
            if(event.optBoolean("delivered") || event.optBoolean("read"))continue;
            String title=Market.text(event.getJSONObject("game"),"title"),id=event.getString("id");
            Intent view=new Intent(c,MainActivity.class).setAction("in.rupeeraid.mobile.PRICE_ALERT").putExtra("alertId",id);
            view.setData(android.net.Uri.fromParts("rupeeraid-alert",id,null));
            PendingIntent pending=PendingIntent.getActivity(c,id.hashCode(),view,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            Notification note=new Notification.Builder(c,CHANNEL).setSmallIcon(R.drawable.price_notification)
                    .setContentTitle(label(event.getString("mode"))+" · "+title)
                    .setContentText(MarketWidgets.money(event.getLong("amount"))+" at "+Market.text(event,"shop"))
                    .setWhen(event.getLong("when")*1000).setOnlyAlertOnce(true).setAutoCancel(true).setContentIntent(pending).build();
            manager.notify(id,1,note);event.put("delivered",true);changed=true;
        }
        if(changed)alerts.save();
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

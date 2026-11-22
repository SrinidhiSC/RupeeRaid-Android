package in.rupeeraid.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Base64;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private final ExecutorService io=AlertJob.IO;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private Vault vault;
    private MobileApi api;
    private MarketScreen screen;
    private String clientId;
    private Intent deferred;
    JSONObject data=new JSONObject(),alertState=new JSONObject();
    String note="";
    boolean signedIn,personal,busy,ready;
    boolean manualSync;
    long backoff;
    private long lastAutoAttempt;
    private final Runnable tick=new Runnable(){
        public void run(){screen.clock();handler.postDelayed(this,1000);}
    };

    @Override public void onCreate(Bundle saved) {
        android.content.SharedPreferences appearance=getSharedPreferences("appearance",0);
        boolean black=appearance.getBoolean("blackCanvas","AMOLED".equals(appearance.getString("theme","Midnight")));
        setTheme(AppTheme.saved(this).style);
        super.onCreate(saved);
        LauncherIcons.select(this,appearance.getString("logo",MarketWidgets.LOGOS[0]));
        MarketWidgets.applyPalette(this,black);
        getWindow().setStatusBarColor(MarketWidgets.BAR);
        getWindow().setNavigationBarColor(MarketWidgets.BAR);
        applyRefreshRate();
        vault=new Vault(this);
        clientId=getPreferences(0).getString("clientId",BuildConfig.ITAD_CLIENT_ID);
        api=new MobileApi(vault,clientId,MobileApi::send);
        screen=new MarketScreen(this);
        if(saved!=null)screen.restore(saved);
        screen.render(false);
        io.execute(()->{
            boolean session=!vault.read("oauth").isEmpty();
            boolean key=session && !vault.read("personalKey").isEmpty();
            JSONObject cached=new JSONObject();
            if(session)try{cached=MobileApi.restoreSnapshot(vault.read("snapshot"));}catch(Exception ignored){}
            final JSONObject snapshot=cached,alerts=session?readAlerts():new JSONObject();
            if(session)AlertJob.schedule(this,alerts.has("universal") || alerts.optJSONObject("rules")!=null && alerts.optJSONObject("rules").length()>0);
            runOnUiThread(()->{
                if(isDestroyed())return;
                data=snapshot;alertState=alerts;signedIn=session;personal=key;ready=true;
                backoff=getPreferences(0).getLong("backoff",0);
                screen.render(false);
                if(!handle(getIntent()) && signedIn){
                    refreshIfExpired();
                    if(screen.showingLibrary())libraryPrices();
                }
            });
        });
    }
    void applyRefreshRate(){
        android.view.Display display=getWindowManager().getDefaultDisplay();
        float highest=0;
        if(getSharedPreferences("appearance",0).getBoolean("highRefresh",true)){
            android.view.Display.Mode current=display.getMode();
            for(android.view.Display.Mode mode:display.getSupportedModes()){
                if(mode.getPhysicalWidth()==current.getPhysicalWidth() && mode.getPhysicalHeight()==current.getPhysicalHeight())
                    highest=Math.max(highest,mode.getRefreshRate());
            }
        }
        android.view.WindowManager.LayoutParams params=getWindow().getAttributes();
        params.preferredRefreshRate=highest;getWindow().setAttributes(params);
    }
    @Override protected void onNewIntent(Intent intent) {super.onNewIntent(intent);setIntent(intent);if(ready)handle(intent);}
    @Override protected void onStart() {super.onStart();screen.resumeSearch();handler.post(tick);if(ready && signedIn){reloadAlerts();refreshIfExpired();}}
    @Override protected void onStop() {screen.pauseSearch();handler.removeCallbacks(tick);super.onStop();}
    @Override protected void onDestroy(){screen.dispose();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);screen.save(b);}
    @Override public void onBackPressed(){if(!screen.back())super.onBackPressed();}

    static long now(){return System.currentTimeMillis()/1000;}
    long remaining(){return Math.max(0,backoff-now());}
    static boolean providerPage(Uri uri){
        String host=uri.getHost();
        if(host==null)return false;
        host=host.toLowerCase(java.util.Locale.ROOT);
        return host.equals("isthereanydeal.com") || host.endsWith(".isthereanydeal.com");
    }
    void open(Uri url){
        try{startActivity(new Intent(Intent.ACTION_VIEW,url));}
        catch(ActivityNotFoundException e){notice("Install a browser to open this link.");}
    }
    void notice(String message){note=message;screen.status();}
    private interface Work {JSONObject execute() throws Exception;}
    private void run(Work work,Consumer<JSONObject> done){
        if(busy)return;
        busy=true;note="";screen.status();
        io.execute(()->{
            try{
                JSONObject result=work.execute();
                runOnUiThread(()->{
                    if(isDestroyed())return;
                    busy=false;manualSync=false;done.accept(result);screen.status();resumeCallback();
                });
            }catch(Exception e){runOnUiThread(()->{
                if(isDestroyed())return;
                busy=false;manualSync=false;
                if(e instanceof MobileApi.Failure){
                    MobileApi.Failure failure=(MobileApi.Failure)e;
                    note=failure.getMessage();
                    if(failure.retry>0)pause(failure.retry);
                    if(failure.status==401 && signedIn){note="Your session needs attention. Reconnect IsThereAnyDeal in Account. Your saved lists are available.";}
                    if(failure instanceof MobileApi.Consent && signedIn)permissionDialog(((MobileApi.Consent)failure).scope);
                    if(failure.status==409 && signedIn && !vault.read("nextSession").isEmpty())switchSessionDialog();
                }else note="Couldn’t finish syncing. Check your connection. Saved games are still available.";
                screen.render(false);resumeCallback();
            });}
        });
    }
    private void pause(int seconds){backoff=now()+seconds;getPreferences(0).edit().putLong("backoff",backoff).apply();}
    private void resumeCallback(){if(!busy && deferred!=null){Intent next=deferred;deferred=null;callback(next);}}
    void refreshIfExpired(){
        if(!signedIn || busy || remaining()>0 || now()-lastAutoAttempt<60)return;
        if(data.optInt("schema")==2 && MobileApi.fresh(data.optLong("saved"),now()) &&
                Market.text(data,"warning").isEmpty())return;
        lastAutoAttempt=now();
        refresh();
    }
    void manualRefresh(){
        if(busy)return;
        if(remaining()>0){notice("Refresh available in "+remaining()+" seconds.");return;}
        refresh(true);
    }
    private void refresh(){ refresh(false); }
    private void refresh(boolean force){
        if(!signedIn || busy || remaining()>0)return;
        manualSync=force;
        final JSONObject previous=data;
        run(()->{
            if(force)api.beginRefresh();
            JSONObject snapshot=api.dashboard();
            // Keep the prior quotes for editions still owned when a dashboard refresh only returns membership.
            JSONObject cachedLibrary=new JSONObject();
            for(JSONObject game:Market.objects(previous.optJSONArray("library")))
                cachedLibrary.put(game.getString("id"),game.optJSONArray("offers"));
            for(JSONObject game:Market.objects(snapshot.optJSONArray("library"))){
                JSONArray prior=cachedLibrary.optJSONArray(game.getString("id"));
                if(prior!=null && prior.length()>0 && game.optJSONArray("offers").length()==0)game.put("offers",prior);
            }
            snapshot.put("librarySaved",previous.optLong("librarySaved"));
            if(force && Market.text(snapshot,"warning").isEmpty()) {
                try {
                    snapshot.put("library",api.library(snapshot.getJSONArray("library"))).put("librarySaved",now());
                } catch (java.io.IOException unavailable) {
                    snapshot.put("warning","Library prices could not be updated.");
                    if(unavailable instanceof MobileApi.Failure)snapshot.put("retryAfter",((MobileApi.Failure)unavailable).retry);
                }
            }
            vault.write("snapshot",snapshot.toString());
            PriceAlerts alerts=new PriceAlerts(vault);
            JSONObject universal=alerts.document.optJSONObject("universal");
            if(universal!=null && universal.optBoolean("enabled") && snapshot.has("wishlist") && Market.text(snapshot,"warning").isEmpty())
                alerts.reconcileUniversal(snapshot.optJSONArray("wishlist"));
            if(alerts.rules.length()>0 && Market.text(snapshot,"warning").isEmpty()){
                JSONObject prices=new JSONObject();
                for(String section:new String[]{"deals","trending","wishlist"})for(JSONObject g:Market.objects(snapshot.optJSONArray(section))){
                    if(g.optJSONArray("offers")!=null && g.getJSONArray("offers").length()>0)prices.put(g.getString("id"),g.getJSONArray("offers"));
                }
                // A discovery row can contain only the headline deal. Compare complete store lists only.
                JSONObject complete=new JSONObject();
                for(JSONObject g:Market.objects(snapshot.optJSONArray("wishlist")))complete.put(g.getString("id"),g.optJSONArray("offers"));
                if(snapshot.optBoolean("personal"))complete=prices;
                alerts.observe(complete,now());AlertJob.deliver(this,alerts);
            }
            return snapshot;
        },snapshot->{
            data=snapshot;personal=data.optBoolean("personal");note=Market.text(data,"warning");
            int retry=data.optInt("retryAfter");if(retry>0)pause(retry);
            if(force)screen.refreshDetails();
            screen.updated();if(force)screen.refreshSelectedPrices();reloadAlerts();
        });
    }
    void libraryPrices(){
        JSONArray collection=data.optJSONArray("library");
        if(busy || backoff>now() || collection==null || collection.length()==0 || MobileApi.fresh(data.optLong("librarySaved"),now()))return;
        final JSONObject snapshot=data;
        run(()->{
            JSONObject updated=new JSONObject(snapshot.toString()).put("library",api.library(collection)).put("librarySaved",now());
            vault.write("snapshot",updated.toString());return updated;
        },updated->{data=updated;screen.render(false);});
    }
    void detailPrices(JSONObject game){
        if(!personal || backoff>now())return;
        io.execute(()->{
            if(isDestroyed() || !signedIn)return;
            try{JSONObject item=api.detailPrices(game);runOnUiThread(()->{if(!isDestroyed() && signedIn)screen.detailResult(item);});}
            catch(Exception ex){runOnUiThread(()->{if(!isDestroyed())screen.offerError(game,ex instanceof MobileApi.Failure?ex.getMessage():"Couldn’t refresh offers. Showing saved prices.");});}
        });
    }
    void gameSection(JSONObject game,String tab,Consumer<JSONObject> done){
        io.execute(()->{
            if(isDestroyed() || !signedIn)return;
            JSONObject page;
            try{page=new GameCatalog(api,vault).load(game,tab);}
            catch(Exception e){page=new JSONObject();try{page.put("message","Couldn’t read this game’s details.");}catch(Exception ignored){}}
            final JSONObject result=page;
            runOnUiThread(()->{if(!isDestroyed() && signedIn)done.accept(result);});
        });
    }
    void editGameNote(JSONObject game){
        String id=Market.text(game,"id");
        run(()->api.gameNote(id),result->{
            EditText input=new EditText(this);input.setMinLines(2);input.setMaxLines(7);input.setHint("Your note about this game");
            input.setText(Market.text(result,"note"));input.setSelection(input.length());
            LinearLayout frame=new LinearLayout(this);int pad=(int)(20*getResources().getDisplayMetrics().density);frame.setPadding(pad,0,pad,0);
            frame.addView(input,new LinearLayout.LayoutParams(-1,-2));
            AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle("Your ITAD note").setView(frame).setNegativeButton("Cancel",null);
            if(result.optBoolean("cached"))dialog.setMessage("Showing the note last saved on this phone.");
            dialog.setPositiveButton("Save",(d,i)->{
                String value=input.getText().toString().trim();
                if(value.equals(result.optString("note")))return;
                run(()->{api.writeNote(id,value);return new JSONObject();},ok->notice(value.isEmpty()?"Your note was removed.":"Your note was saved on IsThereAnyDeal."));
            }).show();
        });
    }
    void onlineSearch(String term,java.util.function.BooleanSupplier current,java.util.function.BiConsumer<JSONArray,String> done){
        io.execute(()->{
            JSONArray hits=null;String error="";
            if(current.getAsBoolean())try{hits=api.search(term);}
            catch(MobileApi.Failure e){error=e.getMessage();
                if(e.status==429){long until=now()+e.retry;runOnUiThread(()->{backoff=Math.max(backoff,until);getPreferences(0).edit().putLong("backoff",backoff).apply();});}
            }catch(Exception e){error="Catalog offline · showing saved matches";}
            final JSONArray result=hits;final String message=error;
            runOnUiThread(()->{if(!isDestroyed())done.accept(result,message);});
        });
    }
    void changeRegion(String country) {
        if (country.equals(PricingRegion.current.country)) return;
        if (busy) { notice("Wait for the current sync to finish."); return; }
        run(() -> {
            PricingRegion region = api.resolveRegion(country);
            region.save(this);
            getSystemService(android.app.NotificationManager.class).cancelAll();
            return new JSONObject();
        }, ignored -> recreate());
    }

    void clearArtwork() {
        com.bumptech.glide.Glide glide = com.bumptech.glide.Glide.get(this);
        glide.clearMemory();
        io.execute(() -> {
            glide.clearDiskCache();
            runOnUiThread(() -> { if (!isDestroyed()) notice("Image cache cleared."); });
        });
    }

    void editKey(){
        if(busy)return;
        input("Personal IsThereAnyDeal API key","",true,key->{if(!key.trim().isEmpty())setKey(key.trim());});
    }
    void setKey(String value){
        run(()->{
            api.setKey(value);
            return new JSONObject();
        },ignored->{
            personal=!value.isEmpty();screen.keyChanged();
            if(personal && backoff<=now())refresh();
            else notice(personal?"Personal key saved.":"Personal key removed.");
        });
    }
    void changeList(JSONObject game,String list,boolean add){
        if(busy)return;
        if(backoff>now()){notice("Try again after the provider’s rate-limit window.");return;}
        final JSONObject current=data;
        run(()->{
            api.changeList(list,game.getString("id"),add);
            JSONObject updated=updateList(current,game,list,add);
            if(list.equals("wishlist")){
                PriceAlerts alerts=new PriceAlerts(vault);
                JSONObject universal=alerts.document.optJSONObject("universal");
                if(universal!=null && universal.optBoolean("enabled"))
                    alerts.reconcileUniversal(updated.optJSONArray("wishlist"));
                if(!add && vault.read("personalKey").isEmpty() && alerts.rules.has(game.getString("id")))alerts.disable(game.getString("id"));
                AlertJob.schedule(this,alerts.monitoring());
            }
            return updated;
        },updated->{data=updated;note="Your IsThereAnyDeal list was updated.";screen.render(false);reloadAlerts();});
    }
    private JSONObject updateList(JSONObject current,JSONObject game,String list,boolean add) throws Exception{
        JSONObject updated=new JSONObject(current.toString());JSONArray next=new JSONArray();String id=game.getString("id");
        for(JSONObject item:Market.objects(updated.optJSONArray(list)))if(!id.equals(item.optString("id")))next.put(item);
        if(add)next.put(game);
        updated.put(list,next);vault.write("snapshot",updated.toString());return updated;
    }
    private JSONObject readAlerts(){
        try{
            PriceAlerts alerts=new PriceAlerts(vault);
            if(alerts.unread()>0 && !vault.read("oauth").isEmpty()){
                try{AlertJob.deliver(this,alerts);}catch(Exception ignored){/* The device inbox still holds the event. */}
            }
            return alerts.document;
        }catch(Exception e){return new JSONObject();}
    }
    void reloadAlerts(){
        io.execute(()->{JSONObject next=readAlerts();runOnUiThread(()->{
            if(!isDestroyed() && signedIn && !next.toString().equals(alertState.toString())){alertState=next;screen.render(false);}
        });});
    }
    JSONObject rule(String id){JSONObject rules=alertState.optJSONObject("rules");return rules==null?null:rules.optJSONObject(id);}
    JSONObject gameRule(String id) {
        JSONObject rule = rule(id);
        return PriceAlerts.gameSpecific(rule) ? rule : null;
    }
    boolean needsWaitlist(JSONObject game){return !personal && !Market.contains(data.optJSONArray("wishlist"),Market.text(game,"id"));}
    void saveAlert(JSONObject game,String mode,JSONArray shops,long target){
        final JSONObject current=data;final boolean add=needsWaitlist(game);
        run(()->{
            JSONObject updated=current;
            if(add){api.changeList("wishlist",game.getString("id"),true);updated=updateList(current,game,"wishlist",true);}
            PriceAlerts alerts=new PriceAlerts(vault);alerts.configure(game,mode,shops,target);
            if(current.optLong("saved")+300>=now() && game.optJSONArray("offers")!=null)
                alerts.observe(new JSONObject().put(game.getString("id"),game.getJSONArray("offers")),now());
            AlertJob.schedule(this,true);
            return new JSONObject().put("snapshot",updated).put("alerts",alerts.document);
        },result->{
            data=result.optJSONObject("snapshot");alertState=result.optJSONObject("alerts");note="Price alert saved.";screen.render(false);
            if(android.os.Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},82);
        });
    }
    void disableAlert(String id){
        run(()->{PriceAlerts alerts=new PriceAlerts(vault);alerts.clearGameRule(id,data.optJSONArray("wishlist"));AlertJob.schedule(this,alerts.monitoring());return alerts.document;},
            result->{alertState=result;note="Price alert turned off.";screen.render(false);});
    }
    void saveUniversalAlert(String mode,JSONArray shops){
        final JSONArray wishlist=data.optJSONArray("wishlist");
        run(()->{PriceAlerts alerts=new PriceAlerts(vault);alerts.configureUniversal(wishlist,mode,shops);
            AlertJob.schedule(this,alerts.monitoring());return alerts.document;},result->{
            alertState=result;note="Wishlist-wide alert saved for "+(wishlist==null?0:wishlist.length())+" games.";screen.render(false);
            if(android.os.Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},82);
        });
    }
    void disableUniversalAlert(){
        run(()->{PriceAlerts alerts=new PriceAlerts(vault);alerts.disableUniversal();AlertJob.schedule(this,alerts.rules.length()>0);return alerts.document;},
            result->{alertState=result;note="Wishlist-wide alert turned off. Game-specific alerts remain active.";screen.render(false);});
    }
    void checkAlerts(){
        run(()->{PriceAlerts alerts=new PriceAlerts(vault);alerts.observe(api.monitoredPrices(alerts),now());AlertJob.deliver(this,alerts);return alerts.document;},
            result->{alertState=result;note="Selected stores checked.";screen.render(false);});
    }
    void readInbox(){
        run(()->{PriceAlerts alerts=new PriceAlerts(vault);alerts.markRead();return alerts.document;},
            result->{alertState=result;getSystemService(android.app.NotificationManager.class).cancelAll();screen.render(false);});
    }
    void openEvent(String id){
        run(()->{
            PriceAlerts alerts=new PriceAlerts(vault);
            for(JSONObject event:Market.objects(alerts.inbox))if(event.optString("id").equals(id)){
                event.put("read",true);alerts.save();return new JSONObject().put("alerts",alerts.document).put("game",event.getJSONObject("game"));
            }
            return new JSONObject().put("alerts",alerts.document);
        },result->{
            alertState=result.optJSONObject("alerts");getSystemService(android.app.NotificationManager.class).cancel(id,1);
            screen.go("alerts");JSONObject game=result.optJSONObject("game");if(game!=null)screen.openRelated(game);
        });
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==82 && results.length>0 && results[0]!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            notice("Alerts will appear in your RupeeRaid inbox. Enable Android notifications to see them on your phone’s lock screen.");
        else if(request==82 && signedIn)reloadAlerts();
    }
    private boolean handle(Intent intent){
        if(intent!=null && "in.rupeeraid.mobile.PRICE_ALERT".equals(intent.getAction()) && signedIn){
            String id=intent.getStringExtra("alertId");setIntent(new Intent());
            if(id!=null && !busy)openEvent(id);else {screen.go("alerts");reloadAlerts();}return true;
        }
        return callback(intent);
    }
    void permissionDialog(){
        permissionDialog("");
    }
    private void permissionDialog(String permissions){
        String message=permissions.contains("notes")?"Allow RupeeRaid to read and edit your ITAD game notes.":permissions.equals("ignored_write")?
            "Allow RupeeRaid to edit your ITAD Ignore List.":permissions.equals("coll_write")?"Allow RupeeRaid to edit your ITAD Collection.":"Renew access to your IsThereAnyDeal account.";
        new AlertDialog.Builder(this).setTitle("Reconnect IsThereAnyDeal").setMessage(message+" Choose the same account in your browser.")
            .setNegativeButton("Cancel",null).setPositiveButton("Reconnect",(d,i)->signIn(permissions)).show();
    }
    void clientDialog(){
        input("Public OAuth client ID",clientId,false,value->{
            clientId=value.trim();getPreferences(0).edit().putString("clientId",clientId).apply();
            api=new MobileApi(vault,clientId,MobileApi::send);vault.remove("pending");
            notice("Register "+MobileApi.REDIRECT+" as the redirect URI on IsThereAnyDeal.");
        });
    }
    private void input(String title,String initial,boolean secret,Consumer<String> action){
        EditText e=new EditText(this);e.setSingleLine();
        if(secret){e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);}
        e.setText(initial);
        LinearLayout inset=new LinearLayout(this);int side=Math.round(24*getResources().getDisplayMetrics().density);
        inset.setPadding(side,0,side,0);inset.addView(e,new LinearLayout.LayoutParams(-1,-2));
        AlertDialog.Builder builder=new AlertDialog.Builder(this).setTitle(title).setView(inset).setNegativeButton("Cancel",null)
                .setPositiveButton("Save",(d,w)->action.accept(e.getText().toString()));
        if(secret){
            builder.setMessage("Enter your own ITAD API key. RupeeRaid encrypts it on this phone and sends it directly to IsThereAnyDeal for price and catalog requests. You can remove it in Settings.");
            e.setSaveEnabled(false);
            e.setImeOptions(e.getImeOptions() | android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        }
        AlertDialog dialog=builder.create();
        if(secret)dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        dialog.show();
    }
    private static String b64(byte[] bytes){return Base64.encodeToString(bytes,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);}
    private static String random(){byte[] b=new byte[32];new SecureRandom().nextBytes(b);return b64(b);}
    void signIn(){
        signIn("");
    }
    private void signIn(String extra){
        if(clientId.isEmpty()){clientDialog();return;}
        try{
            String state=random(),verifier=random();
            String challenge=b64(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            String scope=api.requestedScopes(extra);
            vault.write("pending",new JSONObject().put("state",state).put("verifier",verifier).put("time",now()).put("scope",scope).toString());
            open(Uri.parse("https://isthereanydeal.com/oauth/authorize/").buildUpon()
                .appendQueryParameter("response_type","code").appendQueryParameter("client_id",clientId)
                .appendQueryParameter("redirect_uri",MobileApi.REDIRECT).appendQueryParameter("scope",scope)
                .appendQueryParameter("state",state).appendQueryParameter("code_challenge",challenge)
                .appendQueryParameter("code_challenge_method","S256").build());
        }catch(Exception e){notice("Could not start secure sign-in on this phone.");}
    }
    private boolean callback(Intent intent){
        Uri uri=intent==null?null:intent.getData();
        if(uri==null || !"in.rupeeraid.mobile".equals(uri.getScheme()) || !"signin".equals(uri.getHost()))return false;
        if(busy){deferred=intent;return true;}
        setIntent(new Intent());
        try{
            JSONObject pending=new JSONObject(vault.read("pending"));
            if(!pending.getString("state").equals(uri.getQueryParameter("state")) || now()-pending.getLong("time")>900)throw new IllegalStateException();
            vault.remove("pending");
            String code=uri.getQueryParameter("code");
            if(code==null || code.isEmpty()){notice("Sign-in was cancelled.");return true;}
            boolean reconnect=signedIn;
            run(()->{api.login(code,pending.getString("verifier"),pending.optString("scope",MobileApi.OLD_SCOPE));return new JSONObject();},result->{
                signedIn=true;
                if(reconnect){notice("IsThereAnyDeal reconnected. You can edit your lists.");screen.render(false);}
                else{screen.go("home");refresh();}
            });
        }catch(Exception e){notice("This sign-in link has expired. Start sign-in again.");}
        return true;
    }
    private void clearPrivateFiles(){
        for(String file:new String[]{"oauth","snapshot","pending","personalKey","lists","quotes","waitPrices","collectionPrices","searchCache","priceAlerts","gameNotes","nextSession"})vault.remove(file);
        vault.clearRegionalData();getPreferences(0).edit().remove("nickname").apply();AlertJob.schedule(this,false);
    }
    private void resetDisplay(boolean connected){
        getSystemService(android.app.NotificationManager.class).cancelAll();signedIn=connected;personal=false;
        lastAutoAttempt=0;data=new JSONObject();alertState=new JSONObject();screen.reset();screen.render(false);
    }
    private void switchSessionDialog(){
        new AlertDialog.Builder(this).setTitle("Start a fresh account session?")
            .setMessage("ITAD could not confirm this is the same account. Starting fresh clears the alerts, inbox and personal key saved on this phone. Your lists will load from the account you just authorized.")
            .setNegativeButton("Keep current session",(d,i)->vault.remove("nextSession"))
            .setPositiveButton("Start fresh",(d,i)->run(()->{
                JSONObject staged=new JSONObject(vault.read("nextSession"));
                if(staged.getLong("time")+600<now())throw new MobileApi.Failure(400,0,"This authorization has expired. Reconnect again.");
                String token=staged.getJSONObject("token").toString();clearPrivateFiles();vault.write("oauth",token);return new JSONObject();
            },done->{resetDisplay(true);refresh();})).show();
    }
    void signOut(){
        if(busy)return;
        run(()->{clearPrivateFiles();return new JSONObject();},ignored->{resetDisplay(false);notice("Signed out on this phone.");});
    }
}

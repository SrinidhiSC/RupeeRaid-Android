package in.rupeeraid.mobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MarketScreenTest {
    private Vault vault;
    private ActivityScenario<MainActivity> scenario;
    private File artifacts;
    private boolean seeded,livePreview;
    private JSONObject featured;

    @Before public void setup() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        vault=new Vault(context);
        Assume.assumeTrue("Use an isolated test device.",vault.read("oauth").isEmpty());
        artifacts=context.getExternalFilesDir(null);
        File sample=new File(artifacts,"public-catalog.json");
        livePreview=sample.isFile();
        JSONObject snapshot;
        if(livePreview){
            snapshot=new JSONObject(new String(Files.readAllBytes(sample.toPath()),StandardCharsets.UTF_8));
            JSONObject byGame=snapshot.getJSONObject("rawQuotes");
            JSONObject history=snapshot.optJSONObject("rawGameLows");
            for(String section:new String[]{"wishlist","deals","lows","trending","library"}){
                for(JSONObject g:Market.objects(snapshot.optJSONArray(section))){
                    JSONArray offers=new JSONArray(),raw=byGame.optJSONArray(g.getString("id"));
                    if(raw!=null){
                        for(JSONObject deal:Market.objects(raw)){
                            JSONObject q=MobileApi.offer(deal,history==null?null:history.optJSONObject(g.optString("id")));if(q!=null)offers.put(q);
                        }
                    }else{
                        JSONObject q=MobileApi.offer(g.optJSONObject("deal"));if(q!=null)offers.put(q);
                    }
                    g.put("offers",MobileApi.consistentLows(offers));
                }
            }
            snapshot.remove("rawQuotes");snapshot.remove("rawGameLows");
        }
        else {
            JSONObject quote=new JSONObject().put("paise",10000).put("regular",20000).put("cut",50).put("shop","Test shop")
                .put("url","https://itad.link/example/").put("updated","2026-01-01T12:00:00Z").put("flag","H")
                .put("platforms",new JSONArray().put(new JSONObject().put("name","Windows")));
            JSONArray games=new JSONArray();
            for(int i=0;i<36;i++)games.put(new JSONObject().put("id","00000000-0000-4000-a000-"+String.format(java.util.Locale.ROOT,"%012d",i))
                .put("title",i==0?"Far Cry 3":i==1?"Red Dead Redemption":"Fixture "+i).put("offers",new JSONArray().put(quote)));
            snapshot=new JSONObject();
            for(String section:new String[]{"wishlist","deals","lows","trending","library"})snapshot.put(section,games);
        }
        snapshot.put("name",JSONObject.NULL).put("saved",System.currentTimeMillis()/1000).put("schema",2);
        featured=snapshot.getJSONArray("wishlist").getJSONObject(0);
        File details=new File(artifacts,"public-detail.json");
        if(details.isFile()){
            JSONObject pages=new JSONObject(new String(Files.readAllBytes(details.toPath()),StandardCharsets.UTF_8));
            for(java.util.Iterator<String> ids=pages.keys();ids.hasNext();){
                String id=ids.next();JSONObject tabs=pages.getJSONObject(id);
                for(java.util.Iterator<String> keys=tabs.keys();keys.hasNext();){
                    String k=keys.next();JSONObject p=tabs.getJSONObject(k).put("saved",System.currentTimeMillis()/1000);
                    vault.write("guide-"+id+"-"+k,p.toString());
                }
            }
        }else{
            vault.write("guide-"+featured.getString("id")+"-about",new JSONObject().put("saved",System.currentTimeMillis()/1000)
                .put("data",new JSONObject().put("title","Far Cry 3").put("developers",new JSONArray().put(new JSONObject().put("name","Fixture developer")))).toString());
            for(JSONObject g:Market.objects(snapshot.optJSONArray("wishlist")))if("Red Dead Redemption".equals(g.optString("title"))){
                for(String tab:new String[]{"subscriptions","bundles"})vault.write("guide-"+g.getString("id")+"-"+tab,
                    new JSONObject().put("saved",System.currentTimeMillis()/1000).put("data",new JSONArray()).toString());
            }
        }
        vault.write("oauth","{}");seeded=true;vault.write("snapshot",snapshot.toString());
        scenario=ActivityScenario.launch(MainActivity.class);
        awaitReady();
    }
    @After public void cleanup(){
        if(scenario!=null)scenario.close();
        if(seeded && !"yes".equals(InstrumentationRegistry.getArguments().getString("stageReleaseCatalog"))){
            for(String name:new String[]{"oauth","snapshot","priceAlerts","personalKey","quotes","quota"})vault.remove(name);
            vault.clearRegionalData();Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
            AlertJob.schedule(c,false);c.getSystemService(android.app.NotificationManager.class).cancelAll();
        }
    }
    private void awaitReady(){
        AtomicBoolean ready=new AtomicBoolean();
        for(int i=0;i<80 && !ready.get();i++){
            scenario.onActivity(activity->ready.set(activity.ready &&
                    findText(activity.getWindow().getDecorView(),"Opening your saved games…")==null));if(!ready.get())SystemClock.sleep(75);
        }
        assertTrue(ready.get());
    }
    private static int loadedArtwork(View view){
        int count=0;
        if(view instanceof ImageView){
            android.graphics.drawable.Drawable d=((ImageView)view).getDrawable();
            if(d instanceof android.graphics.drawable.TransitionDrawable || d instanceof android.graphics.drawable.BitmapDrawable)count++;
        }
        if(view instanceof ViewGroup){
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++)count+=loadedArtwork(group.getChildAt(i));
        }
        return count;
    }
    private void preview(String name,boolean awaitArt) throws Exception {
        if(!livePreview)return;
        if(awaitArt){
            AtomicBoolean loaded=new AtomicBoolean();
            for(int i=0;i<75 && !loaded.get();i++){
                int minimum=name.equals("red-dead-redemption") || name.equals("offers")?1:3;
                scenario.onActivity(a->loaded.set(loadedArtwork(a.getWindow().getDecorView())>=minimum));
                if(!loaded.get())SystemClock.sleep(200);
            }
            assertTrue("The public ITAD artwork should load on this device.",loaded.get());
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Bitmap capture=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        try(FileOutputStream stream=new FileOutputStream(new File(artifacts,name+".png"))){
            capture.compress(Bitmap.CompressFormat.PNG,100,stream);
        }finally{capture.recycle();}
    }
    @Test public void firstFeedShowsPlaceholdersUntilSyncSettles() throws Exception {
        scenario.onActivity(a->{
            a.data=new JSONObject();a.manualRefresh();
            assertTrue("First sync must start immediately",a.loadingFirstFeed);
            android.widget.ListView feed=findList(a.getWindow().getDecorView());
            assertNotNull(feed);
            assertEquals("Loading trending games",((View)feed.getItemAtPosition(1)).getContentDescription());
            assertEquals("Loading deals",((View)feed.getItemAtPosition(2)).getContentDescription());
        });
        AtomicBoolean settled=new AtomicBoolean();
        waitUntil(()->{scenario.onActivity(a->settled.set(!a.busy));return settled.get();});
        onView(withText("Refresh")).check(matches(isDisplayed()));
    }
    @Test public void storageReadFailureCanRetryWithTheOriginalProfileAndKey() throws Exception {
        Context ctx=InstrumentationRegistry.getInstrumentation().getTargetContext();
        scenario.onActivity(a->a.accountPrefs().edit().putString("nickname","Saved player").commit());
        scenario.close();
        vault.write("personalKey","fixture-key");
        File encrypted=new File(ctx.getNoBackupFilesDir(),"personalKey");
        byte[] original=Files.readAllBytes(encrypted.toPath());
        try{
            Files.write(encrypted.toPath(),new byte[]{42});
            scenario=ActivityScenario.launch(MainActivity.class);awaitReady();
            onView(withText("Unlock your saved account")).check(matches(isDisplayed()));
            assertArrayEquals(new byte[]{42},Files.readAllBytes(encrypted.toPath()));
            Files.write(encrypted.toPath(),original);
            onView(withText("Retry")).perform(click());awaitReady();
            scenario.onActivity(a->{assertTrue(a.signedIn);assertTrue(a.personal);});
            onView(withContentDescription("Account settings")).perform(click());
            onView(withText("Saved player")).check(matches(isDisplayed()));
        }finally{
            ctx.getSharedPreferences("MainActivity",0).edit().remove("nickname").commit();
        }
    }
    @Test public void firstRunResumesSetupAndAllowsSkippingPricingKey() throws Exception {
        scenario.onActivity(a->a.getPreferences(0).edit().remove("introStep").commit());
        scenario.close();
        vault.remove("oauth");
        scenario=ActivityScenario.launch(MainActivity.class);awaitReady();
        onView(withText("Get started")).perform(click());
        onView(withText("Sign in with IsThereAnyDeal")).check(matches(isDisplayed()));
        scenario.recreate();awaitReady();
        onView(withText("Sign in with IsThereAnyDeal")).check(matches(isDisplayed()));
        scenario.close();
        vault.write("oauth","{}");
        scenario=ActivityScenario.launch(MainActivity.class);awaitReady();
        onView(withText("Add personal API key")).check(matches(isDisplayed()));
        onView(withText("Continue without a key")).perform(click());
        onView(withContentDescription("Wishlist")).check(matches(isDisplayed()));
        scenario.recreate();awaitReady();
        onView(withContentDescription("Wishlist")).check(matches(isDisplayed()));
    }
    @Test public void gameDetailRatingsDrmAndCommunity() throws Exception {
        String id=featured.getString("id");
        JSONObject about=new JSONObject().put("title",featured.getString("title"))
                .put("reviews",new JSONArray().put(new JSONObject().put("source","Steam").put("score",82)))
                .put("stats",new JSONObject().put("waitlisted",1517).put("collected",22349));
        JSONObject editorial=new JSONObject().put("summary",new JSONObject().put("text","A game summary."))
                .put("hltb",new JSONObject().put("main",14014));
        JSONObject sections=new JSONObject().put("about",about).put("editorial",editorial).put("stats",new JSONObject());
        for(String section:new String[]{"about","editorial","stats"}){
            vault.write("guide-"+id+"-"+section,new JSONObject().put("saved",MainActivity.now())
                    .put("generation","regional-offers-v2:"+vault.read("cacheGeneration")).put("data",sections.get(section)).toString());
        }
        scenario.onActivity(activity->activity.personal=true);
        onView(withContentDescription("Wishlist")).perform(click());
        onView(withText(featured.getString("title"))).perform(click());
        onView(withContentDescription("Steam 82% positive")).check(matches(isDisplayed()));
        onView(withContentDescription("Steam 82% positive")).check((badge,error)->{
            if(error!=null)throw error;
            View title=findText(badge.getRootView(),featured.optString("title"));
            View card=(View)title.getParent().getParent();
            int[] corner=new int[2],origin=new int[2];
            badge.getLocationOnScreen(corner);card.getLocationOnScreen(origin);
            assertEquals(origin[0]+card.getWidth()-card.getPaddingRight(),corner[0]+badge.getWidth(),1);
            assertEquals(origin[1]+card.getHeight()-card.getPaddingBottom(),corner[1]+badge.getHeight(),1);
        });
        onView(withContentDescription("Filter by DRM, Any")).perform(scrollTo(),click());
        onView(withText("DRM Free")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click());
        onView(withText("Apply")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click());
        onView(withText("No current offers match the selected DRM.")).perform(scrollTo());
        onView(withContentDescription("About tab")).perform(scrollTo(),click());
        onView(withText("A game summary.")).perform(scrollTo());
        onView(withText("4 h")).perform(scrollTo()).check(matches(isDisplayed()));
        onView(withContentDescription("Community tab")).perform(scrollTo(),click());
        onView(withText("Waitlisted")).perform(scrollTo()).check(matches(isDisplayed()));
        onView(withText("Collected")).check(matches(isDisplayed()));
    }
    @Test public void homeWishlistSearchPlatformAndAccount() throws Exception {
        onView(withText("Trending games")).check(matches(isDisplayed()));preview("discover",true);
        onView(withContentDescription("See all trending games")).perform(click());
        onView(withContentDescription("Sort games")).perform(click());
        onView(withText("Price")).perform(click());
        onView(withContentDescription("Filter Xbox")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist());
        onView(withContentDescription("Wishlist")).perform(click());
        onView(withText("Far Cry 3")).check(matches(isDisplayed()));preview("wishlist",true);
        onView(withContentDescription("Alert off for Far Cry 3")).check(matches(isDisplayed()));
        JSONObject offer=Market.best(featured,Market.ALL);
        if(offer!=null && offer.has("regular"))scenario.onActivity(activity->assertNotNull(findText(activity.getWindow().getDecorView(),"Original "+MarketWidgets.money(offer.optLong("regular")))));
        onView(withText("Far Cry 3")).perform(click());
        onView(withText("Price summary")).check(matches(isDisplayed()));
        preview("offers",true);pressBack();
        onView(withContentDescription("Search games")).perform(click());
        onView(isAssignableFrom(android.widget.EditText.class)).perform(replaceText("Far Cry 3"),closeSoftKeyboard());
        onView(allOf(withText("Far Cry 3"),not(isAssignableFrom(android.widget.EditText.class)))).check(matches(isDisplayed())).perform(click());
        onView(withText("Price summary")).check(matches(isDisplayed()));
        pressBack();
        onView(isAssignableFrom(android.widget.EditText.class)).check(matches(withText("Far Cry 3")));
        pressBack();
        onView(withContentDescription("Account settings")).perform(click());
        onView(withText("IsThereAnyDeal member")).check(matches(isDisplayed()));preview("account",false);
        onView(withText("Add personal API key")).perform(scrollTo(),click());
        onView(withText("Cancel")).perform(click());
        onView(withText("View ignored games")).perform(scrollTo(),click());
        onView(withText("This list is empty.")).check(matches(isDisplayed()));
    }
    private void waitUntil(java.util.function.BooleanSupplier ready) throws Exception{
        for(int i=0;i<180;i++){if(ready.getAsBoolean())return;SystemClock.sleep(100);}
        fail("Expected state did not arrive");
    }
    @Test public void recycledHomepagePricesResetColorGlowAndBadge() throws Exception{
        java.util.List<JSONObject> games=new java.util.ArrayList<>();
        for(String field:new String[]{"allLow","yearLow","quarterLow","ordinary"}){
            JSONObject quote=new JSONObject().put("paise",10000).put("cut",50).put("shop","Steam").put(field,10000);
            games.add(new JSONObject().put("title",field).put("offers",new JSONArray().put(quote)));
        }
        scenario.onActivity(a->{
            MarketWidgets widgets=new MarketWidgets(a);DealFeed feed=new DealFeed(widgets,Market.ALL,g->{});
            feed.addGames(games,games.size());View recycled=null;
            for(int i=0;i<games.size();i++){
                recycled=feed.getView(i,recycled,new android.widget.FrameLayout(a));
                android.widget.TextView price=findText(recycled,MarketWidgets.money(10000));
                Market.LowPeriod tier=Market.LowPeriod.values()[i];
                assertNotNull(price);assertEquals(MarketWidgets.lowColor(tier),price.getCurrentTextColor());
                if(tier!=Market.LowPeriod.NONE)assertNotNull(findText(recycled,tier.badge));
                else{assertEquals(0f,price.getShadowRadius(),0f);assertNull(findText(recycled,"3M"));}
            }
        });
    }
    private static android.widget.TextView findText(View v,String text){
        if(v.getVisibility()!=View.VISIBLE)return null;
        if(v instanceof android.widget.TextView && text.contentEquals(((android.widget.TextView)v).getText()))return (android.widget.TextView)v;
        if(v instanceof ViewGroup){ViewGroup group=(ViewGroup)v;for(int i=0;i<group.getChildCount();i++){
            android.widget.TextView found=findText(group.getChildAt(i),text);if(found!=null)return found;
        }}return null;
    }
    @Test public void redDeadRedemptionShowsRealEmptySectionsAndReturnsToSearch() throws Exception{
        onView(withContentDescription("Search games")).perform(click());
        onView(isAssignableFrom(android.widget.EditText.class)).perform(replaceText("Red Dead Redemption"),closeSoftKeyboard());
        onView(allOf(withText("Red Dead Redemption"),not(isAssignableFrom(android.widget.EditText.class)))).perform(click());
        onView(withContentDescription("Notify for Red Dead Redemption")).check(matches(isDisplayed()));
        preview("red-dead-redemption",true);
        onView(withContentDescription("Offers tab")).perform(scrollTo(),click());
        onView(withText("ITAD lists no bundles containing this game.")).perform(scrollTo()).check(matches(isDisplayed()));
        onView(withContentDescription("Subscriptions tab")).perform(scrollTo(),click());
        String empty="No regional subscription availability reported for this game.";
        waitUntil(()->{
            AtomicBoolean present=new AtomicBoolean();
            scenario.onActivity(activity->present.set(findText(activity.getWindow().getDecorView(),empty)!=null));
            return present.get();
        });
        onView(withText(empty)).perform(scrollTo()).check(matches(isDisplayed()));
        pressBack();onView(isAssignableFrom(android.widget.EditText.class)).check(matches(withText("Red Dead Redemption")));
    }
    @Test public void perStoreAlertDetailTabsAndBackgroundDelivery() throws Exception{
        if(android.os.Build.VERSION.SDK_INT>=33)androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(
                "in.rupeeraid.mobile","android.permission.POST_NOTIFICATIONS");
        onView(withContentDescription("Wishlist")).perform(click());
        onView(withContentDescription("Alert off for Far Cry 3")).perform(click());
        onView(withText("Any price drop")).perform(click());
        onView(withText("Green Man Gaming")).perform(click());onView(withText("Microsoft Store")).perform(click());
        onView(withText("Steam")).check(matches(isChecked()));onView(withText("Epic Games Store")).check(matches(isChecked()));
        preview("choose-stores",false);onView(withText("Save alert")).perform(click());
        waitUntil(()->vault.read("priceAlerts").contains("\"drop\""));
        onView(withContentDescription("Alert on for Far Cry 3")).check(matches(isDisplayed()));
        onView(withText("Far Cry 3")).perform(click());
        String id=featured.getString("id");
        JSONObject rule=new PriceAlerts(vault).rules.getJSONObject(id);
        assertEquals("[61,16]",rule.getJSONArray("shops").toString());
        onView(withContentDescription("About tab")).perform(scrollTo(),click());
        waitUntil(()->!vault.read("guide-"+id+"-about").isEmpty());
        onView(withText("About Far Cry 3")).perform(scrollTo()).check(matches(isDisplayed()));
        onView(withContentDescription("Offers tab")).perform(scrollTo(),click());
        if(new File(artifacts,"public-detail.json").isFile()){
            onView(withText("DLCs")).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withText("Packages")).perform(scrollTo()).check(matches(isDisplayed()));
        }
        onView(withContentDescription("Series tab")).perform(scrollTo(),click());
        onView(withSubstring("Games matching")).check(matches(isDisplayed()));
        if(new File(artifacts,"public-detail.json").isFile()){
            onView(withContentDescription("Offers tab")).perform(scrollTo(),click());
            for(String label:new String[]{"All-time low","1 year low","3 months low","Prices"})
                onView(withText(label)).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withContentDescription("Price history tab")).perform(scrollTo(),click());
            onView(withText("Store history · past three months")).perform(scrollTo()).check(matches(isDisplayed()));preview("price-history",false);
            if(!vault.read("guide-"+id+"-historyYear").isEmpty()){
                onView(withText("Period  ·  Past three months")).perform(scrollTo(),click());onView(withText("Past year")).perform(click());
                onView(withText("Store history · past year")).perform(scrollTo()).check(matches(isDisplayed()));
                onView(withText("Period  ·  Past year")).perform(scrollTo(),click());onView(withText("All available history")).perform(click());
                onView(withText("Store history · all available dates")).perform(scrollTo()).check(matches(isDisplayed()));
            }
            onView(withContentDescription("Offers tab")).perform(scrollTo(),click());
            onView(withText("Ubisoft Build Your Own Bundle")).perform(scrollTo()).check(matches(isDisplayed()));
        }
        scenario.close();scenario=null;
        // Exercise the real JobService after closing the activity with deterministic, fresh providers' cache fixtures.
        long t=System.currentTimeMillis()/1000;String[] stores={"Steam","Epic Games Store","Green Man Gaming"};int[] shopIds={61,16,36};
        JSONArray old=new JSONArray(),next=new JSONArray();
        for(int i=0;i<stores.length;i++){
            JSONObject q=new JSONObject().put("shop",stores[i]).put("shopId",shopIds[i]).put("paise",50000)
                .put("reportedAt",t-5).put("country","IN").put("currency","INR").put("url","https://itad.link/example/");
            old.put(q);next.put(new JSONObject(q.toString()).put("paise",i==0?50000:10000).put("reportedAt",t));
        }
        PriceAlerts alerts=new PriceAlerts(vault);alerts.rules.getJSONObject(id).put("last",new JSONObject());
        alerts.observe(new JSONObject().put(id,old),t);
        vault.write("quotes",new JSONObject().put(id,new JSONObject().put("saved",t).put("items",next)).toString());
        vault.write("personalKey","isolated-test-fixture");
        try(android.os.ParcelFileDescriptor fd=InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand("cmd jobscheduler run -f in.rupeeraid.mobile 8141");
            java.io.InputStream input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();
                byte[] buffer=new byte[256];int n;
                while((n=input.read(buffer))>=0)output.write(buffer,0,n);
                String status=output.toString("UTF-8");assertTrue(status,status.contains("Running job"));}
        waitUntil(()->{try{
            // AtomicFile reads must run after the worker finishes its write, as they do in the app.
            return AlertJob.IO.submit(()->new PriceAlerts(vault).unread()).get()==1;
        }catch(Exception e){throw new AssertionError(e);}});
        PriceAlerts finished=new PriceAlerts(vault);assertEquals("Epic Games Store",finished.inbox.getJSONObject(0).getString("shop"));
        android.app.NotificationManager nm=InstrumentationRegistry.getInstrumentation().getTargetContext().getSystemService(android.app.NotificationManager.class);
        waitUntil(()->nm.getActiveNotifications().length==1);
        vault.remove("personalKey");
        scenario=ActivityScenario.launch(MainActivity.class);awaitReady();
        onView(withContentDescription("Price alert inbox")).perform(click());
        onView(withText("Mark all as read")).perform(scrollTo(),click());waitUntil(()->{try{return new PriceAlerts(vault).unread()==0;}catch(Exception e){throw new AssertionError(e);}});
        assertEquals(1,new PriceAlerts(vault).inbox.length());
    }
    @Test public void typingFetchesLatestCatalogQueryWithoutSubmit() throws Exception {
        java.util.List<String> calls=java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.Map<String,String> values=new java.util.HashMap<>();values.put("personalKey","test");
        MobileApi.Storage store=new MobileApi.Storage(){
            public String read(String k){return values.getOrDefault(k,"");}
            public void write(String k,String v){values.put(k,v);}
            public void remove(String k){values.remove(k);}
        };
        MobileApi api=new MobileApi(store,"",(url,method,header,key,format,body)->{
            if(url.contains("/search/")){
                calls.add(url);return new JSONArray().put(new JSONObject().put("id","pc-title").put("title","Portal Test Edition"));
            }
            if(url.contains("/prices/"))return new JSONArray();
            return new JSONObject().put("pc-title",new JSONArray().put("app/123"));
        });
        scenario.onActivity(activity->{try{
            java.lang.reflect.Field field=MainActivity.class.getDeclaredField("api");field.setAccessible(true);field.set(activity,api);
            activity.personal=true;
        }catch(Exception e){throw new AssertionError(e);}});
        onView(withContentDescription("Search games")).perform(click());
        scenario.onActivity(activity->{
            android.widget.EditText input=activity.getCurrentFocus() instanceof android.widget.EditText?(android.widget.EditText)activity.getCurrentFocus():null;
            assertNotNull(input);
            for(String q:new String[]{"po","por","port","portal"})input.setText(q);
        });
        awaitText("Portal Test Edition");
        assertEquals(1,calls.size());assertTrue(calls.get(0).endsWith("title=portal"));
        onView(withContentDescription("Clear search")).perform(click());
        onView(isAssignableFrom(android.widget.EditText.class)).perform(replaceText("portal"));
        awaitText("Portal Test Edition");assertEquals(1,calls.size());
    }
    private void awaitText(String label) throws Exception{
        waitUntil(()->{
            AtomicBoolean shown=new AtomicBoolean();
            scenario.onActivity(a->shown.set(findText(a.getWindow().getDecorView(),label)!=null));
            return shown.get();
        });
    }
    @Test public void profileAppearanceAndRefreshSettingsPersist() throws Exception{
        Context ctx=InstrumentationRegistry.getInstrumentation().getTargetContext();
        try{
            onView(withContentDescription("Account settings")).perform(click());
            onView(withText("Edit profile")).perform(click());
            onView(isAssignableFrom(android.widget.EditText.class)).perform(replaceText("Raid Tester"),closeSoftKeyboard());
            onView(withText("Save")).perform(click());awaitText("Raid Tester");
            onView(withContentDescription("Theme, Obsidian Emerald")).perform(scrollTo(),click());
            onView(withText("Cobalt Frost")).perform(click());awaitReady();awaitText("Raid Tester");
            onView(withContentDescription("Theme, Cobalt Frost")).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withContentDescription("App icon, Bolt Rupee")).perform(scrollTo(),click());
            onView(withText("Neon Mono")).perform(click());
            onView(withContentDescription("App icon, Neon Mono")).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withContentDescription("Startup tab, Discover")).perform(scrollTo(),click());
            onView(withText("Wishlist")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click());
            onView(withContentDescription("Compact deal rows")).perform(scrollTo(),click());
            onView(withContentDescription("AMOLED black")).perform(scrollTo(),click());awaitReady();
            scenario.onActivity(a->assertEquals(android.graphics.Color.BLACK,MarketWidgets.BG));
            onView(withContentDescription("Reduce motion")).perform(scrollTo(),click());
            onView(withContentDescription("High refresh rate")).perform(scrollTo(),click());
            scenario.recreate();awaitReady();awaitText("Raid Tester");
            onView(withContentDescription("Reduce motion")).perform(scrollTo()).check(matches(isChecked()));
            onView(withContentDescription("High refresh rate")).perform(scrollTo()).check(matches(not(isChecked())));
            scenario.close();scenario=ActivityScenario.launch(MainActivity.class);awaitReady();
            onView(withContentDescription("Alert off for Far Cry 3")).check(matches(isDisplayed()));
        }finally{
            ctx.getSharedPreferences("appearance",0).edit().clear().commit();
            ctx.getSharedPreferences("MainActivity",0).edit().remove("nickname").commit();
        }
    }
    @Test public void menuLibraryAndWishlistWideAlert() throws Exception{
        if(android.os.Build.VERSION.SDK_INT>=33)InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .grantRuntimePermission("in.rupeeraid.mobile","android.permission.POST_NOTIFICATIONS");
        onView(withContentDescription("Open navigation menu")).perform(click());
        onView(withText("Refresh prices")).check(matches(isDisplayed()));preview("menu",false);pressBack();
        onView(withContentDescription("Library")).perform(click());
        onView(withContentDescription("Switch to list")).check(matches(isDisplayed()));preview("library",false);
        onView(withContentDescription("Switch to list")).perform(click());onView(withContentDescription("Switch to grid")).perform(click());
        onView(withContentDescription("Wishlist")).perform(click());
        onView(withText("Set one alert for all wishlist games")).perform(click());
        onView(withText("One-year low or better")).perform(click());
        onView(withText("Apply to wishlist")).perform(click());
        waitUntil(()->vault.read("priceAlerts").contains("universalExclusions"));
        onView(withContentDescription("Alert off for Far Cry 3")).perform(click());
        onView(withText("Any price drop")).perform(click());
        onView(withText("Save alert")).perform(click());
        onView(withContentDescription("Alert on for Far Cry 3")).perform(click());
        onView(withText("Turn off")).perform(click());
        waitUntil(()->{try{return "universal".equals(new PriceAlerts(vault).rules.getJSONObject(featured.getString("id")).optString("source"));}catch(Exception e){return false;}});
        onView(withContentDescription("Alert off for Far Cry 3")).check(matches(isDisplayed()));
    }
    @Test public void measureScrollingDealRows() throws Exception{
        onView(withContentDescription("Deals")).perform(click());
        java.util.List<Long> times=java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        android.os.HandlerThread thread=new android.os.HandlerThread("Frame metrics");thread.start();
        android.view.Window.OnFrameMetricsAvailableListener listener=(window,metrics,dropped)->{
            if(metrics.getMetric(android.view.FrameMetrics.FIRST_DRAW_FRAME)==0)
                times.add(metrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION));
        };
        final float[] hz={0};
        try{
            scenario.onActivity(a->{
                hz[0]=a.getWindowManager().getDefaultDisplay().getRefreshRate();
                a.getWindow().addOnFrameMetricsAvailableListener(listener,new android.os.Handler(thread.getLooper()));
                android.widget.ListView list=findList(a.getWindow().getDecorView());assertNotNull(list);
                list.smoothScrollBy(list.getHeight()*2,1100);
            });
            SystemClock.sleep(1300);
            scenario.onActivity(a->{android.widget.ListView list=findList(a.getWindow().getDecorView());list.smoothScrollBy(-list.getHeight()*2,1100);});
            SystemClock.sleep(1300);
            scenario.onActivity(a->a.getWindow().removeOnFrameMetricsAvailableListener(listener));
            java.util.List<Long> sample=new java.util.ArrayList<>(times);java.util.Collections.sort(sample);assertTrue(sample.size()>5);
            String report=String.format(java.util.Locale.ROOT,"%.0f Hz, %d frames, median %.2f ms, p95 %.2f ms",hz[0],sample.size(),
                    sample.get(sample.size()/2)/1e6,sample.get((int)Math.ceil(sample.size()*.95)-1)/1e6);
            Files.write(new File(artifacts,"scroll-1.5.txt").toPath(),report.getBytes(StandardCharsets.UTF_8));
        }finally{thread.quitSafely();}
    }
    private static android.widget.ListView findList(View view){
        if(view instanceof android.widget.ListView)return (android.widget.ListView)view;
        if(view instanceof ViewGroup){
            ViewGroup g=(ViewGroup)view;
            for(int i=0;i<g.getChildCount();i++){android.widget.ListView list=findList(g.getChildAt(i));if(list!=null)return list;}
        }
        return null;
    }
}

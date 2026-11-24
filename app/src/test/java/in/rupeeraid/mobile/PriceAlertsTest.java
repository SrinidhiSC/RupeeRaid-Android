package in.rupeeraid.mobile;

import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class PriceAlertsTest {
    @Test public void homeColorsRequireVerifiedPeriodRecordsAndPreferLongestMatch() throws Exception{
        JSONObject raw=MobileApiTest.offer("Steam","INR");
        JSONObject price=raw.getJSONObject("price").put("amountInt",5000);
        JSONObject low=new JSONObject(price.toString());
        assertEquals(Market.LowPeriod.NONE,Market.lowPeriod(MobileApi.offer(raw)));
        raw.put("historyLow_3m",low);
        assertEquals(Market.LowPeriod.QUARTER,Market.lowPeriod(MobileApi.offer(raw)));
        raw.put("historyLow_1y",low);
        assertEquals(Market.LowPeriod.YEAR,Market.lowPeriod(MobileApi.offer(raw)));
        raw.put("historyLow",low);
        assertEquals(Market.LowPeriod.ALL_TIME,Market.lowPeriod(MobileApi.offer(raw)));
        JSONObject periods=new JSONObject().put("all",low).put("y1",low).put("m3",low);
        JSONObject bulk=MobileApi.offer(MobileApiTest.offer("Steam","INR").put("price",price),low,periods);
        assertEquals(Market.LowPeriod.ALL_TIME,Market.lowPeriod(bulk));
        assertEquals(Market.LowPeriod.NONE,Market.lowPeriod(bulk.put("currentFloor",4900)));
        low.put("currency","USD");assertEquals(Market.LowPeriod.NONE,Market.lowPeriod(MobileApi.offer(raw)));
    }
    private final MobileApiTest.Memory memory=new MobileApiTest.Memory();
    private final long now=System.currentTimeMillis()/1000;
    @Test public void wishlistPolicyPreservesOverridesAndMuteAcrossSync() throws Exception{
        PriceAlerts alerts=new PriceAlerts(memory);
        JSONArray games=new JSONArray().put(MobileApiTest.game()),steam=new JSONArray("[61]");
        alerts.configureUniversal(games,PriceAlerts.YEAR_LOW,steam);
        alerts.observe(prices(quote(61,120,now-4,"")),now);
        JSONObject quarter=quote(61,100,now-3,"").put("quarterLow",100);
        assertEquals(0,alerts.observe(prices(quarter),now).length());
        assertEquals(1,alerts.observe(prices(quote(61,90,now-2,"").put("yearLow",90)),now).length());
        alerts.disable(MobileApiTest.ID);alerts=new PriceAlerts(memory);alerts.reconcileUniversal(games);
        assertEquals(0,alerts.rules.length());
        alerts.configure(MobileApiTest.game(),PriceAlerts.QUARTER_LOW,steam,-1);
        alerts.observe(prices(quote(61,120,now-4,"")),now);
        assertEquals(1,alerts.observe(prices(quarter),now).length());
        alerts.disableUniversal();assertEquals(1,alerts.rules.length());
    }
    @Test public void newWishlistGameInheritsRuleAndOnlyAlertsAfterBaseline() throws Exception {
        PriceAlerts alerts=new PriceAlerts(memory);
        alerts.configureUniversal(new JSONArray(),PriceAlerts.DROP,new JSONArray("[61]"));
        alerts.reconcileUniversal(new JSONArray().put(MobileApiTest.game()));
        assertFalse(PriceAlerts.gameSpecific(alerts.rules.getJSONObject(MobileApiTest.ID)));
        assertEquals(0,alerts.observe(prices(quote(61,200,now-2,"")),now).length());
        assertEquals(1,alerts.observe(prices(quote(61,100,now-1,"")),now).length());
    }
    @Test public void oldInboxEntriesExpireAcrossRestart() throws Exception {
        PriceAlerts alerts=new PriceAlerts(memory);
        alerts.inbox.put(new JSONObject().put("when",now-31L*86400));
        alerts.inbox.put(new JSONObject().put("when",now-29L*86400));
        alerts.save();
        assertEquals(1,new PriceAlerts(memory).unread());
        assertEquals(1,new JSONObject(memory.read("priceAlerts")).getJSONArray("inbox").length());
    }
    @Test public void bellsOnlyRepresentExplicitGameRules() throws Exception {
        PriceAlerts alerts=new PriceAlerts(memory);
        JSONObject game=MobileApiTest.game();String id=game.getString("id");
        JSONArray wishlist=new JSONArray().put(game),stores=new JSONArray().put(61);
        alerts.configureUniversal(wishlist,PriceAlerts.LOW,stores);
        assertFalse(PriceAlerts.gameSpecific(alerts.rules.getJSONObject(id)));
        alerts.configure(game,PriceAlerts.DROP,stores,-1);
        assertTrue(PriceAlerts.gameSpecific(alerts.rules.getJSONObject(id)));
        alerts.clearGameRule(id,wishlist);
        assertEquals("universal",alerts.rules.getJSONObject(id).getString("source"));
        alerts.configure(game,PriceAlerts.DROP,stores,-1);
        alerts.disableUniversal();
        alerts.observe(prices(quote(61,200,now-1,"")),now);
        assertEquals(1,alerts.observe(prices(quote(61,100,now,"")),now).length());
    }
    private JSONObject quote(int shop,long amount,long at,String flag) throws Exception {
        return new JSONObject().put("shopId",shop).put("paise",amount).put("reportedAt",at).put("flag",flag)
                .put("country","IN").put("currency","INR").put("cut",10).put("url","https://itad.link/example/");
    }
    private JSONObject prices(JSONObject... offers) throws Exception{
        return new JSONObject().put(MobileApiTest.ID,new JSONArray(Arrays.asList(offers)));
    }
    @Test public void dropsStayWithinEachSelectedStoreAndPersistAcrossRestart() throws Exception {
        PriceAlerts alerts=new PriceAlerts(memory);alerts.configure(MobileApiTest.game(),PriceAlerts.DROP,new JSONArray("[61,16]"),-1);
        assertEquals(0,alerts.observe(prices(quote(61,50000,now-30,""),quote(16,60000,now-30,"")),now).length());
        assertEquals(0,alerts.observe(prices(quote(36,100,now-10,"N"),quote(61,50000,now-20,"")),now).length());
        assertEquals(0,alerts.observe(prices(quote(16,100,now-20,"").put("currency","USD"),
            quote(61,10,now,"").put("country","US"),quote(16,10,now,"").put("expiry",now)),now).length());
        PriceAlerts reloaded=new PriceAlerts(memory);
        JSONObject eventPrices=prices(quote(16,59000,now-10,""),quote(16,61000,now-10,""),quote(36,0,now,"N"));
        JSONArray events=reloaded.observe(eventPrices,now);
        assertEquals(1,events.length());assertEquals("Epic Games Store",events.getJSONObject(0).getString("shop"));
        assertEquals(60000,events.getJSONObject(0).getLong("previous"));
        assertEquals(0,new PriceAlerts(memory).observe(eventPrices,now+30).length());
        assertEquals(0,reloaded.observe(prices(quote(16,30000,now-60,"")),now+60).length());
        assertEquals(1,reloaded.unread());reloaded.markRead();assertEquals(0,new PriceAlerts(memory).unread());
    }
    @Test public void lowRequiresProviderFlagAndBaselineAndTargetFiresOnlyOnCrossing() throws Exception{
        PriceAlerts alerts=new PriceAlerts(memory);JSONObject game=MobileApiTest.game();
        alerts.configure(game,PriceAlerts.LOW,new JSONArray("[61]"),-1);
        assertEquals(0,alerts.observe(prices(quote(61,100,1,"H")),now).length());
        assertEquals(0,alerts.observe(prices(quote(61,95,2,"S")),now).length());
        assertEquals(1,alerts.observe(prices(quote(61,90,3,"N")),now).length());
        assertEquals(0,alerts.observe(prices(quote(61,90,4,"H")),now).length());
        alerts.configure(game,PriceAlerts.TARGET,new JSONArray("[61]"),80);
        assertEquals(0,alerts.observe(prices(quote(61,90,5,"")),now).length());
        assertEquals(1,alerts.observe(prices(quote(61,80,6,"")),now).length());
        assertEquals(0,alerts.observe(prices(quote(61,70,7,"")),now).length());
        alerts.configure(game,PriceAlerts.TARGET,new JSONArray("[16]"),80);
        assertEquals(0,alerts.observe(prices(quote(16,20,8,""),quote(61,0,9,"N")),now).length());
        alerts.disable(MobileApiTest.ID);assertEquals(0,alerts.observe(prices(quote(16,0,9,"N")),now).length());
        assertEquals(2,new PriceAlerts(memory).inbox.length());
        assertThrows(IllegalArgumentException.class,()->alerts.configure(game,PriceAlerts.LOW,new JSONArray("[999]"),-1));
    }
    @Test public void gamePanelsUseDocumentedEndpointsAreCachedAndKeepFailuresLocal() throws Exception {
        List<String> calls=new ArrayList<>();memory.put("personalKey","device");
        String id=MobileApiTest.ID;JSONObject game=MobileApiTest.game().put("title","Far Cry 3");
        MobileApi api=new MobileApi(memory,"app",(url,method,header,key,content,body)->{
            calls.add(url);assertEquals("ITAD-API-Key",header);assertEquals("device",key);
            if(url.contains("/search/"))return new JSONArray().put(new JSONObject().put("id","a").put("type","dlc").put("title","Far Cry® 3 DLC"))
                .put(new JSONObject().put("id","b").put("type","dlc").put("title","Far Cry 6 DLC 3"))
                .put(new JSONObject().put("id","c").put("type","game").put("title","Far Cry 4"));
            if(url.contains("/prices/")){
                assertEquals("a",new JSONArray(body).getString(0));
                return new JSONArray().put(new JSONObject().put("id","a").put("deals",new JSONArray()));
            }
            if(url.contains("/info/"))throw new MobileApi.Failure(503,0,"ITAD unavailable");
            if(url.contains("/giveaways/")){assertFalse(url.contains("country="));return new JSONArray();}
            assertTrue(url.contains("country=IN"));
            if(url.contains("/history/") || url.contains("/bundles/")){assertTrue(url.contains("id="+id));return new JSONArray();}
            if(url.contains("/stats/"))return new JSONObject().put("count",18);
            assertEquals("POST",method);assertEquals(id,new JSONArray(body).getString(0));
            if(url.contains("/storelow/"))assertFalse(url.contains("shops="));
            return new JSONArray().put(new JSONObject().put("id","another").put("subs",new JSONArray("[1]")))
                .put(new JSONObject().put("id",id).put("lows",new JSONArray()).put("subs",new JSONArray()));
        });
        GameCatalog guide=new GameCatalog(api,memory);
        for(String section:Arrays.asList("packs","series","lows","history","historyYear","historyAll","record","stats","giveaways","subscriptions","bundles")){
            JSONObject response=guide.load(game,section);assertTrue(section,response.has("data"));
            if(section.equals("packs") || section.equals("series"))assertEquals(1,response.getJSONArray("data").length());
            guide.load(game,section);
        }
        assertEquals(12,calls.size());assertTrue(guide.load(game,"about").has("message"));
        memory.remove("personalKey");assertTrue(guide.load(game,"about").getBoolean("needsKey"));assertTrue(guide.load(game,"lows").has("data"));
    }
    @Test public void olderScopesAndBothListMutationsUseProviderContracts() throws Exception{
        JSONObject token=new JSONObject().put("expires",now+300).put("access_token","example");
        memory.put("oauth",token.toString());List<String> calls=new ArrayList<>();
        MobileApi api=new MobileApi(memory,"app",(url,method,h,key,fmt,body)->{
            assertEquals(MobileApiTest.ID,new JSONArray(body).getString(0));calls.add(method+" "+url);return new JSONObject();
        });
        assertEquals(412,assertThrows(MobileApi.Failure.class,()->api.changeList("ignored",MobileApiTest.ID,true)).status);
        memory.put("oauth",token.put("scope",MobileApi.SCOPE).toString());
        api.changeList("ignored",MobileApiTest.ID,true);api.changeList("library",MobileApiTest.ID,false);
        assertTrue(calls.get(0).endsWith("/ignored/games/v1"));assertTrue(calls.get(0).startsWith("PUT "));
        assertTrue(calls.get(1).endsWith("/collection/games/v1"));assertTrue(calls.get(1).startsWith("DELETE "));
    }
    @Test public void storeLowRuleHandlesFreeAndIgnoresARepeatedProviderEvent() throws Exception{
        JSONObject game=MobileApiTest.game();PriceAlerts alerts=new PriceAlerts(memory);
        alerts.configure(game,PriceAlerts.STORE_LOW,new JSONArray("[61]"),-1);
        alerts.observe(prices(quote(61,10000,now-10,"").put("storeLow",1000)),now);
        JSONArray events=alerts.observe(prices(quote(61,0,now-5,"S").put("storeLow",0)),now);
        assertEquals(0,events.getJSONObject(0).getLong("amount"));
        assertEquals(1000,events.getJSONObject(0).getLong("previousStoreLow"));
        alerts.observe(prices(quote(61,0,now-5,"")),now);
        assertEquals(0,alerts.observe(prices(quote(61,0,now-5,"S")),now).length());
        JSONObject raw=MobileApiTest.offer("Steam","INR").put("flag","N")
            .put("historyLow",new JSONObject().put("currency","INR").put("amountInt",500));
        assertTrue(Market.historical(MobileApi.offer(raw)));
        raw.getJSONObject("historyLow").put("currency","USD");assertFalse(Market.historical(MobileApi.offer(raw)));
    }
    @Test public void personalNotesAreScopedCachedAndUpdateOnlyTheChosenGame() throws Exception{
        JSONObject token=new JSONObject().put("expires",now+300).put("access_token","example").put("scope",MobileApi.OLD_SCOPE);
        memory.put("oauth",token.toString());List<String> verbs=new ArrayList<>();
        MobileApi api=new MobileApi(memory,"app",(url,method,h,key,fmt,body)->{
            assertTrue(url.endsWith("/user/notes/v1"));verbs.add(method);
            if(method.equals("GET"))return new JSONArray().put(new JSONObject().put("gid",MobileApiTest.ID).put("note","First"));
            if(method.equals("DELETE"))assertEquals(MobileApiTest.ID,new JSONArray(body).getString(0));
            if(method.equals("PUT"))assertEquals("Revised",new JSONArray(body).getJSONObject(0).getString("note"));
            return new JSONObject();
        });
        assertThrows(MobileApi.Consent.class,()->api.gameNote(MobileApiTest.ID));
        String permissions=api.requestedScopes("notes_read notes_write");assertFalse(permissions.contains("coll_write"));
        memory.put("oauth",token.put("scope",permissions).toString());
        assertEquals("First",api.gameNote(MobileApiTest.ID).getString("note"));api.gameNote(MobileApiTest.ID);
        api.writeNote(MobileApiTest.ID,"Revised");api.writeNote(MobileApiTest.ID,"");
        assertEquals(Arrays.asList("GET","PUT","DELETE"),verbs);
    }
    @Test public void sameAccountReauthorizationPreservesCachesAndSwitchRequiresConfirmation() throws Exception{
        java.util.function.Function<String,String> jwt=id->"e30."+Base64.getUrlEncoder().withoutPadding().encodeToString(("{\"sub\":\""+id+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8))+".sig";
        String[] subject={"one"};MobileApi api=new MobileApi(memory,"app",(url,m,h,k,f,body)->
            new JSONObject().put("token_type","Bearer").put("access_token",jwt.apply(subject[0])));
        api.login("one","v");memory.write("snapshot","saved");
        api.login("upgrade","v",api.requestedScopes("coll_write"));assertTrue(api.permits("coll_write"));
        String session=memory.read("oauth");subject[0]="two";
        assertEquals(409,assertThrows(MobileApi.Failure.class,()->api.login("another","v")).status);
        assertEquals(session,memory.read("oauth"));assertEquals("saved",memory.read("snapshot"));assertFalse(memory.read("nextSession").isEmpty());
    }
    @Test public void contradictedGameLowIsStillEligibleForItsOwnStoreRule() throws Exception{
        JSONObject cheapest=quote(48,151160,now,"N").put("storeLow",151160);
        JSONObject other=quote(36,151960,now,"N").put("storeLow",151960);
        JSONArray offers=MobileApi.consistentLows(new JSONArray().put(cheapest).put(other));
        assertTrue(Market.historical(offers.getJSONObject(0)));assertFalse(Market.historical(offers.getJSONObject(1)));
        assertEquals("S",other.getString("flag"));
        JSONObject raw=MobileApiTest.offer("GreenManGaming","INR").put("flag","N")
                .put("price",new JSONObject().put("amountInt",151960).put("currency","INR"))
                .put("regular",new JSONObject().put("amountInt",379900).put("currency","INR"));
        assertFalse(Market.historical(MobileApi.offer(raw,new JSONObject().put("amountInt",151160).put("currency","INR"))));
        PriceAlerts a=new PriceAlerts(memory);a.configure(MobileApiTest.game(),PriceAlerts.LOW,new JSONArray("[36]"),-1);
        a.observe(prices(quote(36,200000,now-2,"")),now);assertEquals(0,a.observe(prices(other),now).length());
        a.configure(MobileApiTest.game(),PriceAlerts.STORE_LOW,new JSONArray("[36]"),-1);
        a.observe(prices(quote(36,200000,now-2,"")),now);assertEquals(1,a.observe(prices(other),now).length());
    }
}

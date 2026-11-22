package in.rupeeraid.mobile;

import static org.junit.Assert.*;
import org.junit.Test;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.*;

public class MobileApiTest {
    static final String ID="018d937f-012f-73b8-ab2c-898516969e6a";
    static class Memory extends HashMap<String,String> implements MobileApi.Storage {
        public String read(String name){return getOrDefault(name,"");}
        public void write(String name,String value){put(name,value);}
        public void remove(String name){super.remove(name);}
    }
    static JSONObject game() throws Exception {return new JSONObject().put("id",ID).put("title","Contract test").put("slug","contract-test");}
    static JSONObject offer(String shop,String currency) throws Exception {
        return new JSONObject().put("shop",new JSONObject().put("name",shop))
            .put("price",new JSONObject().put("currency",currency).put("amountInt",0))
            .put("regular",new JSONObject().put("currency",currency).put("amountInt",15000))
            .put("url","https://itad.link/example/?affiliate=test").put("cut",100)
            .put("timestamp","2026-01-01T12:00:00+05:30").put("voucher","INDIA");
    }
    @Test public void regionUsesCurrencyReturnedByItad() throws Exception {
        Memory memory=new Memory();
        memory.write("oauth",new JSONObject().put("expires",Long.MAX_VALUE).put("access_token","test").toString());
        MobileApi api=new MobileApi(memory,"client",(url,method,header,credential,format,body)->{
            assertEquals("JP",new JSONObject(body).getString("country"));
            return new JSONObject().put("list",new JSONArray().put(game().put("deal",offer("Steam","JPY"))));
        });
        PricingRegion original=PricingRegion.current;
        try {
            PricingRegion.current=api.resolveRegion("JP");
            assertEquals("JPY",PricingRegion.current.currency);
            assertEquals("JP",MobileApi.offer(offer("Steam","JPY")).getString("country"));
            assertFalse(MobileApi.offer(offer("Steam","INR")).has("paise"));
        } finally { PricingRegion.current=original; }
    }
    @Test public void directOAuthCacheAndPersonalPricing() throws Exception {
        Memory memory=new Memory();List<String> calls=new ArrayList<>();
        MobileApi api=new MobileApi(memory,"public-id",(url,method,header,credential,format,body)->{
            calls.add(url);
            if(url.equals("https://isthereanydeal.com/oauth/token/")){
                assertTrue(body.contains("code_verifier="));assertFalse(body.contains("secret"));
                assertTrue(body.contains("redirect_uri=in.rupeeraid.mobile%3A%2F%2Fsignin"));
                return new JSONObject().put("access_token","OAuth").put("token_type","bearer").put("expires_in",3600);
            }
            assertTrue(url.startsWith("https://api.isthereanydeal.com/"));
            if(header.equals("Authorization"))assertEquals("Bearer OAuth",credential);
            if(url.contains("/user/info/"))return new JSONObject().put("username","One");
            if(url.contains("/waitlist/"))return method.equals("GET")?new JSONArray().put(game()):new JSONObject();
            if(url.contains("/collection/") || url.contains("/ignored/"))return new JSONArray();
            if(url.contains("/deals/")){
                if(method.equals("POST"))assertEquals("IN",new JSONObject(body).getString("country"));
                else assertTrue(url.contains("country=IN"));
                return new JSONObject().put("list",new JSONArray().put(game().put("deal",offer("Steam","INR"))));
            }
            assertEquals("ITAD-API-Key",header);assertEquals("personal",credential);
            assertTrue(url.contains("country=IN")); assertEquals(ID,new JSONArray(body).get(0));
            return new JSONArray().put(new JSONObject().put("id",ID).put("deals",new JSONArray()
                .put(offer("Other store","INR")).put(offer("Steam","USD")).put(offer("Steam","INR"))));
        });
        api.login("code","verifier");
        JSONObject first=api.dashboard();
        assertEquals(14,calls.size());assertFalse(first.getBoolean("personal"));
        assertEquals(0,first.getJSONArray("wishlist").getJSONObject(0).getJSONArray("offers").getJSONObject(0).getInt("paise"));
        api.dashboard();assertEquals(14,calls.size());
        api.setKey("personal");JSONObject personal=api.dashboard();assertEquals(16,calls.size());
        JSONArray offers=personal.getJSONArray("wishlist").getJSONObject(0).getJSONArray("offers");
        assertEquals(3,offers.length());assertEquals("INDIA",offers.getJSONObject(0).getString("voucher"));
        assertTrue(offers.getJSONObject(0).getString("url").endsWith("affiliate=test"));
        assertFalse(offers.getJSONObject(1).has("paise"));assertFalse(offers.getJSONObject(2).has("paise"));
        api.dashboard();assertEquals(16,calls.size());api.changeWaitlist(ID,true);assertFalse(memory.containsKey("lists"));
        api.setKey("");assertTrue(memory.read("personalKey").isEmpty());
    }
    @Test public void localBudgetAndProviderBackoffSurviveReopen() throws Exception {
        Memory m=new Memory();JSONArray calls=new JSONArray();long now=System.currentTimeMillis()/1000;
        for(int i=0;i<100;i++)calls.put(now);
        m.write("quota",new JSONObject().put("calls",calls).toString());
        int[] n={0}; MobileApi.Transport t=(a,b,c,d,e,f)->{n[0]++;throw new MobileApi.Failure(429,75,"later");};
        MobileApi api=new MobileApi(m,"id",t);
        assertEquals(429,assertThrows(MobileApi.Failure.class,()->api.login("a","b")).status);assertEquals(0,n[0]);
        assertEquals(75,assertThrows(MobileApi.Failure.class,()->api.setKey("private")).retry);
        assertTrue(assertThrows(MobileApi.Failure.class,()->new MobileApi(m,"id",t).setKey("private")).retry>0);
        assertEquals(1,n[0]);assertTrue(m.read("personalKey").isEmpty());
    }
    @Test public void expiredOffersAndUnsafeLinksAreOmitted() throws Exception {
        assertNull(MobileApi.offer(offer("Steam","INR").put("expiry","2020-01-01T00:00:00Z")));
        assertNull(MobileApi.offer(offer("Steam","INR").put("url","javascript:void(0)")));
        assertEquals(300,MobileApi.retry(null));assertEquals(65,MobileApi.retry("65"));
    }

    @Test public void oauthPricesWaitlistedGamesOutsideDiscoveryIncludingFullPriceAndPagination() throws Exception {
        Memory m=new Memory();
        m.write("oauth",new JSONObject().put("access_token","oauth").put("expires",System.currentTimeMillis()/1000+1000).toString());
        JSONObject unseen=game().put("title","Far Cry 3").put("assets",new JSONObject().put("banner300","https://assets.isthereanydeal.com/example.jpg"));
        int[] pages={0};
        MobileApi api=new MobileApi(m,"public",(url,method,header,credential,format,body)->{
            assertEquals("Bearer oauth",credential);
            if(url.endsWith("/user/info/v2"))return new JSONObject().put("username",JSONObject.NULL);
            if(url.endsWith("/waitlist/games/v1"))return new JSONArray().put(unseen);
            if(!url.endsWith("/deals/v2"))return new JSONArray();
            JSONObject req=new JSONObject(body);
            assertEquals("IN",req.getString("country"));
            JSONObject response=new JSONObject().put("list",new JSONArray()).put("hasMore",false);
            if(!req.getJSONObject("filter").optBoolean("inWaitlist"))return response;
            assertTrue(req.getBoolean("nondeals"));assertTrue(req.getBoolean("mature"));
            int shop=req.getJSONArray("shops").getInt(0);pages[0]++;
            if(shop==61 && req.getInt("offset")==0)return response.put("hasMore",true).put("nextOffset",200);
            if(shop!=36 && shop!=61)return response;
            JSONObject record=offer(shop==36?"GreenManGaming":"Steam","INR")
                    .put("shop",new JSONObject().put("id",shop).put("name",shop==36?"GreenManGaming":"Steam"))
                    .put("cut",0).put("price",new JSONObject().put("amountInt",24900).put("currency","INR"))
                    .put("regular",new JSONObject().put("amountInt",24900).put("currency","INR")).put("url","https://itad.link/test/"+shop);
            return response.put("list",new JSONArray().put(new JSONObject().put("id",ID).put("deal",record)));
        });
        JSONObject data=api.dashboard(), farCry=data.getJSONArray("wishlist").getJSONObject(0);
        assertEquals("IsThereAnyDeal member",data.getString("name"));
        assertEquals(0,data.getJSONArray("deals").length());assertEquals(5,pages[0]);assertEquals(2,farCry.getJSONArray("offers").length());
        assertEquals(24900,Market.best(farCry,"All").getLong("paise"));
        assertTrue(Market.art(farCry,"banner300").startsWith("https://assets.isthereanydeal.com/"));
        api.dashboard();assertEquals(5,pages[0]);
    }

    @Test public void cheapestVerifiedOfferRespectsPlatformAndHistoricalFlags() throws Exception {
        JSONObject raw=offer("Steam","INR").put("flag","N")
            .put("platforms",new JSONArray().put(new JSONObject().put("name","Windows")));
        JSONObject pc=MobileApi.offer(raw);
        JSONObject xbox=new JSONObject(pc.toString()).put("paise",99900).put("flag","S")
            .put("platforms",new JSONArray().put(new JSONObject().put("name","Xbox Series X")));
        JSONObject unknown=new JSONObject(pc.toString());unknown.remove("paise");
        JSONObject item=game().put("offers",new JSONArray().put(unknown).put(xbox).put(pc));
        JSONObject data=new JSONObject().put("lows",new JSONArray().put(item)).put("deals",new JSONArray().put(item));
        assertTrue(Market.historical(Market.best(item,"All")));assertEquals(xbox,Market.best(item,"Xbox"));
        assertTrue(Market.rows(data,"lows","Xbox","","Default").isEmpty());assertTrue(Market.rows(data,"deals","Switch","","Price").isEmpty());
        assertEquals(1,Market.rows(data,"free","PC","","Default").size());assertNull(Market.best(item,"PlayStation"));
        assertFalse(Market.nativeInr(new JSONObject().put("id",47).put("name","Steam")));
        assertEquals("",Market.art(game().put("assets",new JSONObject().put("boxart","https://example.org/pic")),"boxart"));
    }
    @Test public void currentPriceDoesNotDependOnReferencePrice() throws Exception {
        JSONObject raw=offer("Steam","INR");raw.remove("regular");
        JSONObject price=MobileApi.offer(raw);
        assertEquals(0,price.getLong("paise"));assertFalse(price.has("regular"));assertFalse(price.has("cut"));
        raw.put("regular",new JSONObject().put("currency","USD").put("amountInt",2999));
        assertFalse(MobileApi.offer(raw).has("regular"));
        assertFalse(MobileApi.offer(offer("Steam","USD")).has("paise"));
    }
    @Test public void searchKeepsExactUnpricedEditionAndExcludesConsoleOnly() throws Exception {
        JSONArray hits=new JSONArray();
        for(String title:new String[]{"Game","Game - PS5","Game (Xbox Series X/S)","Game Enhanced","Game Collection"}){
            JSONObject match=game().put("id",title).put("title",title);
            if(title.equals("Game Enhanced"))match.put("offers",new JSONArray().put(MobileApi.offer(offer("Steam","INR"))));
            hits.put(match);
        }
        List<JSONObject> rows=Market.rows(new JSONObject().put("results",hits),"search",Market.ALL,"","Default");
        assertEquals(3,rows.size());assertEquals("Game Enhanced",rows.get(0).getString("title"));
        assertNull(Market.best(rows.get(1),Market.ALL));
    }
    @Test public void periodRecordsRequireNativeStoreAndDatedMatch() throws Exception {
        java.time.Instant now=java.time.Instant.now();
        JSONObject shop=new JSONObject().put("id",61).put("name","Steam");
        JSONObject historical=new JSONObject().put("amountInt",25000).put("currency","INR");
        JSONObject period=new JSONObject().put("amountInt",39000).put("currency","INR");
        JSONObject game=game().put("periods",new JSONObject().put("all",historical).put("y1",period).put("m3",period));
        JSONObject low=new JSONObject().put("shop",shop).put("price",historical).put("timestamp",now.minusSeconds(400L*86400).toString());
        JSONObject event=new JSONObject().put("shop",shop).put("deal",new JSONObject().put("price",period)).put("timestamp",now.minusSeconds(120L*86400).toString());
        PriceRecords records=new PriceRecords(game,low,null,new JSONArray().put(event));
        assertEquals(25000,records.period(0).amount);assertEquals("Steam",records.period(1).store);assertNull(records.period(2));
        event.put("shop",new JSONObject().put("id",999).put("name","FX store"));
        assertNull(new PriceRecords(game,low,null,new JSONArray().put(event)).period(1));
    }
    @Test public void onlineSearchConfirmsDelistedPcIdsAndCachesLookup() throws Exception {
        Memory memory=new Memory();memory.put("personalKey","private");List<String> calls=new ArrayList<>();
        MobileApi api=new MobileApi(memory,"client",(url,method,header,key,format,body)->{
            calls.add(url);
            if(url.contains("/search/"))return new JSONArray().put(game().put("id","old-pc"))
                    .put(game().put("id","unknown")).put(game().put("id","new-pc").put("title","Enhanced"));
            if(url.contains("/prices/"))return new JSONArray().put(new JSONObject().put("id","new-pc").put("deals",new JSONArray().put(offer("Steam","INR"))));
            if(url.contains("/lookup/shop/61/"))return new JSONObject().put("old-pc",new JSONArray().put("app/111"));
            assertEquals(1,new JSONArray(body).length());assertEquals("unknown",new JSONArray(body).getString(0));
            return new JSONObject();
        });
        JSONArray hits=api.search("game");assertEquals(2,hits.length());
        assertEquals("old-pc",hits.getJSONObject(0).getString("id"));assertNull(Market.best(hits.getJSONObject(0),Market.ALL));
        assertEquals("new-pc",hits.getJSONObject(1).getString("id"));
        int count=calls.size();api.search("game");assertEquals(count,calls.size());
    }
    @Test public void localSuggestionsRankPrefixesTokensAndInitials() throws Exception {
        JSONArray games=new JSONArray();
        for(String title:new String[]{"Grand Theft Auto V","Red Dead Redemption","God of War Ragnarök","Red Dead Redemption II"})
            games.put(new JSONObject().put("id",title).put("title",title));
        TitleIndex index=new TitleIndex(new JSONObject().put("wishlist",games));
        assertEquals("Grand Theft Auto V",index.suggestions("GTA V").get(0).getString("title"));
        assertEquals("God of War Ragnarök",index.suggestions("ragnarok").get(0).getString("title"));
        assertEquals(2,index.suggestions("red redemp").size());
        assertEquals("Red Dead Redemption",index.suggestions("red dead redemption").get(0).getString("title"));
        assertTrue(index.suggestions("no-match").isEmpty());
    }
    @Test public void repeatedTermsReuseCacheAndCredentialBudgetStopsCalls() throws Exception {
        Memory store=new Memory();store.put("personalKey","device");List<String> requested=new ArrayList<>();
        MobileApi api=new MobileApi(store,"client",(url,m,h,k,f,b)->{requested.add(url);return new JSONArray();});
        api.search("portal");api.search("hades");api.search("PORTAL");
        assertEquals(2,requested.size());
        JSONArray used=new JSONArray();for(int i=0;i<100;i++)used.put(System.currentTimeMillis()/1000);
        store.write("quota",new JSONObject().put("keyCalls",used).toString());
        assertEquals(429,assertThrows(MobileApi.Failure.class,()->api.search("fallout")).status);
        assertEquals(2,requested.size());api.search("portal");assertEquals(2,requested.size());
    }
}

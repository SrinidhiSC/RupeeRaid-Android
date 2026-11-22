package in.rupeeraid.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** All traffic goes directly to ITAD. Credentials and private cache use Android Keystore. */
final class MobileApi {
    static final String REDIRECT = "in.rupeeraid.mobile://signin";
    static final String OLD_SCOPE = "user_info wait_read wait_write coll_read ignored_read";
    static final String SCOPE = OLD_SCOPE + " coll_write ignored_write";
    private static final String API = "https://api.isthereanydeal.com";
    private static final String TOKEN = "https://isthereanydeal.com/oauth/token/";
    private static final int WINDOW = 300, BATCH = 200;
    interface Storage {
        String read(String name);
        void write(String name,String value) throws Exception;
        void remove(String name);
    }
    interface Transport {
        Object request(String url,String method,String header,String credential,String format,String body) throws Exception;
    }
    static class Failure extends IOException {
        final int status, retry;
        Failure(int status,int retry,String message) { super(message);this.status=status;this.retry=retry; }
    }
    static final class Consent extends Failure {
        final String scope;
        Consent(String scope){super(412,0,"Additional IsThereAnyDeal permission is needed.");this.scope=scope;}
    }
    private final Storage store;
    private final Transport transport;
    private final String clientId;
    private final String regionScope = PricingRegion.current.prefix();
    MobileApi(Storage store,String clientId,Transport transport) {this.store=store;this.clientId=clientId;this.transport=transport;}
    private static long now(){return System.currentTimeMillis()/1000;}
    private JSONObject saved(String name) throws Exception {
        String raw=store.read(name);return raw.isEmpty()?new JSONObject():new JSONObject(raw);
    }
    static String encode(String s) throws Exception {return URLEncoder.encode(s,"UTF-8");}
    static int retry(String raw) {
        if(raw!=null) try{return Math.max(1,Integer.parseInt(raw));}catch(NumberFormatException ignored){}
        if(raw!=null) try{return (int)Math.max(1,ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()-now());}
        catch(RuntimeException ignored){}
        return WINDOW;
    }
    static Object send(String url,String method,String header,String credential,String format,String body) throws Exception {
        URI uri = new URI(url);
        if(!"https".equals(uri.getScheme()) || !Arrays.asList("api.isthereanydeal.com","isthereanydeal.com").contains(uri.getHost()))
            throw new IOException("Invalid IsThereAnyDeal endpoint.");
        HttpURLConnection c=(HttpURLConnection)uri.toURL().openConnection();
        try {
            c.setInstanceFollowRedirects(false); c.setConnectTimeout(10000); c.setReadTimeout(25000);
            c.setRequestMethod(method);c.setRequestProperty("Accept","application/json");
            if(!header.isEmpty())c.setRequestProperty(header,credential);
            if(body!=null) {
                c.setDoOutput(true);c.setRequestProperty("Content-Type",format);
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(bytes.length);
                try(OutputStream out=c.getOutputStream()){out.write(bytes);}
            }
            int status=c.getResponseCode();
            if(status==429)throw new Failure(429,retry(c.getHeaderField("Retry-After")),"IsThereAnyDeal's rate limit was reached.");
            if("ITAD-API-Key".equals(header) && (status==401 || status==403))
                throw new Failure(403,0,"IsThereAnyDeal declined this personal pricing key.");
            if(status==401 || (url.equals(TOKEN) && status==400))
                throw new Failure(401,0,"IsThereAnyDeal declined authorization. Sign in again.");
            if(status==403)throw new Failure(403,0,"IsThereAnyDeal has not granted access to this feature.");
            if(status<200 || status>=300)throw new Failure(status,0,"IsThereAnyDeal returned HTTP "+status+".");
            if(status==204)return new JSONObject();
            try(InputStream stream=c.getInputStream();BufferedReader reader=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))){
                StringBuilder raw=new StringBuilder();char[] buf=new char[8192];int n;
                while((n=reader.read(buf))!=-1)raw.append(buf,0,n);
                String json=raw.toString().trim();
                return json.isEmpty() ? new JSONObject() : new JSONTokener(json).nextValue();
            }
        } finally {c.disconnect();}
    }
    private static final Object QUOTA_LOCK = new Object();
    private Object request(String url,String method,String header,String credential,String format,String body) throws Exception {
        // Reserve before sending, including failed requests. Reopening the app retains the budget.
        synchronized(QUOTA_LOCK) {
            if (!regionScope.equals(PricingRegion.current.prefix())) throw new IOException("Pricing region changed");
            JSONObject quota=saved("quota");long time=now(),after=quota.optLong("after");
            if(after>time)throw new Failure(429,(int)(after-time),"Waiting for IsThereAnyDeal's retry window.");
            {
                String ledger="ITAD-API-Key".equals(header)?"keyCalls":"calls";
                JSONArray old=quota.optJSONArray(ledger),active=new JSONArray();
                if(old!=null)for(int i=0;i<old.length();i++)if(old.getLong(i)>time-WINDOW)active.put(old.getLong(i));
                if(active.length()>=100)throw new Failure(429,(int)Math.max(1,active.getLong(0)+WINDOW-time),"The five-minute request budget is in use.");
                active.put(time);quota.put(ledger,active);store.write("quota",quota.toString());
            }
        }
        try{return transport.request(url,method,header,credential,format,body);}
        catch(Failure e){
            if(e.status==429)synchronized(QUOTA_LOCK){
                JSONObject quota=saved("quota");quota.put("after",Math.max(quota.optLong("after"),now()+e.retry));store.write("quota",quota.toString());
            }
            throw e;
        }
    }
    private JSONObject token(String params,String previousRefresh,String fallbackScope) throws Exception {
        JSONObject result=(JSONObject)request(TOKEN,"POST","","","application/x-www-form-urlencoded",
                "client_id="+encode(clientId)+"&"+params);
        if(!"Bearer".equalsIgnoreCase(result.optString("token_type")) || result.optString("access_token").isEmpty())
            throw new IOException("Incomplete OAuth response.");
        if(!result.has("refresh_token") && !previousRefresh.isEmpty())result.put("refresh_token",previousRefresh);
        result.put("expires",now()+result.optLong("expires_in",3600));
        if(result.isNull("scope"))result.put("scope",fallbackScope);
        return result;
    }
    void login(String code,String verifier) throws Exception {
        login(code,verifier,OLD_SCOPE);
    }
    void login(String code,String verifier,String requestedScope) throws Exception {
        JSONObject next=token("grant_type=authorization_code&code="+encode(code)+"&redirect_uri="+encode(REDIRECT)+"&code_verifier="+encode(verifier),"",requestedScope);
        JSONObject previous=saved("oauth");
        if(previous.has("access_token")){
            String before=subject(previous),after=subject(next);
            if(before.isEmpty() || !before.equals(after)){
                store.write("nextSession",new JSONObject().put("time",now()).put("token",next).toString());
                throw new Failure(409,0,"Could not confirm the same ITAD account. Your session was kept. To switch accounts, sign out first.");
            }
        }
        store.write("oauth",next.toString());store.remove("nextSession");
    }
    // Compare only access tokens obtained directly from the provider over TLS.
    private static String subject(JSONObject token){
        try{
            String[] parts=token.getString("access_token").split("\\.");
            if(parts.length!=3)return "";
            JSONObject claims=new JSONObject(new String(Base64.getUrlDecoder().decode(parts[1]),StandardCharsets.UTF_8));
            String id=Market.text(claims,"sub");
            return id.isEmpty()?"":Market.text(claims,"iss")+"|"+id;
        }catch(Exception ignored){return "";}
    }
    boolean permits(String scope) throws Exception {
        return Arrays.asList(saved("oauth").optString("scope",OLD_SCOPE).split("\\s+")).contains(scope);
    }
    String requestedScopes(String extra) throws Exception{
        Set<String> scopes=new LinkedHashSet<>(Arrays.asList(OLD_SCOPE.split(" ")));
        List<String> existing=Arrays.asList(saved("oauth").optString("scope","").split("\\s+"));
        List<String> requested=Arrays.asList(extra.split(" "));
        for(String value:Arrays.asList("coll_write","ignored_write","notes_read","notes_write"))
            if(existing.contains(value) || requested.contains(value))scopes.add(value);
        return String.join(" ",scopes);
    }
    private String bearer() throws Exception {
        JSONObject token=saved("oauth");
        if(token.optLong("expires")<=now()+10) {
            String refresh=token.optString("refresh_token");
            if(refresh.isEmpty())throw new Failure(401,0,"Sign in again to renew your session.");
            token=token("grant_type=refresh_token&refresh_token="+encode(refresh),refresh,token.optString("scope",OLD_SCOPE));
            store.write("oauth",token.toString());
        }
        return "Bearer "+token.getString("access_token");
    }
    private Object account(String path,String method,String body) throws Exception {
        return request(API+path,method,"Authorization",bearer(),"application/json",body);
    }
    private Object pricing(String path,String method,String key,String body) throws Exception {
        return request(API+path,method,"ITAD-API-Key",key,"application/json",body);
    }
    Object gameData(String path,String method,String body) throws Exception {
        String key=store.read("personalKey");
        if(key.isEmpty())throw new Failure(428,0,"Add your personal IsThereAnyDeal API key in Account to load this section.");
        return pricing(path,method,key,body);
    }
    PricingRegion resolveRegion(String country) throws Exception {
        JSONObject body = new JSONObject().put("country", country).put("limit", 1);
        JSONObject page = (JSONObject) account("/deals/v2", "POST", body.toString());
        for (JSONObject row : Market.objects(page.optJSONArray("list"))) {
            JSONObject deal = row.optJSONObject("deal");
            String currency = deal == null ? "" : Market.text(deal.optJSONObject("price"), "currency");
            if (currency.matches("[A-Z]{3}")) return new PricingRegion(country, currency);
        }
        throw new Failure(422, 0, "ITAD has no regional price for this country right now.");
    }
    void setKey(String key) throws Exception {
        if(key.isEmpty())store.remove("personalKey");
        else {
            if(key.length()>1024 || !key.matches("[!-~]+"))throw new Failure(400,0,"Enter a valid personal pricing key.");
            pricing("/deals/v2?country="+PricingRegion.current.country+"&limit=1","GET",key,null);
            store.write("personalKey",key);
        }
        store.remove("quotes");
        store.remove("searchCache");
    }
    private static long expiry(JSONObject offer){
        if(offer.isNull("expiry"))return 0;
        try{return Instant.parse(offer.getString("expiry")).getEpochSecond();}catch(Exception invalid){return -1;}
    }
    static JSONObject offer(JSONObject entry) throws Exception {
        return offer(entry,null);
    }
    static JSONObject offer(JSONObject entry,JSONObject gameLow) throws Exception {
        return offer(entry,gameLow,null);
    }
    static JSONObject offer(JSONObject entry,JSONObject gameLow,JSONObject periods) throws Exception {
        if(entry==null)return null;
        JSONObject shop=entry.optJSONObject("shop"), price=entry.optJSONObject("price"), regular=entry.optJSONObject("regular");
        if(shop==null || shop.optString("name").isEmpty())return null;
        String link=entry.optString("url");
        long reportedAt;
        try{
            URI uri=new URI(link);
            if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null)return null;
            reportedAt=Instant.parse(entry.getString("timestamp")).getEpochSecond();
        }catch(Exception invalid){return null;}
        long expiry=expiry(entry);if(expiry<0 || expiry>0 && expiry<=now())return null;
        JSONObject out=new JSONObject().put("url",link).put("shop",shop.getString("name"))
            .put("shopId",Market.shopId(shop)).put("platforms",entry.optJSONArray("platforms"))
            .put("expiry",expiry).put("updated",entry.getString("timestamp")).put("reportedAt",reportedAt);
        long amount=Market.nativeAmount(shop,price);
        if(amount<0)return out;
        long rrp=Market.nativeAmount(shop,regular);
        int cut=entry.optInt("cut",-1);
        if(rrp>=amount)out.put("regular",rrp);
        if(rrp>=amount && cut>=0 && cut<=100)out.put("cut",cut);
        String flag=Market.text(entry,"flag");
        JSONObject low=entry.optJSONObject("historyLow");
        if(low==null)low=gameLow;
        saveLow(out,"allLow",low);
        saveLow(out,"yearLow",periods==null?entry.optJSONObject("historyLow_1y"):periods.optJSONObject("y1"));
        saveLow(out,"quarterLow",periods==null?entry.optJSONObject("historyLow_3m"):periods.optJSONObject("m3"));
        if(low!=null && (flag.equals("N") || flag.equals("H"))){
            long reference=Market.nativeAmount(shop,low);
            if(reference<0 || (flag.equals("N")?amount>reference:amount!=reference))flag="";
        }
        long storeLow=Market.nativeAmount(shop,entry.optJSONObject("storeLow"));
        if(storeLow>=0){out.put("storeLow",storeLow);if(amount>storeLow)flag="";}
        return out.put("paise",amount).put("flag",flag).put("voucher",entry.opt("voucher"))
                .put("country",PricingRegion.current.country).put("currency",Market.text(price,"currency")).put("drm",entry.optJSONArray("drm"));
    }
    private static void saveLow(JSONObject offer,String label,JSONObject value) throws Exception{
        if(value!=null && PricingRegion.current.currency.equals(Market.text(value,"currency")) && value.opt("amountInt") instanceof Number
                && value.optLong("amountInt",-1)>=0)offer.put(label,value.getLong("amountInt"));
    }
    private static void collect(Map<String,JSONObject> games,JSONArray items) throws Exception {
        for(int i=0;i<items.length();i++){JSONObject game=items.getJSONObject(i);games.put(game.getString("id"),game);}
    }
    private static JSONArray games(JSONArray items,JSONObject quotes) throws Exception {
        JSONArray result=new JSONArray();
        for(int i=0;i<items.length();i++){
            JSONObject game=items.getJSONObject(i);String id=game.getString("id");
            result.put(new JSONObject().put("id",id).put("title",game.getString("title")).put("slug",game.opt("slug"))
                .put("assets",game.optJSONObject("assets")).put("type",game.opt("type"))
                .put("offers",consistentLows(quotes.optJSONArray(id))));
        }
        return result;
    }
    static JSONArray consistentLows(JSONArray offers) throws Exception{
        if(offers==null)return new JSONArray();
        long best=Long.MAX_VALUE,ts=now();
        for(JSONObject q:Market.objects(offers))if(Market.pcOffer(q) && Market.active(q,ts) && q.optLong("paise",-1)>=0
                && (!q.has("country") || PricingRegion.current.country.equals(Market.text(q,"country")))
                && (!q.has("currency") || PricingRegion.current.currency.equals(Market.text(q,"currency"))))best=Math.min(best,q.getLong("paise"));
        for(JSONObject q:Market.objects(offers))if(Market.historical(q) && q.getLong("paise")>best)
            q.put("flag",q.optLong("storeLow",-1)==q.getLong("paise")?"S":"");
        for(JSONObject q:Market.objects(offers))if(q.has("paise"))q.put("currentFloor",best);
        return offers;
    }
    static JSONObject restoreSnapshot(String raw) throws Exception{
        JSONObject snapshot=new JSONObject(raw);
        for(String list:Arrays.asList("wishlist","library","ignored","trending","deals","best","lows","free"))
            for(JSONObject game:Market.objects(snapshot.optJSONArray(list)))consistentLows(game.optJSONArray("offers"));
        return snapshot;
    }
    private void quotes(Collection<String> ids,String key,JSONObject book,long ttl) throws Exception {
        ArrayList<String> missing=new ArrayList<>();
        for(String id:ids){JSONObject row=book.optJSONObject(id);if(row==null || row.optLong("saved")+ttl<=now())missing.add(id);}
        for(int offset=0;offset<missing.size();offset+=BATCH) {
            List<String> batch=missing.subList(offset,Math.min(offset+BATCH,missing.size()));
            JSONArray prices=(JSONArray)pricing("/games/prices/v3?country="+PricingRegion.current.country+"&vouchers=true","POST",key,new JSONArray(batch).toString());
            Map<String,JSONObject> byGame=new HashMap<>();
            for(int i=0;i<prices.length();i++){
                JSONObject rec=prices.getJSONObject(i);JSONArray rows=rec.getJSONArray("deals");List<JSONObject> parsed=new ArrayList<>();
                JSONObject lows=rec.optJSONObject("historyLow");
                for(int j=0;j<rows.length();j++){JSONObject item=offer(rows.optJSONObject(j),lows==null?null:lows.optJSONObject("all"),lows);if(item!=null)parsed.add(item);}
                parsed.sort(Comparator.comparingLong(item->item.optLong("paise",Long.MAX_VALUE)));
                byGame.put(rec.getString("id"),new JSONObject().put("items",consistentLows(new JSONArray(parsed))).put("periods",lows));
            }
            for(String id:batch){
                JSONObject result=byGame.get(id);
                // ITAD omits editions without current offers from successful batches.
                if(result==null)result=new JSONObject().put("items",new JSONArray());
                book.put(id,result.put("saved",now()));
            }
            store.write("quotes",book.toString());
        }
    }
    private JSONObject dealPage(JSONObject filters) throws Exception {
        filters.put("country",PricingRegion.current.country);
        return (JSONObject) account("/deals/v2","POST",filters.toString());
    }

    private JSONArray discovery(String section) throws Exception {
        JSONArray shops = new JSONArray();
        for (int shop : Market.INR_SHOPS) shops.put(shop);
        JSONObject filter = new JSONObject().put("type",new JSONArray().put(1));
        JSONObject body = new JSONObject().put("shops",shops).put("filter",filter);
        if ("trending".equals(section)) body.put("sort","-trending");
        if ("best".equals(section)) body.put("sort","-cut");
        if ("lows".equals(section)) filter.put("flag","H");
        if ("free".equals(section)) {
            filter.put("price",new JSONObject().put("min",0).put("max",0));
            body.put("nondeals",true);
        }
        return dealPage(body.put("limit","deals".equals(section) ? 60 : 24)).getJSONArray("list");
    }

    private static void addOffer(JSONObject amounts, JSONObject game) throws Exception {
        JSONObject quote = offer(game.optJSONObject("deal"));
        if (quote == null) return;
        String id = game.getString("id");
        JSONArray offers = amounts.optJSONArray(id);
        if (offers == null) amounts.put(id,offers = new JSONArray());
        for (int i=0; i<offers.length(); i++) {
            JSONObject existing=offers.getJSONObject(i);
            if(quote.getString("url").equals(existing.getString("url"))){
                if(!existing.has("paise") && quote.has("paise"))offers.put(i,quote);
                return;
            }
        }
        offers.put(quote);
    }

    // Each page has one best offer per game. A separate shop filter preserves alternatives
    // and lets logged-in users compare eligible stores without distributing a shared key.
    private JSONObject listPrices(JSONArray games,boolean wishlist) throws Exception {
        Set<String> wanted = new TreeSet<>();
        for (JSONObject g : Market.objects(games)) wanted.add(g.getString("id"));
        if (wanted.isEmpty()) return new JSONObject();
        String ids = String.join(",",wanted);
        String cacheName = wishlist ? "waitPrices" : "collectionPrices";
        JSONObject cache = saved(cacheName);
        long completed = cache.optLong("completed");
        if (!ids.equals(cache.optString("ids")) || (completed>0 && completed+WINDOW<=now())
                || cache.optLong("started")+86400<=now()) {
            cache = new JSONObject().put("ids",ids).put("started",now()).put("offers",new JSONObject()).put("pages",new JSONObject());
        }
        JSONObject offers = cache.getJSONObject("offers"), pages = cache.getJSONObject("pages");
        if (cache.has("completed")) return offers;
        // For the library, a single multi-store query supplies the best verified price.
        // Many collections contain thousands of games, so request these pages only on entry.
        for (int shop : wishlist ? Market.INR_SHOPS : new int[]{0}) {
            String shopKey = String.valueOf(shop);
            JSONArray shops = new JSONArray();
            if (wishlist) shops.put(shop);
            else for(int known : Market.INR_SHOPS) shops.put(known);
            int offset = pages.optInt(shopKey,0);
            while (offset >= 0) {
                JSONObject filters = new JSONObject().put(wishlist ? "inWaitlist" : "inCollection",true);
                JSONObject response = dealPage(new JSONObject().put("shops",shops)
                        .put("filter",filters).put("nondeals",true).put("mature",true).put("limit",BATCH).put("offset",offset));
                for (JSONObject game : Market.objects(response.getJSONArray("list"))) {
                    if (wanted.contains(game.optString("id"))) addOffer(offers,game);
                }
                int next = response.optInt("nextOffset",-1);
                if (response.optBoolean("hasMore") && next <= offset) throw new IOException("ITAD returned an invalid price page.");
                offset = response.optBoolean("hasMore") ? next : -1;
                pages.put(shopKey,offset);
                store.write(cacheName,cache.toString());
            }
        }
        cache.put("completed",now());
        store.write(cacheName,cache.toString());
        return offers;
    }

    JSONArray library(JSONArray collection) throws Exception {
        return games(collection,listPrices(collection,false));
    }

    JSONObject detailPrices(JSONObject game) throws Exception {
        String key = store.read("personalKey");
        if (key.isEmpty()) return game;
        String id = game.getString("id");
        JSONObject book = saved("quotes");
        quotes(Collections.singleton(id),key,book,WINDOW);
        JSONObject quote=book.getJSONObject(id);
        return new JSONObject(game.toString()).put("offers",consistentLows(quote.getJSONArray("items"))).put("periods",quote.optJSONObject("periods"));
    }

    private JSONObject accountLists() throws Exception {
        JSONObject lists = saved("lists");
        if (lists.has("best") && lists.optLong("saved")+WINDOW > now()) return lists;
        JSONObject fresh = new JSONObject();
        JSONObject profile = (JSONObject)account("/user/info/v2","GET",null);
        fresh.put("name",Market.text(profile,"username"));
        fresh.put("wishlist",account("/waitlist/games/v1","GET",null));
        fresh.put("library",account("/collection/games/v1","GET",null));
        fresh.put("ignored",account("/ignored/games/v1","GET",null));
        for (String section : Arrays.asList("deals","trending","lows","best","free")) fresh.put(section,discovery(section));
        fresh.put("saved",now());
        store.write("lists",fresh.toString());
        return fresh;
    }

    JSONObject dashboard() throws Exception {
        String key = store.read("personalKey");
        JSONObject lists = accountLists();
        JSONObject amounts = new JSONObject();
        List<String> priced = Arrays.asList("wishlist","deals","trending","lows","best","free");
        Map<String,JSONObject> titles = new LinkedHashMap<>();
        for (String section : priced) collect(titles,lists.getJSONArray(section));
        String warning = "";
        int retryAfter = 0;
        try {
            if (key.isEmpty()) {
                amounts = listPrices(lists.getJSONArray("wishlist"),true);
            } else {
                JSONObject book = saved("quotes");
                quotes(titles.keySet(),key,book,20);
                for (String id : titles.keySet()) amounts.put(id,book.getJSONObject(id).getJSONArray("items"));
            }
        } catch (IOException ex) {
            if (ex instanceof Failure && ((Failure)ex).status == 401) throw ex;
            warning = ex instanceof Failure ? ex.getMessage() : "The store price check was interrupted.";
            if (ex instanceof Failure) retryAfter = ((Failure)ex).retry;
            // Keep dated same-account observations during outages. Expired offers are hidden by Market.
            JSONObject previous = saved("snapshot");
            if (previous.optLong("schema")==2) {
                for (String section : priced) for (JSONObject game : Market.objects(previous.optJSONArray(section))) {
                    JSONArray prior=game.optJSONArray("offers");if(prior==null || prior.length()==0)continue;
                    for(JSONObject row:Market.objects(prior))if(!row.has("cachedAt"))row.put("cachedAt",previous.optLong("saved"));
                    amounts.put(game.getString("id"),prior);
                }
                if(amounts.length()>0)warning+=" Showing saved offers.";
            }
        }
        // A feed's best offer is independently usable if the private price query failed.
        for (String section : Arrays.asList("deals","trending","lows","best","free")) {
            for (JSONObject g : Market.objects(lists.getJSONArray(section))) {
                JSONArray existing=amounts.optJSONArray(g.getString("id"));
                boolean verified=false;
                for(JSONObject row:Market.objects(existing))if(row.has("paise") && Market.pcOffer(row) && Market.active(row,now())){verified=true;break;}
                if(key.isEmpty() || !verified)addOffer(amounts,g);
            }
        }
        JSONObject result = new JSONObject().put("name",Market.accountName(lists)).put("schema",2)
                .put("saved",key.isEmpty()?lists.getLong("saved"):now()).put("personal",!key.isEmpty())
                .put("warning",warning).put("retryAfter",retryAfter);
        for (String section : Arrays.asList("wishlist","deals","trending","lows","best","free","library","ignored")) {
            result.put(section,games(lists.getJSONArray(section),amounts));
        }
        return result;
    }

    JSONArray search(String query) throws Exception {
        String key = store.read("personalKey");
        if (key.isEmpty()) throw new Failure(403,0,"Add a personal key in Account to search the full catalog.");
        JSONObject cached = saved("searchCache");
        String term=TitleIndex.words(query);
        if(term.length()<2)return new JSONArray();
        JSONArray history=cached.optJSONArray("entries"),recent=new JSONArray();
        for(JSONObject entry:Market.objects(history)){
            if(entry.optLong("saved")+WINDOW<=now())continue;
            if(term.equals(entry.optString("query")))return entry.getJSONArray("results");
            recent.put(entry);
        }
        JSONArray found=(JSONArray)pricing("/games/search/v1?results=25&title="+encode(query.trim()),"GET",key,null);
        JSONArray result=pcSearchResults(priceCatalog(found,key),key);
        while(recent.length()>=16)recent.remove(0);
        recent.put(new JSONObject().put("query",term).put("saved",now()).put("results",result));
        store.write("searchCache",new JSONObject().put("entries",recent).toString());
        return result;
    }

    private JSONArray pcSearchResults(JSONArray games,String key) throws Exception {
        Set<String> pending=new LinkedHashSet<>(),confirmed=new HashSet<>();
        for(JSONObject game:Market.objects(games)){
            if(!Market.pcEdition(game))continue;
            String id=game.getString("id");
            boolean desktop=false;
            for(JSONObject offer:Market.objects(game.optJSONArray("offers"))){
                if(Market.supports(offer,"PC") || Market.pcOffer(offer) && offer.optInt("shopId")==61){desktop=true;break;}
            }
            if(desktop)confirmed.add(id);else pending.add(id);
        }
        // Catalog mappings survive a delisting. They identify the edition without furnishing a price.
        for(int shop:new int[]{61,35,16}){
            if(pending.isEmpty())break;
            JSONObject mapped;
            try{mapped=(JSONObject)pricing("/lookup/shop/"+shop+"/id/v1","POST",key,new JSONArray(pending).toString());}
            catch(IOException unavailable){
                if(confirmed.isEmpty())throw unavailable;
                break;
            }
            for(Iterator<String> it=pending.iterator();it.hasNext();){
                String id=it.next();JSONArray ids=mapped.optJSONArray(id);
                if(ids!=null && ids.length()>0){confirmed.add(id);it.remove();}
            }
        }
        JSONArray result=new JSONArray();
        for(JSONObject game:Market.objects(games))if(confirmed.contains(game.optString("id")))result.put(game);
        return result;
    }

    JSONArray priceCatalog(JSONArray found,String key) throws Exception {
        Map<String,JSONObject> titles=new LinkedHashMap<>();
        collect(titles,found);
        JSONObject book=saved("quotes"),prices=new JSONObject();
        quotes(titles.keySet(),key,book,WINDOW);
        for(String id:titles.keySet())prices.put(id,book.getJSONObject(id).getJSONArray("items"));
        return games(found,prices);
    }
    void changeWaitlist(String id,boolean add) throws Exception {
        changeList("wishlist",id,add);
    }
    void changeList(String list,String id,boolean add) throws Exception {
        UUID.fromString(id);
        String path,scope;
        switch(list){
            case "wishlist":path="/waitlist/games/v1";scope="wait_write";break;
            case "library":path="/collection/games/v1";scope="coll_write";break;
            case "ignored":path="/ignored/games/v1";scope="ignored_write";break;
            default:throw new IllegalArgumentException("Unknown account list");
        }
        if(!permits(scope))throw new Consent(scope);
        account(path,add?"PUT":"DELETE",new JSONArray().put(id).toString());
        store.remove("lists");
        if(list.equals("wishlist"))store.remove("waitPrices");
        if(list.equals("library"))store.remove("collectionPrices");
    }
    JSONObject monitoredPrices(JSONObject rules) throws Exception {
        return monitoredPrices(rules,null);
    }
    JSONObject monitoredPrices(PriceAlerts alerts) throws Exception {
        JSONArray wishlist=null;
        if(alerts.universalEnabled()){
            wishlist=(JSONArray)account("/waitlist/games/v1","GET",null);
            alerts.reconcileUniversal(wishlist);
        }
        return monitoredPrices(alerts.rules,wishlist);
    }
    private JSONObject monitoredPrices(JSONObject rules,JSONArray wishlist) throws Exception {
        List<String> ids=new ArrayList<>();rules.keys().forEachRemaining(ids::add);
        JSONObject result=new JSONObject();
        if(ids.isEmpty())return result;
        String key=store.read("personalKey");
        if(!key.isEmpty()){
            JSONObject book=saved("quotes");quotes(ids,key,book,WINDOW);
            for(String id:ids)result.put(id,consistentLows(book.getJSONObject(id).getJSONArray("items")));
            return result;
        }
        if(wishlist==null)wishlist=(JSONArray)account("/waitlist/games/v1","GET",null);
        JSONObject all=listPrices(wishlist,true);
        for(String id:ids)result.put(id,consistentLows(all.optJSONArray(id)));
        return result;
    }
    JSONObject gameNote(String id) throws Exception {
        UUID.fromString(id);
        if(!permits("notes_read"))throw new Consent("notes_read notes_write");
        JSONObject cache=saved("gameNotes");
        if(cache.optLong("saved")+WINDOW<=now()){
            try{
                JSONArray notes=(JSONArray)account("/user/notes/v1","GET",null);JSONObject entries=new JSONObject();
                for(JSONObject row:Market.objects(notes))entries.put(row.getString("gid"),row.getString("note"));
                cache=new JSONObject().put("saved",now()).put("entries",entries);store.write("gameNotes",cache.toString());
            }catch(IOException e){
                if(!cache.has("entries"))throw e;
                return new JSONObject().put("note",cache.getJSONObject("entries").optString(id)).put("cached",true);
            }
        }
        return new JSONObject().put("note",cache.getJSONObject("entries").optString(id));
    }
    void writeNote(String id,String text) throws Exception {
        UUID.fromString(id);
        if(!permits("notes_write"))throw new Consent("notes_read notes_write");
        JSONArray body=new JSONArray();
        if(text.isEmpty())body.put(id);else body.put(new JSONObject().put("gid",id).put("note",text));
        account("/user/notes/v1",text.isEmpty()?"DELETE":"PUT",body.toString());
        store.remove("gameNotes");
    }
}

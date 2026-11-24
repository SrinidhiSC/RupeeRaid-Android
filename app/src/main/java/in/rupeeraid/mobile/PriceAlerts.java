package in.rupeeraid.mobile;

import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Device-local rules, per-store observations and inbox committed together. */
final class PriceAlerts {
    static final String LOW="low", YEAR_LOW="yearLow", QUARTER_LOW="quarterLow", STORE_LOW="storeLow", DROP="drop", TARGET="target";
    final JSONObject document,rules;
    final JSONArray inbox;
    private final MobileApi.Storage storage;
    PriceAlerts(MobileApi.Storage storage) throws Exception {
        this.storage=storage;
        String raw=storage.read("priceAlerts");
        document=raw.isEmpty()?new JSONObject():new JSONObject(raw);
        if(document.isNull("rules"))document.put("rules",new JSONObject());
        if(document.isNull("inbox"))document.put("inbox",new JSONArray());
        if(document.optJSONObject("universal")!=null && document.isNull("universalExclusions"))document.put("universalExclusions",new JSONArray());
        rules=document.getJSONObject("rules");inbox=document.getJSONArray("inbox");
        long cutoff=System.currentTimeMillis()/1000-30L*24*60*60;
        int before=inbox.length();
        for(int i=before-1;i>=0;i--){
            JSONObject event=inbox.optJSONObject(i);
            if(event==null || event.optLong("when")<=cutoff)inbox.remove(i);
        }
        if(inbox.length()!=before)save();
    }
    static boolean includes(JSONArray stores,int shop){
        if(stores!=null)for(int i=0;i<stores.length();i++)if(stores.optInt(i,-1)==shop)return true;
        return false;
    }
    void save(){try{storage.write("priceAlerts",document.toString());}catch(Exception e){throw new IllegalStateException("Could not save price alerts",e);}}
    void configure(JSONObject game,String mode,JSONArray selections,long target) throws Exception {
        configure(game,mode,selections,target,"game");
        removeExclusion(Market.text(game,"id"));save();
    }
    private void configure(JSONObject game,String mode,JSONArray selections,long target,String source) throws Exception {
        if(!Arrays.asList(LOW,YEAR_LOW,QUARTER_LOW,STORE_LOW,DROP,TARGET).contains(mode) || (TARGET.equals(mode) && target<0))throw new IllegalArgumentException("Invalid alert");
        String id=UUID.fromString(game.getString("id")).toString();
        JSONArray stores=new JSONArray();
        for(int shop:Market.INR_SHOPS)if(includes(selections,shop))stores.put(shop);
        if(stores.length()==0)throw new IllegalArgumentException("Select at least one store");
        JSONObject previous=rules.optJSONObject(id);
        boolean same=previous!=null && mode.equals(previous.optString("mode")) && target==previous.optLong("target",-1);
        JSONObject last=new JSONObject();
        for(int shop:Market.INR_SHOPS){
            String key=String.valueOf(shop);
            if(same && includes(stores,shop) && previous.getJSONObject("last").has(key))
                last.put(key,previous.getJSONObject("last").getJSONObject(key));
        }
        JSONObject ref=new JSONObject(game.toString());ref.remove("offers");ref.remove("deal");
        rules.put(id,new JSONObject().put("game",ref).put("mode",mode).put("shops",stores).put("target",target).put("last",last).put("source",source));
    }
    void configureUniversal(JSONArray wishlist,String mode,JSONArray stores) throws Exception {
        JSONObject universal=new JSONObject().put("enabled",true).put("mode",mode).put("shops",stores).put("target",-1);
        document.put("universal",universal);
        if(document.isNull("universalExclusions"))document.put("universalExclusions",new JSONArray());
        reconcileUniversal(wishlist);
    }
    void reconcileUniversal(JSONArray wishlist) throws Exception {
        JSONObject universal=document.optJSONObject("universal");if(universal==null || !universal.optBoolean("enabled"))return;
        String mode=universal.optString("mode",LOW);JSONArray stores=universal.optJSONArray("shops");
        Set<String> current=new HashSet<>();
        for(JSONObject game:Market.objects(wishlist)){
            String id=Market.text(game,"id");if(id.isEmpty())continue;current.add(id);
            JSONObject existing=rules.optJSONObject(id);
            if(!excluded(id) && (existing==null || "universal".equals(existing.optString("source"))))configure(game,mode,stores,-1,"universal");
        }
        for(String id:ruleIds()){
            JSONObject rule=rules.optJSONObject(id);
            if(rule!=null && "universal".equals(rule.optString("source")) && !current.contains(id))rules.remove(id);
        }
        save();
    }
    void disableUniversal(){
        document.remove("universal");
        document.remove("universalExclusions");
        for(String id:ruleIds()){
            JSONObject rule=rules.optJSONObject(id);if(rule!=null && "universal".equals(rule.optString("source")))rules.remove(id);
        }
        save();
    }
    boolean universalEnabled(){return document.optJSONObject("universal")!=null && document.optJSONObject("universal").optBoolean("enabled");}
    boolean monitoring(){return universalEnabled() || rules.length()>0;}
    private List<String> ruleIds(){List<String> ids=new ArrayList<>();rules.keys().forEachRemaining(ids::add);return ids;}
    static boolean gameSpecific(JSONObject rule) {
        return rule != null && !"universal".equals(rule.optString("source"));
    }
    void clearGameRule(String id, JSONArray wishlist) throws Exception {
        if (!gameSpecific(rules.optJSONObject(id))) return;
        rules.remove(id);
        if (universalEnabled()) reconcileUniversal(wishlist); else save();
    }
    void disable(String id){
        rules.remove(id);
        if(universalEnabled() && !excluded(id))document.optJSONArray("universalExclusions").put(id);
        save();
    }
    private boolean excluded(String id){
        JSONArray values=document.optJSONArray("universalExclusions");
        if(values!=null)for(int i=0;i<values.length();i++)if(id.equals(values.optString(i)))return true;return false;
    }
    private void removeExclusion(String id) throws Exception{
        JSONArray values=document.optJSONArray("universalExclusions");if(values==null)return;
        JSONArray next=new JSONArray();for(int i=0;i<values.length();i++)if(!id.equals(values.optString(i)))next.put(values.get(i));
        document.put("universalExclusions",next);
    }
    void markRead(){for(JSONObject event:Market.objects(inbox))try{event.put("read",true);}catch(Exception ignored){}save();}
    int unread(){int n=0;for(JSONObject e:Market.objects(inbox))if(!e.optBoolean("read"))n++;return n;}

    JSONArray observe(JSONObject prices,long now) throws Exception {
        JSONArray events=new JSONArray();
        for(Iterator<String> it=rules.keys();it.hasNext();){
            String id=it.next();JSONObject rule=rules.getJSONObject(id);
            Map<Integer,JSONObject> best=new TreeMap<>();
            for(JSONObject offer:Market.objects(prices.optJSONArray(id))){
                int shop=offer.optInt("shopId",-1);
                if(!Market.pcEdition(rule.getJSONObject("game")) || !Market.pcOffer(offer) || !includes(rule.optJSONArray("shops"),shop) || Market.shopName(shop).isEmpty()
                        || !PricingRegion.current.country.equals(Market.text(offer,"country")) || !PricingRegion.current.currency.equals(Market.text(offer,"currency"))
                        || offer.has("cachedAt") || offer.optLong("paise",-1)<0 || offer.optLong("reportedAt")<=0 || !Market.active(offer,now))continue;
                JSONObject other=best.get(shop);
                if(other==null || offer.getLong("paise")<other.getLong("paise")
                        || offer.getLong("paise")==other.getLong("paise") && offer.optLong("reportedAt")>other.optLong("reportedAt"))best.put(shop,offer);
            }
            for(Map.Entry<Integer,JSONObject> row:best.entrySet()){
                JSONObject offer=row.getValue(),last=rule.getJSONObject("last");
                String shop=String.valueOf(row.getKey()),mode=rule.getString("mode");
                JSONObject prev=last.optJSONObject(shop);
                long amount=offer.getLong("paise"),reported=offer.getLong("reportedAt");
                if(prev!=null && reported<prev.optLong("reported"))continue;
                Market.LowPeriod period=Market.lowPeriod(offer);
                boolean low=periodQualifies(mode,period,offer);
                String delivered=prev==null?"":Market.text(prev,"lastEvent");
                if(prev!=null){
                    long was=prev.getLong("amount");
                    boolean qualifies=Arrays.asList(LOW,YEAR_LOW,QUARTER_LOW,STORE_LOW).contains(mode)?low && (!prev.optBoolean("low") || amount<was)
                        :TARGET.equals(mode)?was>rule.getLong("target") && amount<=rule.getLong("target"):amount<was;
                    String key=reported+"@"+amount;
                    if(qualifies && !delivered.equals(key)){
                        JSONObject event=new JSONObject().put("id",UUID.randomUUID().toString()).put("game",rule.getJSONObject("game"))
                            .put("shop",Market.shopName(row.getKey())).put("mode",mode).put("when",now).put("seenAt",reported)
                            .put("amount",amount).put("previous",was).put("cut",offer.optInt("cut")).put("url",Market.text(offer,"url")).put("read",false);
                        if(prev.optLong("storeLow",-1)>=0)event.put("previousStoreLow",prev.getLong("storeLow"));
                        inbox.put(event);events.put(event);delivered=key;
                    }
                }
                last.put(shop,new JSONObject().put("amount",amount).put("reported",reported).put("low",low)
                    .put("storeLow",offer.optLong("storeLow",-1)).put("lastEvent",delivered));
            }
        }
        document.put("checked",now);document.remove("error");save();return events;
    }
    private static boolean periodQualifies(String mode,Market.LowPeriod period,JSONObject offer){
        if(STORE_LOW.equals(mode))return Market.storeLow(offer);
        if(LOW.equals(mode))return period==Market.LowPeriod.ALL_TIME;
        if(YEAR_LOW.equals(mode))return period==Market.LowPeriod.ALL_TIME || period==Market.LowPeriod.YEAR;
        if(QUARTER_LOW.equals(mode))return period!=Market.LowPeriod.NONE;
        return false;
    }
}

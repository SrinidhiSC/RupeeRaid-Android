package in.rupeeraid.mobile;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Resolve provider lows to eligible store observations before displaying a regional price. */
final class PriceRecords {
    static final class Low {
        final long amount;
        final String store,time;
        Low(long amount,String store,String time){this.amount=amount;this.store=store;this.time=time;}
    }
    private final List<Low> observations=new ArrayList<>();
    private final Map<Integer,NavigableMap<Instant,Low>> changes=new HashMap<>();
    private final long[] provider={-1,-1,-1};
    private final List<Low> currentOffers=new ArrayList<>();
    private Low all;

    PriceRecords(JSONObject game,JSONObject record,JSONArray storeLows,JSONArray history){
        JSONObject periods=game.optJSONObject("periods");
        String[] keys={"all","y1","m3"};
        for(int i=0;i<keys.length;i++){
            JSONObject price=periods==null?null:periods.optJSONObject(keys[i]);
            if(PricingRegion.current.currency.equals(Market.text(price,"currency")))provider[i]=price.optLong("amountInt",-1);
        }
        addRecord(record);
        for(JSONObject row:Market.objects(storeLows))addRecord(row);
        for(JSONObject row:Market.objects(history)){
            JSONObject shop=row.optJSONObject("shop"),deal=row.optJSONObject("deal");
            String time=Market.text(row,"timestamp");
            try{
                Instant at=Instant.parse(time);
                long amount=deal==null?-1:Market.nativeAmount(shop,deal.optJSONObject("price"));
                Low value=amount<0?null:new Low(amount,Market.text(shop,"name"),time);
                // A withdrawal or currency change also ends the previous price interval.
                if(shop==null)continue;
                changes.computeIfAbsent(Market.shopId(shop),k->new TreeMap<>()).put(at,value);
                if(value!=null)observations.add(value);
            }catch(java.time.DateTimeException invalid){/* Cannot place this change on the timeline. */}
        }
        for(JSONObject offer:Market.offers(game,Market.ALL)){
            if(!offer.has("paise"))continue;
            String shop=Market.text(offer,"shop");

            long amount=offer.optLong("paise"),storeLow=offer.optLong("storeLow",-1);
            Low current=new Low(amount,shop,Market.text(offer,"updated"));
            observations.add(current);
            if(!offer.has("cachedAt"))currentOffers.add(current);
            if(storeLow>=0)consider(new Low(storeLow,shop,storeLow==amount?current.time:""));
            String[] fields={"allLow","yearLow","quarterLow"};
            for(int i=0;i<fields.length;i++)if(provider[i]<0)provider[i]=offer.optLong(fields[i],-1);
            if(amount==provider[0] || Market.historical(offer))consider(current);
        }
    }
    private void addRecord(JSONObject row){
        if(row==null)return;
        JSONObject shop=row.optJSONObject("shop"),deal=row.optJSONObject("deal");
        JSONObject price=(deal==null?row:deal).optJSONObject("price");
        long amount=Market.nativeAmount(shop,price);
        if(amount<0)return;
        Low value=new Low(amount,Market.text(shop,"name"),Market.text(row,"timestamp"));
        observations.add(value);
        consider(value);
    }
    private void consider(Low low){
        if(all==null || low.amount<all.amount || low.amount==all.amount && all.time.isEmpty())all=low;
    }
    Low period(int index){
        if(index==0)return confirmedLow(index,all);
        ZonedDateTime now=ZonedDateTime.now(ZoneOffset.UTC);
        Instant start=now.minusMonths(index==1?12:3).toInstant();
        Low result=null;
        for(NavigableMap<Instant,Low> timeline:changes.values()){
            Map.Entry<Instant,Low> active=timeline.floorEntry(start);
            if(active==null || active.getValue()==null || active.getValue().amount!=provider[index])continue;
            if(result==null || active.getKey().isAfter(Instant.parse(result.time)))result=active.getValue();
        }
        for(Low value:observations){
            // A dated native offer must match the published period low or a known all-time minimum.
            if(provider[index]>=0 && value.amount!=provider[index] && (all==null || all.amount!=value.amount))continue;
            try{
                Instant seen=Instant.parse(value.time);
                if(!seen.isBefore(start) && !seen.isAfter(now.toInstant()) && (result==null || value.amount<result.amount
                        || value.amount==result.amount && seen.isAfter(Instant.parse(result.time))))result=value;
            }catch(java.time.DateTimeException invalid){/* Undated records cannot establish period membership. */}
        }
        if(result==null && provider[index]>=0){
            for(Low current:currentOffers)if(current.amount==provider[index]){
                // The active offer corroborates the amount. Its update can predate this window.
                return new Low(current.amount,current.store,"");
            }
        }
        return confirmedLow(index,result);
    }
    private Low confirmedLow(int index,Low observed){
        if(observed!=null && provider[index]>=0 && provider[index]<observed.amount)return null;
        return observed;
    }
}

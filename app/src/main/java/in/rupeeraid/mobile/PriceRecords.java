package in.rupeeraid.mobile;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
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
        addRecord(record,true);
        for(JSONObject row:Market.objects(storeLows))addRecord(row,true);
        for(JSONObject row:Market.objects(history))addRecord(row,false);
        for(JSONObject offer:Market.offers(game,Market.ALL)){
            if(!offer.has("paise"))continue;
            String shop=Market.text(offer,"shop");
            if(Market.shopName(offer.optInt("shopId",-1)).isEmpty())continue;
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
    private void addRecord(JSONObject row,boolean storeMinimum){
        if(row==null)return;
        JSONObject shop=row.optJSONObject("shop"),deal=row.optJSONObject("deal");
        long amount=Market.nativeAmount(shop,(deal==null?row:deal).optJSONObject("price"));
        if(amount<0)return;
        Low value=new Low(amount,Market.text(shop,"name"),Market.text(row,"timestamp"));
        observations.add(value);
        if(storeMinimum)consider(value);
    }
    private void consider(Low low){
        if(all==null || low.amount<all.amount || low.amount==all.amount && all.time.isEmpty())all=low;
    }
    Low period(int index){
        if(index==0)return all;
        ZonedDateTime now=ZonedDateTime.now(ZoneOffset.UTC);
        Instant start=now.minusMonths(index==1?12:3).toInstant();
        Low result=null;
        for(Low value:observations){
            // A dated native offer must match the published period low or a known all-time minimum.
            if(value.amount!=provider[index] && (all==null || all.amount!=value.amount))continue;
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
        return result;
    }
}

package in.rupeeraid.mobile;

import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Cached, on-demand reads for a single game. All monetary endpoints are scoped to the selected country. */
final class GameCatalog {
    private final MobileApi api;
    private final MobileApi.Storage storage;
    GameCatalog(MobileApi api,MobileApi.Storage storage){this.api=api;this.storage=storage;}

    static String seriesQuery(String title){
        String base=title.split("[:：]",2)[0].replaceFirst("(?i)\\s+(?:[0-9]+|II|III|IV|VI|VII|VIII|IX)$","").trim();
        return base.isEmpty()?title:base;
    }
    static String words(String title){
        return title.toLowerCase(java.util.Locale.ROOT).replace('®',' ').replace('™',' ')
                .replaceAll("[^\\p{L}\\p{N}]+"," ").trim();
    }
    JSONObject load(JSONObject game,String section) throws Exception {
        String id=UUID.fromString(game.getString("id")).toString();
        String file="guide-"+id+"-"+section;
        String cached=storage.read(file);
        JSONObject prior=cached.isEmpty()?new JSONObject():new JSONObject(cached);
        long now=System.currentTimeMillis()/1000;
        String generation=api.generation();
        if(prior.has("data") && MobileApi.fresh(prior.optLong("saved"),now)
                && generation.equals(prior.optString("generation")))return prior;
        try{
            Object content=fetch(id,game.getString("title"),section);
            JSONObject result=new JSONObject().put("data",content).put("generation",generation).put("saved",System.currentTimeMillis()/1000);
            storage.write(file,result.toString());return result;
        }catch(Exception ex){
            String message=ex instanceof MobileApi.Failure?ex.getMessage():"Couldn’t reach IsThereAnyDeal. Try again when you’re online.";
            return prior.put("message",message).put("needsKey",ex instanceof MobileApi.Failure && ((MobileApi.Failure)ex).status==428);
        }
    }
    private Object fetch(String id,String title,String section) throws Exception {
        String gid="?id="+id,body=new JSONArray().put(id).toString();
        switch(section){
            case "about":return api.gameData("/games/info/v2"+gid,"GET",null);
            case "stats":return api.gameData("/stats/waitlist/v1"+gid+"&country="+PricingRegion.current.country,"GET",null);
            case "lows":return matching((JSONArray)api.gameData("/games/storelow/v2?country="+PricingRegion.current.country,"POST",body),id,"lows");
            case "record":{
                for(JSONObject row:Market.objects((JSONArray)api.gameData("/games/historylow/v1?country="+PricingRegion.current.country,"POST",body)))
                    if(id.equals(Market.text(row,"id")) && row.optJSONObject("low")!=null)return row.getJSONObject("low");
                return new JSONObject();
            }
            case "history":return api.gameData("/games/history/v2"+gid+"&country="+PricingRegion.current.country,"GET",null);
            case "historyYear":case "historyAll":{
                String since=section.equals("historyAll")?"1970-01-01T00:00:00Z":java.time.Instant.now()
                        .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).minus(365,java.time.temporal.ChronoUnit.DAYS).toString();
                return api.gameData("/games/history/v2"+gid+"&country="+PricingRegion.current.country+"&since="+MobileApi.encode(since),"GET",null);
            }
            case "bundles":return api.gameData("/games/bundles/v2"+gid+"&country="+PricingRegion.current.country+"&expired=true","GET",null);
            case "giveaways":return api.gameData("/games/giveaways/v1"+gid+"&expired=true","GET",null);
            case "subscriptions":return matching((JSONArray)api.gameData("/games/subs/v1?country="+PricingRegion.current.country,"POST",body),id,"subs");
            case "packs":case "series":{
                boolean packs=section.equals("packs");
                String term=packs?title:seriesQuery(title);
                JSONArray results=(JSONArray)api.gameData("/games/search/v1?results=100&title="+MobileApi.encode(term),"GET",null);
                JSONArray filtered=new JSONArray();
                for(JSONObject item:Market.objects(results)){
                    String type=Market.text(item,"type");
                    if(id.equals(Market.text(item,"id")) || !Market.pcEdition(item))continue;
                    if(!(" "+words(Market.text(item,"title"))+" ").contains(" "+words(term)+" "))continue;
                    if(packs?(type.equals("dlc") || type.equals("package")):type.equals("game"))filtered.put(item);
                }
                if(packs && filtered.length()>0){
                    try{return api.priceCatalog(filtered,storage.read("personalKey"));}
                    catch(java.io.IOException unavailable){/* Keep matching titles if their quotes are temporarily unavailable. */}
                }
                return filtered;
            }
            default:throw new IllegalArgumentException("Unknown game section");
        }
    }
    private static JSONArray matching(JSONArray rows,String id,String field){
        for(JSONObject row:Market.objects(rows))if(id.equals(Market.text(row,"id")) && row.optJSONArray(field)!=null)return row.optJSONArray(field);
        return new JSONArray();
    }
}

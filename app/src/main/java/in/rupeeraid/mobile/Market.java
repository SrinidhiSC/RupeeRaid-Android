package in.rupeeraid.mobile;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** The same eligibility rules drive each list, carousel and game detail screen. */
final class Market {
    // ITAD shop IDs are stable across locales. See the storefront sources in the Android README.
    static final int[] INR_SHOPS = {61,16,36,48};
    static String shopQuery(){
        List<String> ids=new ArrayList<>();for(int id:INR_SHOPS)ids.add(String.valueOf(id));return String.join(",",ids);
    }
    static String shopName(int id){
        switch(id){case 61:return "Steam";case 16:return "Epic Games Store";case 36:return "Green Man Gaming";case 48:return "Microsoft Store";default:return "";}
    }
    static long nativeAmount(JSONObject shop,JSONObject price){
        if(shop==null || ("IN".equals(PricingRegion.current.country) && !nativeInr(shop)) || price==null || !PricingRegion.current.currency.equals(text(price,"currency")))return -1;
        return price.optLong("amountInt",-1);
    }
    static boolean contains(JSONArray games,String id){
        for(JSONObject g:objects(games))if(id.equals(text(g,"id")))return true;
        return false;
    }
    static final String ALL = "All";
    private static final java.util.regex.Pattern CONSOLE_EDITION = java.util.regex.Pattern.compile(
            "(?i)\\b(?:xbox(?:\\s*(?:360|one|series))?|playstation(?:\\s*[1-5])?|ps[1-5]|nintendo switch)\\b");
    static final String NO_PRICE = "Regional pricing unavailable.";

    static String text(JSONObject object, String field) {
        if (object == null || object.isNull(field)) return "";
        String value = object.optString(field).trim();
        return "null".equalsIgnoreCase(value) ? "" : value;
    }

    static String accountName(JSONObject data) {
        String name = text(data, "name");
        return name.isEmpty() ? "IsThereAnyDeal member" : name;
    }

    static boolean nativeInr(JSONObject shop) {
        int id=shopId(shop);for(int known:INR_SHOPS)if(id==known)return true;
        return false;
    }
    static int shopId(JSONObject shop){
        int id=shop.optInt("id",-1);if(id>=0)return id;
        String name = text(shop,"name").toLowerCase(Locale.ROOT).replace(" ","");
        switch(name){
            case "steam":return 61;case "greenmangaming":return 36;case "epicgamestore":case "epicgamesstore":return 16;
            case "microsoftstore":return 48;default:return -1;
        }
    }

    static String art(JSONObject game, String... sizes) {
        JSONObject assets = game.optJSONObject("assets");
        for (String size : sizes) {
            String value = text(assets,size);
            try {
                URI uri = URI.create(value);
                if ("https".equals(uri.getScheme()) && ("assets.isthereanydeal.com".equals(uri.getHost()) || "cdn.isthereanydeal.com".equals(uri.getHost()))
                        && uri.getUserInfo() == null && uri.getPort() == -1) return value;
            } catch (IllegalArgumentException ignored) { }
        }
        return "";
    }

    static String platform(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (key.contains("xbox")) return "Xbox";
        if (key.contains("playstation") || key.matches("ps[345]")) return "PlayStation";
        if (key.contains("switch")) return "Switch";
        if (key.contains("windows") || key.contains("linux") || key.contains("mac") || key.equals("pc")
                || key.equals("steam deck") || key.equals("htc vive") || key.equals("oculus")) return "PC";
        return key.trim().isEmpty()?"":"Other";
    }

    static boolean supports(JSONObject offer, String platform) {
        if (ALL.equals(platform)) return pcOffer(offer);
        JSONArray systems = offer.optJSONArray("platforms");
        if (systems != null) for (int i=0; i<systems.length(); i++) {
            if (platform.equals(platform(text(systems.optJSONObject(i),"name")))) return true;
        }
        return false;
    }

    static boolean pcOffer(JSONObject offer) {
        JSONArray systems=offer.optJSONArray("platforms");
        return systems==null || systems.length()==0 || supports(offer,"PC");
    }

    static boolean pcEdition(JSONObject game) {
        String title=text(game,"title");
        if(CONSOLE_EDITION.matcher(title).find() && !title.matches("(?i).*\\b(?:PC|Windows)\\b.*"))return false;
        JSONArray offers=game.optJSONArray("offers");
        if(offers==null || offers.length()==0)return true;
        for(JSONObject offer:objects(offers))if(pcOffer(offer))return true;
        return false;
    }

    static boolean active(JSONObject offer, long now) {
        long ends = offer.optLong("expiry");
        return ends == 0 || ends > now;
    }

    static List<JSONObject> offers(JSONObject game, String platform) {
        List<JSONObject> result = new ArrayList<>();
        long now = System.currentTimeMillis()/1000;
        for (JSONObject offer : objects(game.optJSONArray("offers"))) {
            if (active(offer, now) && supports(offer, platform)) result.add(offer);
        }
        result.sort(Comparator.comparingLong(o -> o.optLong("paise",Long.MAX_VALUE)));
        return result;
    }

    static JSONObject best(JSONObject game, String platform) {
        JSONObject lowest = null;
        JSONArray offers = game.optJSONArray("offers");
        long now = System.currentTimeMillis()/1000;
        if (offers != null) for (int i=0; i<offers.length(); i++) {
            JSONObject row = offers.optJSONObject(i);
            if (row == null || row.isNull("paise") || !active(row,now) || !supports(row,platform)) continue;
            if (lowest == null || row.optLong("paise") < lowest.optLong("paise")) lowest = row;
        }
        return lowest;
    }

    static boolean historical(JSONObject offer) {
        return offer != null && offer.has("paise") && ("H".equals(text(offer,"flag")) || "N".equals(text(offer,"flag")));
    }
    static boolean storeLow(JSONObject offer) {
        if(offer==null || offer.optLong("paise",-1)<0 || !active(offer,System.currentTimeMillis()/1000))return false;
        long recorded=offer.optLong("storeLow",-1);
        return "S".equals(text(offer,"flag")) || recorded>=0 && offer.optLong("paise")<=recorded;
    }
    enum LowPeriod {
        ALL_TIME("HL","All-time low"), YEAR("1Y","One-year low"), QUARTER("3M","Three-month low"), NONE("","");
        final String badge,description;
        LowPeriod(String badge,String description){this.badge=badge;this.description=description;}
    }
    static LowPeriod lowPeriod(JSONObject offer){
        if(offer==null || offer.optLong("paise",-1)<0 || !active(offer,System.currentTimeMillis()/1000))return LowPeriod.NONE;
        long amount=offer.optLong("paise");
        if(amount>offer.optLong("currentFloor",Long.MAX_VALUE))return LowPeriod.NONE;
        long year=offer.optLong("yearLow",-1),quarter=offer.optLong("quarterLow",-1);
        if((year<0 || amount<=year) && (quarter<0 || amount<=quarter) && amount<=offer.optLong("storeLow",Long.MAX_VALUE)
                && (historical(offer) || amount==offer.optLong("allLow",-1)))return LowPeriod.ALL_TIME;
        if(amount==year && (quarter<0 || amount<=quarter))return LowPeriod.YEAR;
        return amount==quarter?LowPeriod.QUARTER:LowPeriod.NONE;
    }

    static List<JSONObject> objects(JSONArray array) {
        if (array == null) return Collections.emptyList();
        List<JSONObject> result = new ArrayList<>(array.length());
        for (int i=0; i<array.length(); i++) {
            JSONObject row = array.optJSONObject(i);
            if (row != null) result.add(row);
        }
        return result;
    }

    static List<JSONObject> rows(JSONObject data, String section, String platform, String query, String order) {
        return rows(data,section,platform,query,order,false);
    }
    static List<JSONObject> rows(JSONObject data,String section,String platform,String query,String order,boolean hideOwned) {
        Map<String,JSONObject> unique = new LinkedHashMap<>();
        String[] sources;
        switch (section) {
            case "search": sources = new String[]{"results","wishlist","trending","deals","lows","best","free","library","ignored"}; break;
            case "budget":case "best": case "free": sources = new String[]{section,"deals","trending","lows","best"}; break;
            default: sources = new String[]{section};
        }
        String term = query.trim().toLowerCase(Locale.ROOT);
        Set<String> hidden=new HashSet<>();
        if(!section.equals("search") && !section.equals("wishlist") && !section.equals("library") && !section.equals("ignored")){
            for(JSONObject g:objects(data.optJSONArray("ignored")))hidden.add(text(g,"id"));
            if(hideOwned)for(JSONObject g:objects(data.optJSONArray("library")))hidden.add(text(g,"id"));
        }
        for (String source : sources) for (JSONObject game : objects(data.optJSONArray(source))) {
            if (!text(game,"title").toLowerCase(Locale.ROOT).contains(term)) continue;
            if(hidden.contains(text(game,"id")) || !pcEdition(game))continue;
            if (!ALL.equals(platform) && offers(game,platform).isEmpty()) continue;
            JSONObject quote = best(game,platform);
            if("budget".equals(section) && (quote==null || quote.optLong("paise",-1)<0 || quote.optLong("paise")>=30000))continue;
            if ("free".equals(section) && (quote == null || quote.optLong("paise") != 0)) continue;
            if ("lows".equals(section) && !historical(quote)) continue;
            if ("best".equals(section) && (quote == null || quote.optInt("cut") == 0)) continue;
            String id=text(game,"id");
            JSONObject existing=unique.get(id);
            if(existing==null || best(existing,platform)==null && quote!=null)unique.put(id,game);
        }
        List<JSONObject> rows = new ArrayList<>(unique.values());
        if ("search".equals(section) && "Default".equals(order)) rows.sort(Comparator.comparing(g->best(g,platform)==null));
        if ("deals".equals(section) && "Default".equals(order)) {
            rows.sort(Comparator.comparingLong(g->{
                JSONObject offer = best(g,platform);
                return offer == null ? 0 : -offer.optLong("reportedAt");
            }));
        }
        if ("Title".equals(order)) rows.sort(Comparator.comparing(g->text(g,"title"),String.CASE_INSENSITIVE_ORDER));
        if ("Price".equals(order)) rows.sort(Comparator.comparingLong(g->{
            JSONObject offer = best(g,platform);
            return offer == null ? Long.MAX_VALUE : offer.optLong("paise");
        }));
        if ("Discount".equals(order) || ("best".equals(section) && "Default".equals(order))) {
            rows.sort(Comparator.comparingInt(g->{
                JSONObject offer = best(g,platform);
                return offer == null ? 1 : -offer.optInt("cut");
            }));
        }
        return rows;
    }

    private Market() {}
}

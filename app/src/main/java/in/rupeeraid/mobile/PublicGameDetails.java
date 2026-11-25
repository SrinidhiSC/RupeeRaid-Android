package in.rupeeraid.mobile;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import org.json.*;

/** Supplemental fields published on the public game pages. No account credentials travel with these requests. */
final class PublicGameDetails {
    private static final Pattern PAGE = Pattern.compile("(?m)^\\s*var page\\s*=\\s*(\\[.*\\]);\\s*$");
    private static final Pattern CONTEXT = Pattern.compile("(?m)^\\s*var g\\s*=\\s*(\\{.*\\});\\s*$");
    private static final Pattern PURCHASE = Pattern.compile("(?is)<h2\\b[^>]*\\bid=\"(bundle_purchase_label_|game_area_purchase_section_add_to_cart_title_)(\\d+)\"[^>]*>\\s*Buy\\s+([^<]+)");

    static JSONObject editorial(String id,String slug) throws Exception {
        if(!slug.matches("[a-z0-9][a-z0-9-]*"))throw new IOException("Missing game slug");
        return parseEditorial(id,read("https://isthereanydeal.com/game/"+slug+"/info/"));
    }

    static JSONObject parseEditorial(String id,String html) throws Exception {
        Matcher match=PAGE.matcher(html);
        if(!match.find())throw new IOException("Game metadata unavailable");
        JSONObject page=new JSONArray(match.group(1)).getJSONObject(1);
        if(!id.equals(page.getJSONObject("game").getString("id")))throw new IOException("Game mismatch");
        JSONObject detail=page.getJSONObject("detail");
        return new JSONObject().put("summary",detail.optJSONObject("summary")).put("hltb",detail.optJSONObject("hltb"));
    }

    static JSONArray relations(String id,String slug) throws Exception {
        if(!slug.matches("[a-z0-9][a-z0-9-]*"))throw new IOException("Missing game slug");
        CookieManager guest=new CookieManager(null,CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        String page=read(URI.create("https://isthereanydeal.com/game/"+slug+"/info/"),guest,null,null);
        Matcher game=PAGE.matcher(page),context=CONTEXT.matcher(page);
        if(!game.find() || !id.equals(new JSONArray(game.group(1)).getJSONObject(1).getJSONObject("game").getString("id"))
                || !context.find())throw new IOException("Game context unavailable");
        String token=new JSONObject(context.group(1)).getJSONObject("user").getString("token");
        String response=read(URI.create("https://isthereanydeal.com/api/game/info/"),guest,token,new JSONObject().put("gid",id).toString());
        return relatedGames(new JSONObject(response).getJSONObject("games"));
    }

    static JSONArray relatedGames(JSONObject games) throws Exception {
        JSONArray rows=new JSONArray();
        for(String field:new String[]{"packages","dlcs"}){
            if(!games.has(field))throw new IOException("Missing game relationships");
            if(games.isNull(field))continue;
            JSONArray items=games.getJSONArray(field);
            for(int i=0;i<items.length();i++){
                JSONObject item=items.getJSONObject(i);
                rows.put(new JSONObject().put("id",UUID.fromString(item.getString("id")).toString())
                        .put("title",item.getString("title")).put("slug",item.opt("slug")).put("assets",item.optJSONObject("assets"))
                        .put("type",field.equals("packages")?"package":"dlc"));
            }
        }
        return rows;
    }

    static JSONArray packages(int appid,String country) throws Exception {
        if(appid<=0 || !country.matches("[A-Z]{2}"))throw new IOException("Missing Steam app or region");
        return parsePackages(appid,read("https://store.steampowered.com/app/"+appid+"/?cc="+country+"&l=english"));
    }

    static JSONArray parsePackages(int appid,String html) throws Exception {
        if(!html.contains("game_area_purchase") && !html.contains("bundle_purchase_label_"))
            throw new IOException("Steam purchase options unavailable");
        JSONArray rows=new JSONArray();
        Set<String> seen=new HashSet<>();
        Matcher item=PURCHASE.matcher(html);
        while(item.find()){
            String number=item.group(2);
            boolean bundle=item.group(1).startsWith("bundle");
            // Ordinary Steam subs can represent just the base game. Require an explicit contents list.
            if(!bundle && !html.contains("id=\"package-item-"+number+"-"+appid+"\""))continue;
            String path=(bundle?"bundle/":"sub/")+number;
            if(seen.add(path))rows.put(new JSONObject().put("steamPath",path).put("type","package")
                    .put("title",decode(item.group(3).trim())));
        }
        return rows;
    }

    private static String decode(String text){
        return text.replace("&quot;","\"").replace("&#039;","'").replace("&#39;","'").replace("&apos;","'")
                .replace("&lt;","<").replace("&gt;",">").replace("&amp;","&");
    }

    private static String read(String address) throws IOException {
        return read(URI.create(address),null,null,null);
    }
    private static String read(URI uri,CookieManager guest,String token,String body) throws IOException {
        HttpURLConnection conn=(HttpURLConnection)uri.toURL().openConnection();
        conn.setRequestProperty("User-Agent","Mozilla/5.0 (compatible; RupeeRaid Android)");
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(6500);conn.setReadTimeout(9000);
        try{
            if(body!=null){
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Accept","application/json");
                conn.setRequestProperty("Content-Type","application/json");
                conn.setRequestProperty("ITAD-SessionToken",token);
                for(Map.Entry<String,List<String>> header:guest.get(uri,Collections.emptyMap()).entrySet())
                    conn.setRequestProperty(header.getKey(),String.join("; ",header.getValue()));
                conn.setDoOutput(true);
                try(OutputStream out=conn.getOutputStream()){out.write(body.getBytes(StandardCharsets.UTF_8));}
            }
            if(conn.getResponseCode()!=200)throw new IOException("Game page unavailable");
            if(guest!=null)guest.put(uri,conn.getHeaderFields());
            try(InputStream stream=conn.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                byte[] buf=new byte[8192];int n;
                while((n=stream.read(buf))!=-1){
                    if(bytes.size()+n>3*1024*1024)throw new IOException("Page too large");
                    bytes.write(buf,0,n);
                }
                return bytes.toString(StandardCharsets.UTF_8.name());
            }
        }finally{conn.disconnect();}
    }
}

package in.rupeeraid.mobile;

import org.json.JSONObject;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Normalize once per game and snapshot. The original title remains the display title. */
final class TitleIndex {
    private static final Pattern MARKS=Pattern.compile("\\p{M}+"), PUNCT=Pattern.compile("[^\\p{L}\\p{N}]+");
    static String words(String raw){
        String plain=MARKS.matcher(Normalizer.normalize(raw,Normalizer.Form.NFD)).replaceAll("");
        return PUNCT.matcher(plain.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }
    static final class Entry{
        final JSONObject game;
        final String title,initials;
        final String[] tokens;
        Entry(JSONObject game){
            this.game=game;title=words(Market.text(game,"title"));tokens=title.split(" ");
            StringBuilder shortName=new StringBuilder();
            for(String token:tokens)if(!token.isEmpty())shortName.append(token.charAt(0));
            initials=shortName.toString();
        }
        int score(String q,String[] terms,String compact){
            if(title.equals(q))return 0;
            if(title.startsWith(q))return 1;
            if(initials.startsWith(compact) && compact.length()>1)return 2;
            boolean prefixes=true;
            for(String term:terms){
                boolean found=false;
                for(String token:tokens)if(token.startsWith(term)){found=true;break;}
                if(!found){prefixes=false;break;}
            }
            if(prefixes)return 3;
            return title.contains(q)?4:5;
        }
    }
    private final List<Entry> entries=new ArrayList<>();
    TitleIndex(JSONObject snapshot){
        for(JSONObject game:Market.rows(snapshot,"search",Market.ALL,"","Default"))entries.add(new Entry(game));
    }
    List<JSONObject> suggestions(String input){
        String q=words(input),compact=q.replace(" ","");String[] terms=q.split(" ");
        List<List<JSONObject>> buckets=new ArrayList<>();
        for(int i=0;i<5;i++)buckets.add(new ArrayList<>());
        for(Entry e:entries){
            int score=q.isEmpty()?0:e.score(q,terms,compact);
            if(score<5)buckets.get(score).add(e.game);
        }
        List<JSONObject> result=new ArrayList<>();for(List<JSONObject> bucket:buckets)result.addAll(bucket);
        return result;
    }
}

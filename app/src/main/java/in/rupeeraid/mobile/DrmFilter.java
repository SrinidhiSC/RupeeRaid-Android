package in.rupeeraid.mobile;

import android.app.AlertDialog;
import android.content.Context;
import java.util.*;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

final class DrmFilter {
    private static final String[] OPTIONS={"Steam","Microsoft Store","Epic","GOG","DRM Free"};
    private static final String KEY="offerDrm";

    static Set<String> saved(Context context){
        return new LinkedHashSet<>(context.getSharedPreferences("appearance",0).getStringSet(KEY,Collections.emptySet()));
    }
    static void save(Context context,Set<String> values){
        context.getSharedPreferences("appearance",0).edit().putStringSet(KEY,new LinkedHashSet<>(values)).apply();
    }
    static String label(Set<String> selected){
        if(selected.isEmpty())return "Any";
        return selected.size()==1?selected.iterator().next():selected.size()+" selected";
    }
    static void choose(Context context,Set<String> selected,Consumer<Set<String>> apply){
        Set<String> pending=new LinkedHashSet<>(selected);
        boolean[] checked=new boolean[OPTIONS.length];
        for(int i=0;i<OPTIONS.length;i++)checked[i]=pending.contains(OPTIONS[i]);
        new AlertDialog.Builder(context).setTitle("Filter by DRM").setMultiChoiceItems(OPTIONS,checked,(d,i,on)->{
            if(on)pending.add(OPTIONS[i]);else pending.remove(OPTIONS[i]);
        }).setPositiveButton("Apply",(d,i)->apply.accept(pending))
          .setNeutralButton("Any DRM",(d,i)->apply.accept(Collections.emptySet())).setNegativeButton("Cancel",null).show();
    }
    static boolean matches(JSONObject offer,Set<String> selected){
        if(selected.isEmpty())return true;
        JSONArray drm=offer.optJSONArray("drm");
        if(drm!=null && drm.length()>0){
            for(int i=0;i<drm.length();i++){
                Object entry=drm.opt(i);
                String name=entry instanceof JSONObject?Market.text((JSONObject)entry,"name"):drm.optString(i);
                if(selected.contains(drmName(name)))return true;
            }
            return false;
        }
        return selected.contains(drmName(Market.text(offer,"shop")));
    }
    private static String drmName(String name){
        switch(GameCatalog.words(name)){
            case "steam":return "Steam";
            case "gog":case "gog com":return "GOG";
            case "epic":case "epic game store":case "epic games store":return "Epic";
            case "microsoft store":case "windows store":return "Microsoft Store";
            case "drm free":return "DRM Free";
            default:return "";
        }
    }
}

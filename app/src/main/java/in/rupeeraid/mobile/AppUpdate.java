package in.rupeeraid.mobile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

final class AppUpdate {
    static final String RELEASES="https://github.com/SrinidhiSC/RupeeRaid-Android/releases";
    private static final String LATEST="https://api.github.com/repos/SrinidhiSC/RupeeRaid-Android/releases/latest";

    static int compare(String tag,String installed){
        String[] remote=parts(tag),local=parts(installed);
        for(int i=0;i<Math.max(remote.length,local.length);i++){
            int a=i<remote.length?Integer.parseInt(remote[i]):0;
            int b=i<local.length?Integer.parseInt(local[i]):0;
            if(a!=b)return Integer.compare(a,b);
        }
        return 0;
    }
    private static String[] parts(String version){
        if(!version.matches("v?[0-9]{1,9}(?:\\.[0-9]{1,9}){0,3}"))throw new IllegalArgumentException("Invalid release version");
        return version.replaceFirst("^v","").split("\\.");
    }
    static String latestTag() throws Exception {
        HttpURLConnection conn=(HttpURLConnection)new URL(LATEST).openConnection();
        try{
            conn.setConnectTimeout(6000);conn.setReadTimeout(8000);
            conn.setRequestProperty("Accept","application/vnd.github+json");
            conn.setRequestProperty("User-Agent","RupeeRaid-Android/"+BuildConfig.VERSION_NAME);
            int status=conn.getResponseCode();
            if(status==404)throw new IOException("No public GitHub release is available yet.");
            if(status==403 || status==429)throw new IOException("GitHub limited this request. Try again later.");
            if(status!=200)throw new IOException("GitHub could not check for updates. Try again later.");
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(conn.getInputStream(),StandardCharsets.UTF_8))){
                StringBuilder json=new StringBuilder();String line;
                while((line=reader.readLine())!=null)json.append(line);
                return new JSONObject(json.toString()).getString("tag_name");
            }
        }finally{conn.disconnect();}
    }
}

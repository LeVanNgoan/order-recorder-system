package vn.orderrecorder.shopee;

import android.content.Context;
import android.content.SharedPreferences;

public final class HubPrefs {
    private static final String FILE="hub_prefs";
    private static final String URL="hub_url";
    private static final String KEY="hub_key";
    private static final String HUB_ID="hub_id";
    private static final String LAST_OK="hub_last_ok";
    private static final String LAST_ERROR="hub_last_error";
    private static final String LAST_DISCOVERY="hub_last_discovery";
    private static final String LAST_DISCOVERY_METHOD="hub_last_discovery_method";
    private HubPrefs() {}
    private static SharedPreferences p(Context c){return c.getSharedPreferences(FILE,Context.MODE_PRIVATE);}

    public static String getUrl(Context c){return p(c).getString(URL,"");}
    public static String getKey(Context c){return p(c).getString(KEY,"");}
    public static String getHubId(Context c){return p(c).getString(HUB_ID,"");}
    public static boolean hasUrl(Context c){return !getUrl(c).isEmpty();}
    public static boolean hasKey(Context c){return !getKey(c).isEmpty();}
    public static boolean isConfigured(Context c){return hasUrl(c)&&hasKey(c);}

    /** Manual configuration. Key is preserved exactly as entered after trim. */
    public static void save(Context c,String url,String key){
        p(c).edit()
                .putString(URL,normalizeUrl(url))
                .putString(KEY,key==null?"":key.trim())
                .remove(HUB_ID)
                .remove(LAST_DISCOVERY)
                .remove(LAST_DISCOVERY_METHOD)
                .remove(LAST_ERROR)
                .apply();
    }

    /** Called only after HubDiscovery authenticated the candidate with the existing API key. */
    public static void saveDiscoveredUrl(Context c,String url,String hubId){
        SharedPreferences.Editor e=p(c).edit()
                .putString(URL,normalizeUrl(url))
                .putLong(LAST_DISCOVERY,System.currentTimeMillis())
                .remove(LAST_ERROR);
        if(hubId!=null&&!hubId.trim().isEmpty())e.putString(HUB_ID,hubId.trim());
        e.apply();
    }

    public static void markDiscoveryMethod(Context c,String method){
        p(c).edit().putString(LAST_DISCOVERY_METHOD,method==null?"":method).apply();
    }
    public static long lastDiscovery(Context c){return p(c).getLong(LAST_DISCOVERY,0L);}
    public static String lastDiscoveryMethod(Context c){return p(c).getString(LAST_DISCOVERY_METHOD,"");}
    public static void clear(Context c){p(c).edit().clear().apply();}
    public static void markOk(Context c){p(c).edit().putLong(LAST_OK,System.currentTimeMillis()).remove(LAST_ERROR).apply();}
    public static void markError(Context c,String e){p(c).edit().putString(LAST_ERROR,e==null?"":e).apply();}
    public static long lastOk(Context c){return p(c).getLong(LAST_OK,0L);}
    public static String lastError(Context c){return p(c).getString(LAST_ERROR,"");}

    public static String normalizeUrl(String raw){
        String s=raw==null?"":raw.trim();
        if(s.isEmpty())return "";
        while(s.endsWith("/"))s=s.substring(0,s.length()-1);
        if(!s.startsWith("http://")&&!s.startsWith("https://"))s="http://"+s;
        String after=s.substring(s.indexOf("://")+3);
        // Current deployment is IPv4. Avoid appending the default port only when a port is already present.
        if(!after.contains(":"))s=s+":17891";
        return s;
    }
}

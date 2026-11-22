package in.rupeeraid.mobile;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import java.util.Arrays;

final class LauncherIcons {
    private static final String[] ALIASES = {"Bolt","Shield","Pixel","Neon","Pitch"};

    static void select(Context context,String logo) {
        int selected = Math.max(0, Arrays.asList(MarketWidgets.LOGOS).indexOf(logo));
        PackageManager manager = context.getPackageManager();
        // Enable the new entry first so the app always has a launcher entry.
        update(context,manager,selected,true);
        for(int i=0;i<ALIASES.length;i++) if(i!=selected) update(context,manager,i,false);
    }
    private static void update(Context context,PackageManager manager,int index,boolean enabled) {
        ComponentName name = new ComponentName(context,context.getPackageName()+".Launcher"+ALIASES[index]);
        int state = manager.getComponentEnabledSetting(name);
        boolean current = state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ? index==0 :
                state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        if(current!=enabled)manager.setComponentEnabledSetting(name,enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED :
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,PackageManager.DONT_KILL_APP);
    }
}

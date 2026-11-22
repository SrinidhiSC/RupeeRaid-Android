package in.rupeeraid.mobile;

import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import static in.rupeeraid.mobile.MarketWidgets.*;

final class AlertEditor {
    static void open(MainActivity a,JSONObject game){
        MarketWidgets w=new MarketWidgets(a);String id=Market.text(game,"id");
        JSONObject active=a.gameRule(id);
        String current=active==null?PriceAlerts.LOW:active.optString("mode");
        ScrollView scroll=new ScrollView(a);LinearLayout form=w.column();w.pad(form,22,8,22,8);scroll.addView(form);
        form.addView(w.text(Market.text(game,"title"),16,LILAC,true));
        RadioGroup modes=new RadioGroup(a);form.addView(modes);
        String[][] choices={{PriceAlerts.LOW,"All-time historical low"},{PriceAlerts.YEAR_LOW,"One-year low or better"},
                {PriceAlerts.QUARTER_LOW,"Three-month low or better"},{PriceAlerts.STORE_LOW,"A selected store’s own historical low"},
                {PriceAlerts.DROP,"Any price drop"},{PriceAlerts.TARGET,"At or below a target price"}};
        for(String[] choice:choices){
            RadioButton radio=new RadioButton(a);radio.setId(View.generateViewId());radio.setTag(choice[0]);radio.setText(choice[1]);
            modes.addView(radio);if(choice[0].equals(current))modes.check(radio.getId());
        }
        EditText target=new EditText(a);target.setSingleLine();target.setHint("Target in "+PricingRegion.current.currency);target.setContentDescription("Target price in "+PricingRegion.current.currency);
        target.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if(active!=null && active.optLong("target",-1)>=0)target.setText(BigDecimal.valueOf(active.optLong("target"),2).stripTrailingZeros().toPlainString());
        form.addView(target);target.setVisibility(PriceAlerts.TARGET.equals(current)?View.VISIBLE:View.GONE);
        modes.setOnCheckedChangeListener((group,checked)->target.setVisibility(PriceAlerts.TARGET.equals(group.findViewById(checked).getTag())?View.VISIBLE:View.GONE));
        TextView heading=w.text("Stores to monitor",16,WHITE,true);w.pad(heading,0,12,0,4);form.addView(heading);
        Map<Integer,CheckBox> shops=new LinkedHashMap<>();
        for(int shop:Market.INR_SHOPS){
            CheckBox box=new CheckBox(a);box.setText(Market.shopName(shop));box.setChecked(active==null || PriceAlerts.includes(active.optJSONArray("shops"),shop));
            shops.put(shop,box);form.addView(box);
        }
        boolean add=a.needsWaitlist(game);
        TextView hint=w.text((add?"Saving also adds this game to your ITAD Wishlist. ":"")+"The first price from each store starts tracking. Android checks periodically while online.",13,MUTED,false);
        w.pad(hint,0,7,0,4);form.addView(hint);
        TextView error=w.text("",14,0xffffa8a8,false);error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);form.addView(error);
        AlertDialog.Builder builder=new AlertDialog.Builder(a).setTitle("Price alert").setView(scroll).setNegativeButton("Cancel",null)
                .setPositiveButton(add?"Add & save":"Save alert",null);
        if(active!=null)builder.setNeutralButton("Turn off",(d,i)->a.disableAlert(id));
        AlertDialog dialog=builder.create();dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String mode=(String)modes.findViewById(modes.getCheckedRadioButtonId()).getTag();
            JSONArray ids=new JSONArray();for(Map.Entry<Integer,CheckBox> item:shops.entrySet())if(item.getValue().isChecked())ids.put(item.getKey().intValue());
            if(ids.length()==0){error.setText("Select at least one store.");return;}
            long price=-1;
            if(PriceAlerts.TARGET.equals(mode)){
                try{price=new BigDecimal(target.getText().toString().trim()).movePointRight(2).longValueExact();if(price<0)throw new NumberFormatException();}
                catch(Exception ex){target.setError("Enter a price with up to two decimal places.");return;}
            }
            if(a.busy){error.setText("Wait for the current change to finish.");return;}
            dialog.dismiss();a.saveAlert(game,mode,ids,price);
        });
    }
    static void openUniversal(MainActivity a){
        MarketWidgets w=new MarketWidgets(a);JSONObject active=a.alertState.optJSONObject("universal");
        String current=active==null?PriceAlerts.LOW:active.optString("mode",PriceAlerts.LOW);
        ScrollView scroll=new ScrollView(a);LinearLayout form=w.column();w.pad(form,22,8,22,8);scroll.addView(form);
        form.addView(w.text("All wishlist games",18,WHITE,true));
        TextView intro=w.text("Choose one rule for every game in your Wishlist. A game-specific rule can still replace it.",13,MUTED,false);
        w.pad(intro,0,5,0,8);form.addView(intro);
        RadioGroup modes=new RadioGroup(a);form.addView(modes);
        String[][] choices={{PriceAlerts.LOW,"All-time historical low"},{PriceAlerts.YEAR_LOW,"One-year low or better"},
                {PriceAlerts.QUARTER_LOW,"Three-month low or better"},{PriceAlerts.STORE_LOW,"A selected store’s own historical low"},
                {PriceAlerts.DROP,"Any price drop"}};
        for(String[] choice:choices){
            RadioButton radio=new RadioButton(a);radio.setId(View.generateViewId());radio.setTag(choice[0]);radio.setText(choice[1]);
            modes.addView(radio);if(choice[0].equals(current))modes.check(radio.getId());
        }
        TextView heading=w.text("Stores to monitor",16,WHITE,true);w.pad(heading,0,12,0,4);form.addView(heading);
        Map<Integer,CheckBox> shops=new LinkedHashMap<>();
        for(int shop:Market.INR_SHOPS){
            CheckBox box=new CheckBox(a);box.setText(Market.shopName(shop));
            box.setChecked(active==null || PriceAlerts.includes(active.optJSONArray("shops"),shop));shops.put(shop,box);form.addView(box);
        }
        TextView error=w.text("",14,0xffffa8a8,false);error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);form.addView(error);
        AlertDialog.Builder builder=new AlertDialog.Builder(a).setTitle("Wishlist-wide alerts").setView(scroll).setNegativeButton("Cancel",null)
                .setPositiveButton("Apply to wishlist",null);
        if(active!=null)builder.setNeutralButton("Turn off",(d,i)->a.disableUniversalAlert());
        AlertDialog dialog=builder.create();dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String mode=(String)modes.findViewById(modes.getCheckedRadioButtonId()).getTag();
            JSONArray ids=new JSONArray();for(Map.Entry<Integer,CheckBox> item:shops.entrySet())if(item.getValue().isChecked())ids.put(item.getKey().intValue());
            if(ids.length()==0){error.setText("Select at least one store.");return;}
            if(a.busy){error.setText("Wait for the current change to finish.");return;}
            dialog.dismiss();a.saveUniversalAlert(mode,ids);
        });
    }
    private AlertEditor(){}
}

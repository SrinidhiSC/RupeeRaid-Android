package in.rupeeraid.mobile;

import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.json.JSONObject;
import static in.rupeeraid.mobile.MarketWidgets.*;

/** ListView recycles game rows. Only section controls and the small carousel are retained. */
final class DealFeed extends BaseAdapter {
    private final MarketWidgets w;
    private final Consumer<JSONObject> open;
    private final List<Object> items=new ArrayList<>();
    private final String platform;
    private final java.util.Set<String> drm;
    private final java.util.Map<JSONObject,RowData> rendered=new java.util.IdentityHashMap<>();
    private static final class RowData{
        final JSONObject offer;
        final String art,thumbnail,price,original;
        final Market.LowPeriod period;
        RowData(JSONObject game,String platform,java.util.Set<String> drm){
            art=Market.art(game,"banner300","banner145","banner400","boxart");
            thumbnail=Market.art(game,"banner145");
            offer=Market.best(game,platform,drm);period=Market.lowPeriod(offer);
            price=offer==null?"—":money(offer.optLong("paise"));
            original=offer!=null && offer.has("regular")?"Original "+money(offer.optLong("regular")):"Original price unavailable";
        }
    }
    private final java.util.Set<Integer> wishlistRows=new java.util.HashSet<>();
    private final java.util.function.Predicate<JSONObject> alertEnabled;
    private final Consumer<JSONObject> editAlert;

    DealFeed(MarketWidgets widgets,String platform,Consumer<JSONObject> open) {
        this(widgets,platform,open,g->false,g->{});
    }
    DealFeed(MarketWidgets widgets,String platform,Consumer<JSONObject> open,
             java.util.function.Predicate<JSONObject> alertEnabled, Consumer<JSONObject> editAlert) {
        this.drm=DrmFilter.saved(widgets.context);this.w=widgets;this.platform=platform;this.open=open;this.alertEnabled=alertEnabled;this.editAlert=editAlert;
    }
    void add(View header) { items.add(header); }
    void addLibrary(List<JSONObject> games){
        for(int i=0;i<games.size();i+=2)items.add(games.subList(i,Math.min(i+2,games.size())));
    }
    private View libraryPair(List<?> pair){
        LinearLayout row=w.row();w.pad(row,12,4,12,5);
        for(int i=0;i<2;i++){
            LinearLayout tile=w.column();LinearLayout.LayoutParams lp=w.flex();lp.setMargins(w.dp(4),0,w.dp(4),0);row.addView(tile,lp);
            if(i>=pair.size())continue;
            JSONObject game=(JSONObject)pair.get(i);tile.setClipToOutline(true);w.action(tile,w.shape(PANEL,16),()->open.accept(game));
            FrameLayout banner=new FrameLayout(w.context);tile.addView(banner,new LinearLayout.LayoutParams(-1,w.dp(88)));
            ImageView art=w.artView();banner.addView(art);w.art(art,game,"banner400","banner300","boxart");
            TextView owned=w.badge("OWNED",true);FrameLayout.LayoutParams mark=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.END);
            mark.setMargins(0,w.dp(6),w.dp(6),0);banner.addView(owned,mark);
            TextView title=w.text(Market.text(game,"title"),14,WHITE,true);w.ellipsis(title,1);w.pad(title,10,9,10,10);tile.addView(title);
        }
        return row;
    }
    void addGames(List<JSONObject> games,int limit) { addGames(games,limit,false); }
    void addGames(List<JSONObject> games,int limit,boolean wishlist) {
        for(JSONObject game:games.subList(0,Math.min(games.size(),limit))){
            if(wishlist)wishlistRows.add(items.size());
            items.add(game);
        }
    }
    @Override public int getCount() { return items.size(); }
    @Override public Object getItem(int position) { return items.get(position); }
    @Override public long getItemId(int position) { return position; }
    @Override public boolean areAllItemsEnabled() { return false; }
    @Override public boolean isEnabled(int position) { return items.get(position) instanceof JSONObject; }
    @Override public int getItemViewType(int position) { return isEnabled(position)?0:IGNORE_ITEM_VIEW_TYPE; }
    @Override public View getView(int position,View recycled,ViewGroup parent) {
        Object item=items.get(position);
        if(item instanceof View)return (View)item;
        if(item instanceof List)return libraryPair((List<?>)item);
        DealRow row=recycled instanceof DealRow?(DealRow)recycled:new DealRow();
        row.bind((JSONObject)item,wishlistRows.contains(position));return row;
    }
    private final class DealRow extends LinearLayout {
        final ImageView art;
        final TextView title,metadata,price,cut,low,storeLow;
        final LinearLayout marks;
        final FrameLayout alert;
        final Icon bellOff,bellOn;
        JSONObject game;
        DealRow() {
            super(w.context);
            setOrientation(VERTICAL);
            LinearLayout bar=w.row();addView(bar,new LayoutParams(-1,-2));
            boolean compact=w.context.getSharedPreferences("appearance",0).getBoolean("compactRows",false);
            w.pad(this,16,compact?2:4,16,compact?3:5);
            w.action(bar,w.shape(PANEL,17),()->open.accept(game));
            w.pad(bar,8,compact?5:8,6,compact?5:8);
            int width=w.context.getResources().getConfiguration().screenWidthDp;
            art=w.artView();art.setBackground(w.shape(RAISED,12));art.setClipToOutline(true);bar.addView(art,new LayoutParams(w.dp(compact?72:Math.min(112,Math.max(84,Math.round(width*.24f)))),w.dp(compact?48:62)));
            LinearLayout info=w.column();w.pad(info,10,5,6,5);bar.addView(info,w.flex());
            title=w.text("",15,WHITE,true);w.ellipsis(title,2);info.addView(title);
            metadata=w.text("",11,MUTED,false);w.ellipsis(metadata,1);w.pad(metadata,0,4,0,0);info.addView(metadata);
            LinearLayout values=w.column();values.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
            w.pad(values,0,4,10,4);bar.addView(values,new LayoutParams(-2,-2));
            price=w.text("",17,WHITE,true);price.setGravity(Gravity.END);values.addView(price);
            marks=w.row();marks.setGravity(Gravity.END);w.pad(marks,0,3,0,0);values.addView(marks);
            low=w.badge("HL",true);low.setContentDescription("Historical low");marks.addView(low);
            storeLow=w.badge("S",false);storeLow.setContentDescription("Store low");
            LayoutParams storeLp=new LayoutParams(-2,-2);storeLp.leftMargin=w.dp(3);marks.addView(storeLow,storeLp);
            cut=w.badge("",false);LayoutParams badgeLp=new LayoutParams(-2,-2);badgeLp.leftMargin=w.dp(3);
            marks.addView(cut,badgeLp);
            alert=new FrameLayout(w.context);
            bellOff=new Icon(w.context,"bell",MUTED);bellOn=new Icon(w.context,"bell-on",ACCENT);
            for(Icon icon:new Icon[]{bellOff,bellOn})alert.addView(icon,new FrameLayout.LayoutParams(w.dp(19),w.dp(19),Gravity.CENTER));
            w.action(alert,w.shape(PANEL,12),()->editAlert.accept(game));
            bar.addView(alert,new LayoutParams(w.dp(48),w.dp(48)));
            
        }
        void bind(JSONObject value,boolean wishlist) {
            game=value;title.setText(Market.text(game,"title"));
            RowData display=rendered.get(game);
            if(display==null || display.offer!=null && !Market.active(display.offer,System.currentTimeMillis()/1000)){
                display=new RowData(game,platform,drm);rendered.put(game,display);
            }
            w.rowArt(art,display.art,display.thumbnail);
            JSONObject offer=display.offer;
            alert.setVisibility(wishlist?VISIBLE:GONE);
            if(wishlist){
                boolean on=alertEnabled.test(game);
                bellOff.setVisibility(on?GONE:VISIBLE);bellOn.setVisibility(on?VISIBLE:GONE);
                alert.setSelected(on);alert.setContentDescription((on?"Alert on for ":"Alert off for ")+Market.text(game,"title"));
                alert.setTooltipText(on?"Edit price alert":"Set price alert");
                w.action(alert,w.shape(on?ACCENT_SURFACE:PANEL,12),()->editAlert.accept(game));
                alert.setElevation(on?w.dp(5):0);
            }
            metadata.setPaintFlags(metadata.getPaintFlags() & ~android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            if(offer==null){
                metadata.setText(Market.NO_PRICE);price.setText("—");w.priceStyle(price,Market.LowPeriod.NONE);price.setTextColor(MUTED);
                marks.setVisibility(GONE);
            }else{
                String age=age(Market.text(offer,"updated"));
                if(wishlist){
                    boolean regular=offer.has("regular");
                    metadata.setText(display.original);
                    if(regular && offer.optLong("regular")>offer.optLong("paise"))
                        metadata.setPaintFlags(metadata.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
                }else metadata.setText(Market.text(offer,"shop")+(age.isEmpty()?"":"  ·  "+age));
                price.setText(display.price);Market.LowPeriod tier=display.period;
                boolean marked=tier!=Market.LowPeriod.NONE;w.priceStyle(price,tier);
                low.setText(tier.badge);low.setContentDescription(tier.description);w.tierBadge(low,tier,true);low.setVisibility(marked?VISIBLE:GONE);
                boolean store=Market.storeLow(offer);storeLow.setVisibility(store?VISIBLE:GONE);
                int discount=offer.optInt("cut");
                cut.setText("−"+discount+"%");cut.setVisibility(discount>0?VISIBLE:GONE);
                w.tierBadge(cut,tier,false);
                marks.setVisibility(marked||store||discount>0?VISIBLE:GONE);
            }
        }
    }
}

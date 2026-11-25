package in.rupeeraid.mobile;

import android.app.AlertDialog;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.time.Instant;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;
import static in.rupeeraid.mobile.MarketWidgets.*;

/** A single game keeps its selected tab and DRM filter across foreground updates. */
final class GamePage {
    private static final String[][] TABS={{"offers","Offers"},{"history","Price history"},
            {"series","Series"},{"subscriptions","Subscriptions"},{"giveaways","Giveaways"},{"about","About"},{"stats","Community"}};
    final String id;
    String tab="offers",offerMessage="";
    private final MarketScreen screen;
    private final MainActivity a;
    private final MarketWidgets w;
    private final Map<String,JSONObject> loaded=new HashMap<>();
    private final Set<String> waiting=new HashSet<>();
    private final Map<String,Integer> visible=new HashMap<>();
    private JSONObject game;
    private String platform,historyKey="history";
    private final Set<String> selectedDrm=new LinkedHashSet<>(),expandedBundles=new HashSet<>();
    private LinearLayout steamRating;
    private TextView heroInfo,heroShop;
    private LinearLayout hero,offerHeader,actions;
    private View heroLoading;
    private ImageView cover;
    private int requestGeneration;
    private boolean redrawQueued;
    private int historyShop=-1,scrollY,tabX;
    private LinearLayout content,tabRow;
    private ScrollView scroll;
    private HorizontalScrollView tabs;
    GamePage(MarketScreen screen,MainActivity a,MarketWidgets w,String id){this.screen=screen;this.a=a;this.w=w;this.id=id;selectedDrm.addAll(DrmFilter.saved(a));}
    void remember(){if(scroll!=null){scrollY=scroll.getScrollY();tabX=tabs==null?0:tabs.getScrollX();}}
    void mount(LinearLayout page,JSONObject game,String platform,String origin){
        this.game=game;this.platform=platform;scroll=(ScrollView)page.getParent();
        FrameLayout heroFrame=new FrameLayout(a);page.addView(heroFrame);
        hero=w.row();hero.setGravity(Gravity.TOP);w.pad(hero,12,14,12,14);
        android.graphics.drawable.GradientDrawable heroBg=new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[]{ACCENT_SURFACE,BG});heroBg.setCornerRadius(w.dp(20));heroBg.setStroke(w.dp(1),LINE);hero.setBackground(heroBg);heroFrame.addView(hero);
        hero.setVisibility(View.INVISIBLE);
        heroLoading=w.loadingRows("Loading game overview");heroLoading.setBackground(w.shape(PANEL,20));
        heroFrame.addView(heroLoading,new FrameLayout.LayoutParams(-1,-1));
        FrameLayout artwork=new FrameLayout(a);artwork.setBackground(w.shape(PANEL,14));artwork.setClipToOutline(true);
        hero.addView(artwork,new LinearLayout.LayoutParams(w.dp(104),w.dp(150)));
        cover=w.artView();artwork.addView(cover,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout head=w.column();w.pad(head,14,3,0,0);hero.addView(head,new LinearLayout.LayoutParams(0,-1,1));
        String type=Market.text(game,"type");
        line(head,PricingRegion.current.name().toUpperCase(Locale.ROOT)+" / "+(type.isEmpty()?"GAME":type.toUpperCase(Locale.ROOT))+" · PC",10,LILAC,true,0);
        line(head,Market.text(game,"title"),24,WHITE,true,5);
        heroInfo=w.text("",12,MUTED,false);w.pad(heroInfo,0,6,0,0);head.addView(heroInfo);
        offerHeader=w.column();head.addView(offerHeader);
        head.addView(new View(a),new LinearLayout.LayoutParams(1,0,1));
        LinearLayout footer=w.row();footer.setGravity(Gravity.BOTTOM);head.addView(footer);
        heroShop=w.text("",12,MUTED,false);w.pad(heroShop,0,4,5,0);footer.addView(heroShop,w.flex());
        steamRating=w.row();footer.addView(steamRating);showHeroInfo();
        actions=w.row();w.pad(actions,0,13,0,8);page.addView(actions);
        bindGameControls();
        tabs=new HorizontalScrollView(a);tabs.setHorizontalScrollBarEnabled(false);tabRow=w.row();tabs.addView(tabRow);
        LinearLayout.LayoutParams spacing=new LinearLayout.LayoutParams(-1,-2);spacing.topMargin=w.dp(12);page.addView(tabs,spacing);
        content=w.column();page.addView(content);
        if(a.personal && !loaded.containsKey("about"))request("about");
        paintTabs(false);paintContent();
        final int oldY=scrollY,oldX=tabX;ScrollView current=scroll;HorizontalScrollView strip=tabs;
        current.post(()->{current.scrollTo(0,oldY);strip.scrollTo(oldX,0);});
    }
    void update(JSONObject latest){
        game=latest;
        showHeroInfo();
        bindGameControls();
        paintContent();
    }
    private void bindGameControls(){
        LinearLayout head=offerHeader;
        head.removeAllViews();actions.removeAllViews();
        JSONObject lead=Market.best(game,platform,selectedDrm);
        if(lead!=null){
            LinearLayout chips=w.row();w.pad(chips,0,8,0,0);head.addView(chips);
            Market.LowPeriod tier=Market.lowPeriod(lead);
            if(tier!=Market.LowPeriod.NONE){TextView mark=w.badge(tier.badge,false);w.tierBadge(mark,tier,true);w.addBadge(chips,mark);}
            if(Market.storeLow(lead))w.addBadge(chips,w.badge("S",true));
            if(lead.optInt("cut")>0){TextView cut=w.badge("−"+lead.optInt("cut")+"%",false);w.tierBadge(cut,tier,false);w.addBadge(chips,cut);}
        }
        heroShop.setText(Market.text(lead,"shop"));
        boolean wished=member("wishlist"),owned=member("library"),ignored=member("ignored");
        boolean notifying=a.gameRule(id)!=null;
        action(actions,wished?"heart-on":"heart",wished?"Wishlisted":"Wishlist",wished,()->change("wishlist",!wished));
        action(actions,notifying?"bell-on":"bell",notifying?"Alert on":"Notify",notifying,()->{
            if(wished)screen.wishlistBell(id);
            else android.widget.Toast.makeText(a,"Add this game to your Wishlist, then tap its bell.",android.widget.Toast.LENGTH_LONG).show();
        });
        action(actions,owned?"own-on":"own",owned?"Owned":"Own",owned,()->change("library",!owned));
        action(actions,ignored?"ignore-on":"ignore",ignored?"Ignored":"Ignore",ignored,()->change("ignored",!ignored));
    }
    private JSONObject info(){
        JSONObject page=loaded.get("about");return page==null?null:page.optJSONObject("data");
    }
    private void showHeroInfo(){
        JSONObject info=info();String release=Market.text(info,"releaseDate");String developer=info==null?"":names(info.optJSONArray("developers"));
        if(developer.contains(" · "))developer=developer.substring(0,developer.indexOf(" · "));
        String metadata=String.join("  ·  ",nonempty(release.isEmpty()?"":release.substring(0,Math.min(4,release.length())),developer));
        steamRating.removeAllViews();
        if(info!=null)for(JSONObject review:Market.objects(info.optJSONArray("reviews"))){
            if("Steam".equalsIgnoreCase(Market.text(review,"source")) && validScore(review)){
                rating(steamRating,review,true);break;
            }
        }
        heroInfo.setText(metadata);heroInfo.setVisibility(metadata.isEmpty()?View.GONE:View.VISIBLE);w.ellipsis(heroInfo,2);
        if(a.personal && !loaded.containsKey("about"))return;
        String portrait=info==null?"":Market.art(info,"boxart");
        if(portrait.isEmpty())portrait=Market.art(game,"boxart");
        String url=portrait.isEmpty()?Market.art(game,"banner600","banner400","banner300","banner145"):portrait;
        ImageView target=cover;
        w.heroArt(target,url,!portrait.isEmpty(),()->{
            if(target!=cover || !screen.isGame(this))return;
            boolean firstReveal=hero.getVisibility()!=View.VISIBLE;
            heroLoading.setVisibility(View.GONE);hero.setVisibility(View.VISIBLE);
            if(firstReveal && motion(a)){
                hero.setAlpha(.85f);hero.animate().alpha(1f).setDuration(150).start();
            }
        });
    }
    private static List<String> nonempty(String... values){List<String> result=new ArrayList<>();for(String value:values)if(!value.isEmpty())result.add(value);return result;}
    private boolean member(String list){return Market.contains(a.data.optJSONArray(list),id);}
    private void change(String list,boolean add){
        if(!add && list.equals("library")){
            new AlertDialog.Builder(a).setTitle("Remove from Collection?").setMessage("This removes the game and its copies from your IsThereAnyDeal Collection.")
                .setNegativeButton("Cancel",null).setPositiveButton("Remove",(d,i)->a.changeList(game,list,false)).show();
        }else if(!add && list.equals("wishlist") && a.rule(id)!=null && !a.personal){
            new AlertDialog.Builder(a).setTitle("Remove from Wishlist?").setMessage("Your alert needs Wishlist access to check prices. Removing this game also turns off its alert on this phone.")
                .setNegativeButton("Cancel",null).setPositiveButton("Remove",(d,i)->a.changeList(game,list,false)).show();
        }else a.changeList(game,list,add);
    }
    private void action(LinearLayout parent,String icon,String label,boolean active,Runnable click){
        LinearLayout button=w.column();button.setGravity(Gravity.CENTER);
        button.addView(new Icon(a,icon,active?LILAC:ACCENT),new LinearLayout.LayoutParams(w.dp(19),w.dp(19)));
        TextView caption=w.text(label,11,active?LILAC:WHITE,true);caption.setGravity(Gravity.CENTER);w.pad(caption,0,4,0,0);button.addView(caption);
        w.action(button,w.shape(active?ACCENT_SURFACE:RAISED,15),click);button.setContentDescription(label+" for "+Market.text(game,"title"));
        button.setSelected(active);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,w.dp(54),1);
        if(parent.getChildCount()>0)lp.setMarginStart(w.dp(10));
        parent.addView(button,lp);
    }
    private void paintTabs(boolean reveal){
        tabRow.removeAllViews();
        for(String[] item:TABS){
            String key=item[0];boolean active=tab.equals(key);
            LinearLayout cell=w.column();TextView text=w.text(item[1],14,active?WHITE:MUTED,active);text.setGravity(Gravity.CENTER);
            w.pad(text,12,0,12,0);cell.addView(text,new LinearLayout.LayoutParams(-2,w.dp(41)));
            View bar=new View(a);bar.setBackgroundColor(active?ACCENT:LINE);cell.addView(bar,new LinearLayout.LayoutParams(-1,w.dp(active?3:1)));
            w.action(cell,w.shape(BG,2),()->{if(!tab.equals(key)){tab=key;paintTabs(true);paintContent();}});cell.setSelected(active);cell.setContentDescription(item[1]+" tab");
            tabRow.addView(cell);
            if(active && reveal)cell.post(()->{
                if(cell.getLeft()<tabs.getScrollX() || cell.getRight()>tabs.getScrollX()+tabs.getWidth())
                    tabs.smoothScrollTo(Math.max(0,cell.getLeft()-(tabs.getWidth()-cell.getWidth())/2),0);
            });
        }
    }
    private void paintContent(){
        loaded.entrySet().removeIf(entry-> !entry.getValue().has("message") &&
                !MobileApi.fresh(entry.getValue().optLong("saved"),MainActivity.now()));
        if(a.personal && !loaded.containsKey("about"))request("about");
        content.removeAllViews();
        if(tab.equals("offers")){offerOverview();return;}
        String active=tab;
        if(active.equals("series"))line(content,"Games matching “"+GameCatalog.seriesQuery(Market.text(game,"title"))+"”. ITAD does not publish official series membership.",13,MUTED,false,14);
        if(active.equals("history")){
            String[] options={"Past three months","Past year","All available history"};String[] keys={"history","historyYear","historyAll"};
            button(content,"Period  ·  "+options[Arrays.asList(keys).indexOf(historyKey)],()->new AlertDialog.Builder(a)
                .setTitle("Price history period").setItems(options,(d,i)->{historyKey=keys[i];paintContent();}).show());
        }
        if(active.equals("about") && info()!=null && !loaded.containsKey("editorial"))request("editorial");
        String key=active.equals("history")?historyKey:active;
        JSONObject page=loaded.get(key);
        if(page==null){
            content.addView(w.loadingRows("Loading game details"));
            request(key);return;
        }
        Object data=page.opt("data");String message=Market.text(page,"message");
        if(!message.isEmpty()){
            line(content,message,14,LILAC,false,13);
            if(page.optBoolean("needsKey"))button(content,"Add personal API key",a::editKey);
            else button(content,"Retry",()->{loaded.remove(key);paintContent();});
        }
        JSONArray rows=data instanceof JSONArray?(JSONArray)data:null;
        if(rows!=null)switch(active){
            case "series":related(rows);break;
            case "giveaways":giveaways(rows);break;
            case "subscriptions":subscriptions(rows);break;
            case "history":history(rows);break;
        }
        if(data instanceof JSONObject){if(active.equals("about"))about((JSONObject)data);else stats((JSONObject)data);}
        if(active.equals("stats") && !(data instanceof JSONObject) && info()!=null)stats(new JSONObject());
        if(data!=null && page.optLong("saved")>0)line(content,"Saved "+age(Instant.ofEpochSecond(page.optLong("saved")).toString()).toLowerCase(Locale.ROOT),11,MUTED,false,14);
        if(active.equals("series"))button(content,"Search other titles",()->screen.relatedSearch(GameCatalog.seriesQuery(Market.text(game,"title"))));
    }
    private void request(String key){
        if(!waiting.add(key))return;
        int generation=requestGeneration;
        boolean supplement=key.equals("editorial") || key.equals("memberships") || key.equals("relations");
        JSONObject target=game;
        if(supplement && info()!=null){
            target=new JSONObject();
            try{
                target.put("id",id).put("title",game.getString("title")).put("appid",info().optInt("appid"));
                String slug=Market.text(info(),"slug");
                target.put("slug",slug.isEmpty()?Market.text(game,"slug"):slug);
            }catch(org.json.JSONException invalid){target=game;}
        }
        a.gameSection(target,key,result->{
            if(generation!=requestGeneration)return;
            waiting.remove(key);loaded.put(key,result);
            if(!screen.isGame(this))return;
            if(key.equals("about"))showHeroInfo();
            if(!redrawQueued && (tab.equals("offers") || tab.equals(key) || tab.equals("about") && key.equals("editorial") || tab.equals("stats") && key.equals("about") || tab.equals("history") && historyKey.equals(key))){
                redrawQueued=true;
                android.view.Choreographer.getInstance().postFrameCallback(time->{
                    redrawQueued=false;if(screen.isGame(this))paintContent();
                });
            }
        });
    }
    void credentialsChanged(){requestGeneration++;waiting.clear();loaded.clear();}
    private void line(LinearLayout box,String value,int size,int color,boolean bold,int top){
        if(value.isEmpty())return;TextView v=w.text(value,size,color,bold);w.pad(v,0,top,0,0);box.addView(v);
    }
    private void button(LinearLayout box,String label,Runnable click){
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=w.dp(7);box.addView(w.button(label,false,click),lp);
    }
    private LinearLayout card(){
        LinearLayout box=w.column();box.setBackground(w.shape(PANEL,16));w.pad(box,11,8,11,8);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=w.dp(7);content.addView(box,lp);return box;
    }
    private void link(LinearLayout box,String label,String url){
        Uri u=Uri.parse(url);if("https".equalsIgnoreCase(u.getScheme()) && u.getHost()!=null && u.getUserInfo()==null && !MainActivity.providerPage(u))button(box,label+"  ↗",()->a.open(u));
    }
    private static String names(JSONArray rows){
        Set<String> values=new LinkedHashSet<>();
        if(rows!=null)for(int i=0;i<rows.length();i++){
            Object raw=rows.opt(i);String s=raw instanceof JSONObject?Market.text((JSONObject)raw,"name"):raw instanceof String?(String)raw:"";
            if(!s.isEmpty())values.add(s);
        }return String.join(" · ",values);
    }
    static String date(String raw){return raw.length()>=10?raw.substring(0,10):raw;}
    static String localPriceDate(String raw){
        if(raw.isEmpty())return "";
        try{return Instant.parse(raw).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString();}
        catch(java.time.DateTimeException invalid){return date(raw);}
    }
    private static boolean ended(JSONObject row){
        String end=Market.text(row,"expiry");try{return !end.isEmpty() && !Instant.parse(end).isAfter(Instant.now());}catch(Exception e){return false;}
    }
    private void chooseDrm(){
        DrmFilter.choose(a,selectedDrm,values->{
            selectedDrm.clear();selectedDrm.addAll(values);
            bindGameControls();paintContent();
        });
    }
    private void offers(){
        LinearLayout heading=w.row();w.pad(heading,0,6,0,0);content.addView(heading);
        heading.addView(w.text("Prices",19,WHITE,true),w.flex());
        String choice=DrmFilter.label(selectedDrm);
        TextView filter=w.text("DRM · "+choice+"  ▾",13,LILAC,true);filter.setGravity(Gravity.CENTER);
        w.pad(filter,12,0,12,0);w.action(filter,w.shape(PANEL,12),this::chooseDrm);
        filter.setContentDescription("Filter by DRM, "+choice);heading.addView(filter,new LinearLayout.LayoutParams(-2,w.dp(44)));
        if(!offerMessage.isEmpty())line(content,offerMessage,13,LILAC,false,6);
        List<JSONObject> rows=Market.offers(game,platform);
        Map<Integer,JSONObject> storeRecords=new LinkedHashMap<>();
        for(JSONObject item:Market.objects(rowsFor("lows"))){
            JSONObject shop=item.optJSONObject("shop");
            if(Market.nativeAmount(shop,item.optJSONObject("price"))<0)continue;
            storeRecords.put(Market.shopId(shop),item);
        }
        Set<Integer> displayed=new HashSet<>();
        boolean matched=false;
        for(JSONObject row:rows){
            if(!DrmFilter.matches(row,selectedDrm))continue;
            matched=true;
            LinearLayout box=card(),head=w.row();box.addView(head);head.addView(w.text(Market.text(row,"shop"),16,WHITE,true),w.flex());
            boolean verified=row.has("paise");Market.LowPeriod period=Market.lowPeriod(row);
            TextView amount=w.text(verified?money(row.optLong("paise")):"—",18,WHITE,true);w.priceStyle(amount,period);head.addView(amount);
            if(!verified)line(box,Market.NO_PRICE,13,MUTED,false,5);
            else{
                List<String> tags=new ArrayList<>();if(row.optInt("cut")>0)tags.add("−"+row.optInt("cut")+"% · was "+money(row.optLong("regular")));
                if(period!=Market.LowPeriod.NONE)tags.add(period.description);
                if(Market.storeLow(row))tags.add("S · Store low");
                line(box,String.join("  ·  ",tags),13,period==Market.LowPeriod.NONE?LILAC:lowColor(period),true,5);
                String coupon=Market.text(row,"voucher");if(!coupon.isEmpty())line(box,"Coupon  "+coupon,14,GREEN,true,6);
            }
            int shopId=row.optInt("shopId",-1);displayed.add(shopId);
            JSONObject storeRecord=storeRecords.get(shopId);
            long low=row.optLong("storeLow",-1);
            if(storeRecord!=null){
                long historical=Market.nativeAmount(storeRecord.optJSONObject("shop"),storeRecord.optJSONObject("price"));
                low=low<0?historical:Math.min(low,historical);
            }
            line(box,"Store all-time low  ·  "+(low<0?"Unavailable":money(low)),13,LILAC,true,5);
            String drm=names(row.optJSONArray("drm"));if(!drm.isEmpty())line(box,"Activation  ·  "+drm,12,MUTED,false,4);
            List<String> dates=new ArrayList<>();
            if(!Market.text(row,"updated").isEmpty())dates.add("Updated "+age(Market.text(row,"updated")).toLowerCase(Locale.ROOT));
            if(row.optLong("cachedAt")>0)dates.add("Checked "+date(Instant.ofEpochSecond(row.optLong("cachedAt")).toString()));
            if(row.optLong("expiry")>0)dates.add("Ends "+date(Instant.ofEpochSecond(row.optLong("expiry")).toString()));
            LinearLayout footer=w.row();box.addView(footer);
            footer.addView(w.text(String.join(" · ",dates),11,MUTED,false),w.flex());
            Uri retailer=Uri.parse(Market.text(row,"url"));
            if("https".equalsIgnoreCase(retailer.getScheme()) && retailer.getHost()!=null && retailer.getUserInfo()==null && !MainActivity.providerPage(retailer)){
                TextView open=w.text("Open store ↗",12,LILAC,true);open.setGravity(Gravity.CENTER);
                w.pad(open,8,0,3,0);w.action(open,w.shape(PANEL,8),()->a.open(retailer));
                open.setContentDescription("Open "+Market.text(row,"shop"));footer.addView(open,new LinearLayout.LayoutParams(-2,w.dp(44)));
            }
        }
        if(!matched)line(content,selectedDrm.isEmpty()?Market.NO_PRICE:"No current offers match the selected DRM.",13,MUTED,false,8);
        if(!selectedDrm.isEmpty())return;
        for(Map.Entry<Integer,JSONObject> entry:storeRecords.entrySet()){
            if(displayed.contains(entry.getKey()))continue;
            JSONObject value=entry.getValue(),shop=value.optJSONObject("shop");
            LinearLayout box=card();line(box,Market.text(shop,"name"),16,WHITE,true,0);
            line(box,"Current  ·  "+Market.NO_PRICE,13,MUTED,false,4);
            line(box,"Store all-time low  ·  "+money(Market.nativeAmount(shop,value.optJSONObject("price"))),13,LILAC,true,5);
        }
    }
    private void related(JSONArray rows){
        if(rows.length()==0)line(content,"No matching titles returned.",14,MUTED,false,10);
        for(JSONObject item:firstRows(rows))gameRow(content,item);
        more(rows);
    }
    private List<JSONObject> firstRows(JSONArray array){
        List<JSONObject> entries=Market.objects(array);return entries.subList(0,Math.min(entries.size(),visible.getOrDefault(tab,12)));
    }
    private void more(JSONArray rows){
        int limit=visible.getOrDefault(tab,12);
        if(rows.length()>limit)button(content,"Show more ("+(rows.length()-limit)+")",()->{visible.put(tab,limit+12);paintContent();});
    }
    private void gameRow(LinearLayout box,JSONObject item){
        String steamPath=Market.text(item,"steamPath");
        boolean external=Market.text(item,"id").isEmpty();
        if(external && !steamPath.matches("(bundle|sub)/[0-9]+"))return;
        LinearLayout row=w.row();w.pad(row,7,5,8,5);
        ImageView art=w.artView();row.addView(art,new LinearLayout.LayoutParams(w.dp(63),w.dp(38)));w.art(art,item,"banner145","banner300","boxart");
        LinearLayout caption=w.column();w.pad(caption,9,0,2,0);row.addView(caption,w.flex());
        line(caption,Market.text(item,"title"),14,WHITE,true,0);JSONObject offer=Market.best(item,Market.ALL,selectedDrm);
        line(caption,offer==null?Market.text(item,"type").toUpperCase(Locale.ROOT):money(offer.optLong("paise"))+"  ·  "+Market.text(offer,"shop"),12,MUTED,false,3);
        w.action(row,w.shape(PANEL,6),()->{if(external)a.open(Uri.parse("https://store.steampowered.com/"+steamPath+"/"));else screen.openRelated(item);});row.setContentDescription("Open "+Market.text(item,"title"));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=w.dp(5);box.addView(row,lp);
    }
    private void bundles(JSONArray rows){
        if(rows.length()==0)line(content,"ITAD lists no bundles containing this game.",14,MUTED,false,14);
        for(JSONObject b:firstRows(rows)){
            LinearLayout box=card(),summary=w.column();box.addView(summary);
            String bundleId=b.optString("id",Market.text(b,"title"));boolean open=expandedBundles.contains(bundleId);
            JSONObject page=b.optJSONObject("page");
            LinearLayout heading=w.row();summary.addView(heading);heading.addView(w.text(Market.text(b,"title"),15,WHITE,true),w.flex());
            TextView arrow=w.text(open?"▴":"▾",14,LILAC,true);w.pad(arrow,8,0,0,0);heading.addView(arrow);
            w.action(summary,w.shape(android.graphics.Color.TRANSPARENT,8),()->{if(open)expandedBundles.remove(bundleId);else expandedBundles.add(bundleId);paintContent();});
            w.pad(summary,0,4,2,4);
            line(summary,Market.text(page,"name")+"  ·  "+(ended(b)?"Ended":"Listed")+"  "+date(Market.text(b,"expiry")),13,MUTED,false,5);
            if(!open)continue;
            JSONObject counts=b.optJSONObject("counts");if(counts!=null)line(box,counts.optInt("games")+" games  ·  "+counts.optInt("media")+" media",12,LILAC,false,5);
            int index=0;
            for(JSONObject tier:Market.objects(b.optJSONArray("tiers"))){
                ++index;JSONObject shop=new JSONObject();try{shop.put("id",page==null?-1:page.optInt("shopId",-1));}catch(Exception ignored){}
                long amount=Market.nativeAmount(shop,tier.optJSONObject("price"));
                JSONArray items=tier.optJSONArray("games");if(items==null)continue;
                final String tierName=(tier.optBoolean("addon")?"Add-on ":"Tier ")+index+(amount>=0?"  ·  "+money(amount):"");
                button(box,tierName+"  ·  "+items.length()+" games",()->{
                    List<JSONObject> games=Market.objects(items);String[] labels=new String[games.size()];
                    for(int i=0;i<labels.length;i++)labels[i]=Market.text(games.get(i),"title");
                    new AlertDialog.Builder(a).setTitle(tierName).setItems(labels,(d,pos)->screen.openRelated(games.get(pos))).setNegativeButton("Close",null).show();
                });
                if(amount<0 && tier.optJSONObject("price")!=null)line(box,Market.NO_PRICE,12,MUTED,false,4);
            }
            if(!ended(b))link(box,"Visit bundle",Market.text(b,"url"));else link(box,"Bundle details",Market.text(b,"details"));
        }more(rows);
    }
    private void giveaways(JSONArray rows){
        line(content,"ITAD’s giveaway feed covers multiple regions. Check the offer for regional eligibility.",13,MUTED,false,14);
        if(rows.length()==0)line(content,"No giveaways listed for this game.",14,MUTED,false,10);
        for(JSONObject g:firstRows(rows)){
            LinearLayout box=card();line(box,Market.text(g,"title"),16,WHITE,true,0);
            line(box,(ended(g)?"Ended":"Listed")+"  ·  "+date(Market.text(g,"publish")),13,MUTED,false,5);
            line(box,Market.text(g.optJSONObject("shop"),"name"),13,LILAC,false,4);
            if(!Market.text(g,"expiry").isEmpty())line(box,"End date  ·  "+date(Market.text(g,"expiry")),12,MUTED,false,4);
            link(box,ended(g)?"Giveaway details":"Check availability",Market.text(g,ended(g)?"details":"url"));
        }more(rows);
    }
    private void subscriptions(JSONArray rows){
        if(rows.length()==0)line(content,"No regional subscription availability reported for this game.",14,MUTED,false,14);
        for(JSONObject row:Market.objects(rows)){
            LinearLayout box=card();line(box,Market.text(row,"name"),17,WHITE,true,0);
            if(!Market.text(row,"leaving").isEmpty())line(box,"Leaving "+date(Market.text(row,"leaving")),13,MUTED,false,5);
        }
    }
    private JSONArray rowsFor(String key){
        JSONObject result=loaded.get(key);
        return result==null?null:result.optJSONArray("data");
    }
    private void offerOverview(){
        List<String> sections=new ArrayList<>(Arrays.asList("lows","record","historyAll","bundles"));
        boolean hasSlug=!Market.text(game,"slug").isEmpty() || !Market.text(info(),"slug").isEmpty();
        if(hasSlug)sections.add("relations");
        JSONArray relations=rowsFor("relations");
        if(relations==null && (hasSlug?loaded.containsKey("relations"):!waiting.contains("about"))){
            sections.add("packs");
            if(info()!=null && info().optInt("appid")>0)sections.add("memberships");
        }
        for(String key:sections)if(!loaded.containsKey(key))request(key);
        line(content,"Price summary",20,WHITE,true,12);
        LinearLayout box=w.column();content.addView(box);
        JSONObject best=Market.best(game,platform,selectedDrm);
        summaryRow(box,"Current best",best==null?-1:best.optLong("paise",-1),Market.text(best,"shop"),WHITE);
        JSONObject record=loaded.get("record");
        PriceRecords records=new PriceRecords(game,record==null?null:record.optJSONObject("data"),rowsFor("lows"),rowsFor("historyAll"));
        for(int i=0;i<3;i++){
            PriceRecords.Low low=records.period(i);
            if(low!=null && low.time.isEmpty() && loadingPriceHistory())low=null;
            String detail=low==null?"":String.join("  ·  ",nonempty(low.store,localPriceDate(low.time)));
            summaryRow(box,new String[]{"All-time low","1 year low","3 months low"}[i],low==null?-1:low.amount,detail,
                lowColor(new Market.LowPeriod[]{Market.LowPeriod.ALL_TIME,Market.LowPeriod.YEAR,Market.LowPeriod.QUARTER}[i]));
        }
        offers();
        if(best==null && a.personal)button(content,"Search other editions",()->screen.relatedSearch(GameCatalog.seriesQuery(Market.text(game,"title"))));
        List<JSONObject> catalog=Market.objects(relations==null?rowsFor("packs"):relations);
        List<JSONObject> packages=new ArrayList<>();
        if(relations==null)packages.addAll(Market.objects(rowsFor("memberships")));
        Set<String> ids=new HashSet<>(),titles=new HashSet<>();
        for(JSONObject item:packages){ids.add(Market.text(item,"id"));titles.add(GameCatalog.words(Market.text(item,"title")));}
        boolean dlcHeading=false;
        for(JSONObject item:catalog){
            if(!Market.pcEdition(item))continue;
            if("dlc".equals(Market.text(item,"type"))){
                if(!dlcHeading){line(content,"DLCs",17,WHITE,true,12);dlcHeading=true;}
                gameRow(content,item);
            }else if(!ids.contains(Market.text(item,"id")) && titles.add(GameCatalog.words(Market.text(item,"title"))))packages.add(item);
        }
        if(!packages.isEmpty()){
            line(content,"Packages",17,WHITE,true,12);
            for(JSONObject item:packages)gameRow(content,item);
        }
        if(info()!=null && info().optInt("appid",-1)>0)
            link(content,"Browse this game’s DLC on Steam","https://store.steampowered.com/dlc/"+info().optInt("appid")+"/?cc="+PricingRegion.current.country);
        JSONArray bundles=rowsFor("bundles");
        if(bundles!=null){line(content,"Bundles",19,WHITE,true,18);bundles(bundles);}
        boolean needsKey=false,failed=false;
        for(String key:sections){
            JSONObject page=loaded.get(key);
            if(page==null)continue;
            if(page.optBoolean("needsKey"))needsKey=true;
            else if(!Market.text(page,"message").isEmpty())failed=true;
        }
        if(needsKey)button(content,"Add a personal key for full price history and related games",a::editKey);
        if(failed)button(content,"Retry unavailable details",()->{
            for(String key:sections)if(!Market.text(loaded.get(key),"message").isEmpty())loaded.remove(key);
            paintContent();
        });
        if(!waiting.isEmpty())content.addView(w.loadingRows("Loading game details"));
    }
    private void summaryRow(LinearLayout box,String name,long amount,String detail,int color){
        LinearLayout row=box.getChildCount()==0?null:(LinearLayout)box.getChildAt(box.getChildCount()-1);
        if(row==null || row.getChildCount()==2){row=w.row();box.addView(row);}
        LinearLayout tile=w.column();tile.setBackground(w.shape(PANEL,16));w.pad(tile,13,11,13,11);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1);lp.setMargins(row.getChildCount()==0?0:w.dp(5),w.dp(6),row.getChildCount()==0?w.dp(5):0,w.dp(6));row.addView(tile,lp);
        line(tile,name,13,MUTED,false,0);
        boolean pending=amount<0 && loadingPriceHistory();
        line(tile,amount<0?(pending?"Loading…":"Unavailable"):money(amount),amount<0?13:21,amount<0?MUTED:color,true,3);
        line(tile,detail,11,MUTED,false,4);
    }
    private boolean loadingPriceHistory(){
        return a.personal && (waiting.contains("record") || waiting.contains("lows") || waiting.contains("historyAll"));
    }
    private void history(JSONArray rows){
        line(content,"Store history · "+(historyKey.equals("history")?"past three months":historyKey.equals("historyYear")?"past year":"all available dates"),19,WHITE,true,14);
        Map<Integer,String> names=new LinkedHashMap<>();
        for(JSONObject r:Market.objects(rows)){
            JSONObject s=r.optJSONObject("shop"),d=r.optJSONObject("deal");
            if(s!=null && s.has("id") && d!=null && Market.nativeAmount(s,d.optJSONObject("price"))>=0)
                names.put(s.optInt("id"),Market.text(s,"name"));
        }
        List<Integer> stores=new ArrayList<>(names.keySet());
        if(stores.isEmpty()){line(content,"No regional price changes for this period.",14,MUTED,false,9);return;}
        if(!stores.contains(historyShop))historyShop=stores.get(0);
        String[] labels=names.values().toArray(new String[0]);
        button(content,"Store · "+names.get(historyShop),()->new AlertDialog.Builder(a).setTitle("Price history store").setItems(labels,(d,i)->{historyShop=stores.get(i);paintContent();}).show());
        List<JSONObject> points=new ArrayList<>();for(JSONObject r:Market.objects(rows))if(r.optJSONObject("shop")!=null && r.optJSONObject("shop").optInt("id")==historyShop)points.add(r);
        points.sort(Comparator.comparingLong(r->PriceChart.time(r)));
        new PriceChart(a,w,points).attach(content);
    }
    private static boolean validScore(JSONObject review){
        int score=review.optInt("score",-1);
        return score>=0 && score<=100;
    }
    private void rating(LinearLayout row,JSONObject review,boolean compact){
        String source=Market.text(review,"source"),key=source.toLowerCase(Locale.ROOT);
        boolean steam=key.equals("steam");
        int mark=steam?R.drawable.review_steam:key.contains("meta")?R.drawable.review_metacritic:key.contains("opencritic")?R.drawable.review_opencritic:0;
        LinearLayout chip=w.row();chip.setBackground(w.shape(PANEL,9));w.pad(chip,6,3,6,3);
        if(mark!=0){
            ImageView logo=new ImageView(a);logo.setImageResource(mark);
            chip.addView(logo,new LinearLayout.LayoutParams(w.dp(compact?13:16),w.dp(compact?13:16)));
        }
        String score=review.optInt("score")+(steam?"%":"");
        LinearLayout caption=w.column();w.pad(caption,mark==0?0:4,0,0,0);chip.addView(caption);
        line(caption,score,compact?11:14,WHITE,true,0);
        if(!compact)line(caption,key.contains("user")?"Meta users":source,10,MUTED,false,0);
        String reviews=Market.text(review,"count");
        chip.setContentDescription(source+" "+score+(steam?" positive":" out of 100")+(reviews.isEmpty()?"":", "+reviews+" reviews"));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);if(row.getChildCount()>0)lp.setMarginStart(w.dp(5));row.addView(chip,lp);
    }
    static String duration(long seconds){
        if(seconds<=0)return "";
        if(seconds<3600)return Math.max(1,Math.round(seconds/60.0))+" min";
        return Math.round(seconds/3600.0)+" h";
    }
    private void about(JSONObject info){
        JSONObject page=loaded.get("editorial"),detail=page==null?null:page.optJSONObject("data");
        JSONObject summary=detail==null?null:detail.optJSONObject("summary");
        line(content,"About "+Market.text(info,"title"),18,WHITE,true,10);
        String text=Market.text(summary,"text");
        line(content,text,14,WHITE,false,5);
        if(!text.isEmpty())line(content,Market.text(summary,"author"),10,MUTED,false,2);
        if(page==null)line(content,"Loading summary and play times…",12,MUTED,false,5);
        else if(page.has("message"))button(content,"Retry summary and play times",()->{loaded.remove("editorial");paintContent();});
        List<JSONObject> reviews=Market.objects(info.optJSONArray("reviews"));
        HorizontalScrollView strip=new HorizontalScrollView(a);strip.setHorizontalScrollBarEnabled(false);
        LinearLayout scores=w.row();strip.addView(scores);
        for(JSONObject r:reviews)if(validScore(r))rating(scores,r,false);
        if(scores.getChildCount()>0){
            line(content,"Ratings",15,WHITE,true,12);w.pad(strip,0,5,0,0);content.addView(strip);
        }
        JSONObject times=detail==null?null:detail.optJSONObject("hltb");
        LinearLayout play=w.row();play.setBaselineAligned(false);
        String[] fields={"main","extra","complete","all"},labels={"Main story","Main + sides","Completionist","All styles"};
        for(int i=0;times!=null && i<fields.length;i++){
            String time=duration(times.optLong(fields[i]));if(time.isEmpty())continue;
            LinearLayout cell=w.column();line(cell,time,15,WHITE,true,0);line(cell,labels[i],10,MUTED,false,0);play.addView(cell,w.flex());
        }
        if(play.getChildCount()>0){
            line(content,"HowLongToBeat",15,WHITE,true,12);w.pad(play,0,5,0,0);content.addView(play);
        }
        line(content,String.join("  ·  ",nonempty(Market.text(info,"releaseDate"),names(info.optJSONArray("developers")))),13,LILAC,true,12);
        String publisher=names(info.optJSONArray("publishers"));if(!publisher.isEmpty())line(content,"Published by "+publisher,12,MUTED,false,3);
        line(content,names(info.optJSONArray("tags")),12,MUTED,false,3);
        List<String> features=new ArrayList<>();
        if(info.optBoolean("earlyAccess"))features.add("Early access");
        if(info.optBoolean("achievements"))features.add("Achievements");
        if(info.optBoolean("tradingCards"))features.add("Trading cards");
        if(info.optBoolean("mature"))features.add("Mature content");
        line(content,String.join(" · ",features),12,MUTED,false,3);
        button(content,"Your ITAD game note",()->a.editGameNote(game));
        int appid=info.optInt("appid",-1);if(appid>0)link(content,"Store description and media","https://store.steampowered.com/app/"+appid+"/?cc="+PricingRegion.current.country);
    }
    private void stats(JSONObject data){
        JSONObject info=info(),stats=info==null?null:info.optJSONObject("stats");
        line(content,"ITAD community",18,WHITE,true,10);
        long wished=stats==null?-1:stats.optLong("waitlisted",-1);
        long owned=stats==null?-1:stats.optLong("collected",-1);
        long max=Math.max(wished,owned);
        if(wished>=0 || owned>=0){
            communityBar("Waitlisted",wished,max,LILAC);
            communityBar("Collected",owned,max,GREEN);
        }else if(data.optLong("count",-1)>=0){
            line(content,String.format(Locale.getDefault(),"%,d tracking this game",data.optLong("count")),15,WHITE,true,6);
        }else line(content,"Community counts unavailable.",13,MUTED,false,5);
        JSONObject cuts=data.optJSONObject("cut");
        double average=cuts==null?-1:cuts.optDouble("average",-1);
        if(average>=0 && average<=100)line(content,"Average requested discount  ·  "+Math.round(average)+"%",13,MUTED,false,8);
        JSONObject players=info==null?null:info.optJSONObject("players");
        if(players!=null){
            LinearLayout numbers=w.row();
            String[] keys={"recent","day","week","peak"},labels={"Recent","24h peak","Week peak","All-time peak"};
            for(int i=0;i<keys.length;i++)if(!players.isNull(keys[i])){
                LinearLayout cell=w.column();line(cell,String.format(Locale.getDefault(),"%,d",players.optLong(keys[i])),14,WHITE,true,0);
                line(cell,labels[i],10,MUTED,false,1);numbers.addView(cell,w.flex());
            }
            if(numbers.getChildCount()>0){line(content,"Steam players",15,WHITE,true,12);w.pad(numbers,0,4,0,0);content.addView(numbers);}
        }
    }
    private void communityBar(String label,long count,long max,int color){
        if(count<0)return;
        LinearLayout legend=w.row();w.pad(legend,0,6,0,2);content.addView(legend);
        legend.addView(w.text(label,13,WHITE,false),w.flex());
        legend.addView(w.text(String.format(Locale.getDefault(),"%,d",count),13,color,true));
        ProgressBar bar=new ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(false);bar.setMax(1000);bar.setProgress(max>0?(int)Math.round(1000.0*count/max):0);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(color));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(RAISED));
        bar.setContentDescription(label+", "+count);content.addView(bar,new LinearLayout.LayoutParams(-1,w.dp(6)));
    }
}

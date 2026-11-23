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

/** A single game keeps its selected tab and store filter across foreground updates. */
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
    private String platform,filter="",historyKey="history";
    private TextView heroInfo;
    private LinearLayout hero;
    private View heroLoading;
    private ImageView cover;
    private int requestGeneration;
    private boolean redrawQueued;
    private int historyShop=-1,scrollY,tabX;
    private LinearLayout content,tabRow;
    private ScrollView scroll;
    private HorizontalScrollView tabs;
    GamePage(MarketScreen screen,MainActivity a,MarketWidgets w,String id){this.screen=screen;this.a=a;this.w=w;this.id=id;}
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
        cover=w.artView();cover.setClipToOutline(true);cover.setBackground(w.shape(PANEL,14));
        hero.addView(cover,new LinearLayout.LayoutParams(w.dp(104),w.dp(150)));
        LinearLayout head=w.column();w.pad(head,14,3,0,0);hero.addView(head,w.flex());
        String type=Market.text(game,"type");
        line(head,PricingRegion.current.name().toUpperCase(Locale.ROOT)+"  /  "+(type.isEmpty()?"GAME":type.toUpperCase(Locale.ROOT))+"  ·  PC",11,LILAC,true,1);
        line(head,Market.text(game,"title"),24,WHITE,true,5);
        heroInfo=w.text("",12,MUTED,false);w.pad(heroInfo,0,6,0,0);head.addView(heroInfo);showHeroInfo();
        JSONObject lead=Market.best(game,platform);
        if(lead!=null){
            LinearLayout chips=w.row();w.pad(chips,0,8,0,0);head.addView(chips);
            Market.LowPeriod tier=Market.lowPeriod(lead);
            if(tier!=Market.LowPeriod.NONE){TextView mark=w.badge(tier.badge,false);w.tierBadge(mark,tier,true);w.addBadge(chips,mark);}
            if(Market.storeLow(lead))w.addBadge(chips,w.badge("S",true));
            if(lead.optInt("cut")>0){TextView cut=w.badge("−"+lead.optInt("cut")+"%",false);w.tierBadge(cut,tier,false);w.addBadge(chips,cut);}
            line(head,Market.text(lead,"shop"),12,MUTED,false,4);
        }
        LinearLayout actions=w.row();w.pad(actions,0,13,0,8);page.addView(actions);
        boolean wished=member("wishlist"),owned=member("library"),ignored=member("ignored");
        boolean notifying=a.gameRule(id)!=null;
        action(actions,wished?"heart-on":"heart",wished?"Wishlisted":"Wishlist",wished,()->change("wishlist",!wished));
        action(actions,notifying?"bell-on":"bell",notifying?"Alert on":"Notify",notifying,()->{
            if(wished)screen.wishlistBell(id);
            else android.widget.Toast.makeText(a,"Add this game to your Wishlist, then tap its bell.",android.widget.Toast.LENGTH_LONG).show();
        });
        action(actions,owned?"own-on":"own",owned?"Owned":"Own",owned,()->change("library",!owned));
        action(actions,ignored?"ignore-on":"ignore",ignored?"Ignored":"Ignore",ignored,()->change("ignored",!ignored));
        tabs=new HorizontalScrollView(a);tabs.setHorizontalScrollBarEnabled(false);tabRow=w.row();tabs.addView(tabRow);
        LinearLayout.LayoutParams spacing=new LinearLayout.LayoutParams(-1,-2);spacing.topMargin=w.dp(12);page.addView(tabs,spacing);
        content=w.column();page.addView(content);
        if(a.personal && !loaded.containsKey("about"))request("about");
        paintTabs(false);paintContent();
        final int oldY=scrollY,oldX=tabX;ScrollView current=scroll;HorizontalScrollView strip=tabs;
        current.post(()->{current.scrollTo(0,oldY);strip.scrollTo(oldX,0);});
    }
    private JSONObject info(){
        JSONObject page=loaded.get("about");return page==null?null:page.optJSONObject("data");
    }
    private void showHeroInfo(){
        JSONObject info=info();String release=Market.text(info,"releaseDate");String developer=info==null?"":names(info.optJSONArray("developers"));
        if(developer.contains(" · "))developer=developer.substring(0,developer.indexOf(" · "));
        String metadata=String.join("  ·  ",nonempty(release.isEmpty()?"":release.substring(0,Math.min(4,release.length())),developer));
        heroInfo.setText(metadata);heroInfo.setVisibility(metadata.isEmpty()?View.GONE:View.VISIBLE);w.ellipsis(heroInfo,2);
        if(a.personal && !loaded.containsKey("about"))return;
        String portrait=info==null?"":Market.art(info,"boxart");
        if(portrait.isEmpty())portrait=Market.art(game,"boxart");
        String url=portrait.isEmpty()?Market.art(game,"banner600","banner400","banner300","banner145"):portrait;
        ImageView target=cover;
        w.heroArt(target,url,!portrait.isEmpty(),()->{
            if(target!=cover || !screen.isGame(this))return;
            heroLoading.setVisibility(View.GONE);hero.setVisibility(View.VISIBLE);
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
        if(active.equals("about"))button(content,"Your ITAD game note",()->a.editGameNote(game));
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
        if(data!=null && page.optLong("saved")>0)line(content,"Saved "+age(Instant.ofEpochSecond(page.optLong("saved")).toString()).toLowerCase(Locale.ROOT),11,MUTED,false,14);
        if(active.equals("series"))button(content,"Search other titles",()->screen.relatedSearch(GameCatalog.seriesQuery(Market.text(game,"title"))));
    }
    private void request(String key){
        if(!waiting.add(key))return;
        int generation=requestGeneration;
        a.gameSection(game,key,result->{
            if(generation!=requestGeneration)return;
            waiting.remove(key);loaded.put(key,result);
            if(!screen.isGame(this))return;
            if(key.equals("about"))showHeroInfo();
            if(!redrawQueued && (tab.equals("offers") || tab.equals(key) || tab.equals("history") && historyKey.equals(key))){
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
        LinearLayout box=w.column();box.setBackground(w.shape(PANEL,16));w.pad(box,14,13,14,14);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=w.dp(14);content.addView(box,lp);return box;
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
    private static boolean ended(JSONObject row){
        String end=Market.text(row,"expiry");try{return !end.isEmpty() && !Instant.parse(end).isAfter(Instant.now());}catch(Exception e){return false;}
    }
    private void offers(){
        line(content,"Prices",19,WHITE,true,14);
        if(!offerMessage.isEmpty())line(content,offerMessage,13,LILAC,false,6);
        List<JSONObject> rows=Market.offers(game,platform);SortedSet<String> available=new TreeSet<>();
        Map<Integer,JSONObject> storeRecords=new LinkedHashMap<>();
        for(JSONObject item:Market.objects(rowsFor("lows"))){
            JSONObject shop=item.optJSONObject("shop");
            if(Market.nativeAmount(shop,item.optJSONObject("price"))<0)continue;
            storeRecords.put(Market.shopId(shop),item);available.add(Market.text(shop,"name"));
        }
        Set<Integer> displayed=new HashSet<>();
        for(JSONObject row:rows)available.add(Market.text(row,"shop"));
        if(!available.contains(filter))filter="";
        if(available.size()>1)button(content,filter.isEmpty()?"Filter stores":"Store · "+filter,()->{
            List<String> choices=new ArrayList<>();choices.add("All stores");choices.addAll(available);
            new AlertDialog.Builder(a).setTitle("Show offers from").setItems(choices.toArray(new String[0]),(d,i)->{filter=i==0?"":choices.get(i);paintContent();}).show();
        });
        if(rows.isEmpty())line(content,Market.NO_PRICE,14,MUTED,false,12);
        for(JSONObject row:rows){
            if(!filter.isEmpty() && !filter.equals(Market.text(row,"shop")))continue;
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
            line(box,"Updated "+age(Market.text(row,"updated")).toLowerCase(Locale.ROOT),12,MUTED,false,5);
            if(row.optLong("cachedAt")>0)line(box,"Last checked "+date(Instant.ofEpochSecond(row.optLong("cachedAt")).toString()),12,LILAC,false,4);
            if(row.optLong("expiry")>0)line(box,"Offer ends "+date(Instant.ofEpochSecond(row.optLong("expiry")).toString()),12,MUTED,false,4);
            Uri retailer=Uri.parse(Market.text(row,"url"));
            if("https".equalsIgnoreCase(retailer.getScheme()) && retailer.getHost()!=null && retailer.getUserInfo()==null && !MainActivity.providerPage(retailer)){
                LinearLayout.LayoutParams cta=new LinearLayout.LayoutParams(-1,-2);cta.topMargin=w.dp(9);
                box.addView(w.button("Open store ("+Market.text(row,"shop")+")  →",true,()->a.open(retailer)),cta);
            }
        }
        for(Map.Entry<Integer,JSONObject> entry:storeRecords.entrySet()){
            if(displayed.contains(entry.getKey()))continue;
            JSONObject value=entry.getValue(),shop=value.optJSONObject("shop");
            if(!filter.isEmpty() && !filter.equals(Market.text(shop,"name")))continue;
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
        if(Market.text(item,"id").isEmpty())return;
        LinearLayout row=w.row();w.pad(row,7,5,8,5);
        ImageView art=w.artView();row.addView(art,new LinearLayout.LayoutParams(w.dp(63),w.dp(38)));w.art(art,item,"banner145","banner300","boxart");
        LinearLayout caption=w.column();w.pad(caption,9,0,2,0);row.addView(caption,w.flex());
        line(caption,Market.text(item,"title"),14,WHITE,true,0);JSONObject offer=Market.best(item,Market.ALL);
        line(caption,offer==null?Market.text(item,"type").toUpperCase(Locale.ROOT):money(offer.optLong("paise"))+"  ·  "+Market.text(offer,"shop"),12,MUTED,false,3);
        w.action(row,w.shape(PANEL,6),()->screen.openRelated(item));row.setContentDescription("Open "+Market.text(item,"title"));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=w.dp(5);box.addView(row,lp);
    }
    private void bundles(JSONArray rows){
        if(rows.length()==0)line(content,"ITAD lists no bundles containing this game.",14,MUTED,false,14);
        for(JSONObject b:firstRows(rows)){
            LinearLayout box=card();JSONObject page=b.optJSONObject("page");line(box,Market.text(b,"title"),17,WHITE,true,0);
            line(box,Market.text(page,"name")+"  ·  "+(ended(b)?"Ended":"Listed")+"  "+date(Market.text(b,"expiry")),13,MUTED,false,5);
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
        String[] sections={"lows","record","historyYear","packs","bundles"};
        for(String key:sections)if(!loaded.containsKey(key))request(key);
        line(content,"Price summary",20,WHITE,true,12);
        LinearLayout box=w.column();content.addView(box);
        JSONObject best=Market.best(game,platform);
        summaryRow(box,"Current best",best==null?-1:best.optLong("paise",-1),Market.text(best,"shop"),WHITE);
        JSONObject record=loaded.get("record");
        PriceRecords records=new PriceRecords(game,record==null?null:record.optJSONObject("data"),rowsFor("lows"),rowsFor("historyYear"));
        for(int i=0;i<3;i++){
            PriceRecords.Low low=records.period(i);
            String detail=low==null?"":String.join("  ·  ",nonempty(low.store,date(low.time)));
            summaryRow(box,new String[]{"All-time low","1 year low","3 months low"}[i],low==null?-1:low.amount,detail,
                lowColor(new Market.LowPeriod[]{Market.LowPeriod.ALL_TIME,Market.LowPeriod.YEAR,Market.LowPeriod.QUARTER}[i]));
        }
        offers();
        if(best==null && a.personal)button(content,"Search other editions",()->screen.relatedSearch(GameCatalog.seriesQuery(Market.text(game,"title"))));
        JSONArray packs=rowsFor("packs");
        if(packs!=null){
            line(content,"DLCs and packages",19,WHITE,true,18);
            line(content,"Matches by title in the ITAD catalog. Open an edition to check its contents.",12,MUTED,false,4);
            for(String type:new String[]{"dlc","package"}){
                line(content,type.equals("dlc")?"DLCs":"Packages",15,LILAC,true,10);
                int count=0;
                for(JSONObject item:Market.objects(packs))if(type.equals(Market.text(item,"type")) && Market.pcEdition(item)){gameRow(content,item);count++;}
                if(count==0)line(content,"No matches listed.",13,MUTED,false,4);
            }
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
        boolean pending=amount<0 && a.personal && (!loaded.containsKey("record") || !loaded.containsKey("lows") || !loaded.containsKey("historyYear"));
        line(tile,amount<0?(pending?"Loading…":"Unavailable"):money(amount),amount<0?13:21,amount<0?MUTED:color,true,3);
        line(tile,detail,11,MUTED,false,4);
    }
    private void history(JSONArray rows){
        line(content,"Store history · "+(historyKey.equals("history")?"past three months":historyKey.equals("historyYear")?"past year":"all available dates"),19,WHITE,true,14);
        List<Integer> stores=new ArrayList<>();
        for(int shop:Market.INR_SHOPS)for(JSONObject r:Market.objects(rows)){
            JSONObject s=r.optJSONObject("shop"),d=r.optJSONObject("deal");
            if(s!=null && s.optInt("id")==shop && d!=null && Market.nativeAmount(s,d.optJSONObject("price"))>=0){stores.add(shop);break;}
        }
        if(stores.isEmpty()){line(content,"No regional price changes for this period.",14,MUTED,false,9);return;}
        if(!stores.contains(historyShop))historyShop=stores.get(0);
        String[] labels=new String[stores.size()];for(int i=0;i<labels.length;i++)labels[i]=Market.shopName(stores.get(i));
        button(content,"Store · "+Market.shopName(historyShop),()->new AlertDialog.Builder(a).setTitle("Price history store").setItems(labels,(d,i)->{historyShop=stores.get(i);paintContent();}).show());
        List<JSONObject> points=new ArrayList<>();for(JSONObject r:Market.objects(rows))if(r.optJSONObject("shop")!=null && r.optJSONObject("shop").optInt("id")==historyShop)points.add(r);
        points.sort(Comparator.comparingLong(r->PriceChart.time(r)));
        new PriceChart(a,w,points).attach(content);
    }
    private void about(JSONObject info){
        line(content,"About "+Market.text(info,"title"),19,WHITE,true,14);
        LinearLayout facts=card();fact(facts,"Release date",Market.text(info,"releaseDate"));fact(facts,"Developer",names(info.optJSONArray("developers")));
        fact(facts,"Publisher",names(info.optJSONArray("publishers")));fact(facts,"Tags",names(info.optJSONArray("tags")));
        List<String> features=new ArrayList<>();if(info.optBoolean("earlyAccess"))features.add("Early access");if(info.optBoolean("achievements"))features.add("Achievements");
        if(info.optBoolean("tradingCards"))features.add("Trading cards");if(info.optBoolean("mature"))features.add("Mature content");
        fact(facts,"Features",String.join(" · ",features));fact(facts,"Product type",Market.text(info,"type"));
        JSONObject stats=info.optJSONObject("stats");if(stats!=null){number(facts,"ITAD rank",stats,"rank");number(facts,"Wishlisted",stats,"waitlisted");number(facts,"Collected",stats,"collected");}
        JSONObject players=info.optJSONObject("players");
        if(players!=null){LinearLayout box=card();line(box,"Steam players",16,WHITE,true,0);number(box,"Recent",players,"recent");number(box,"Day",players,"day");number(box,"Week",players,"week");number(box,"Peak",players,"peak");}
        for(JSONObject r:Market.objects(info.optJSONArray("reviews"))){
            LinearLayout box=card();line(box,Market.text(r,"source"),16,WHITE,true,0);number(box,"Score",r,"score");number(box,"Reviews",r,"count");link(box,"Read reviews",Market.text(r,"url"));
        }
        int appid=info.optInt("appid",-1);if(appid>0)link(content,"Store description and media","https://store.steampowered.com/app/"+appid+"/?cc="+PricingRegion.current.country);
    }
    private void stats(JSONObject data){
        line(content,"ITAD Waitlist community",19,WHITE,true,14);LinearLayout box=card();
        number(box,"Players tracking this game",data,"count");
        JSONObject price=data.optJSONObject("price");if(price!=null)number(box,"Accept any price",price,"any");
        line(box,"Community preferences include players in all regions.",13,MUTED,false,8);
        JSONObject cut=data.optJSONObject("cut");if(cut!=null){
            double avg=cut.optDouble("average",-1);if(avg>=0 && avg<=100)fact(box,"Average requested discount",Math.round(avg)+"%");
            JSONArray buckets=cut.optJSONArray("buckets");if(buckets!=null && buckets.length()>0){
                LinearLayout dist=card();line(dist,"Requested discounts",16,WHITE,true,0);
                for(JSONObject bucket:Market.objects(buckets))if(bucket.has("bucket") && bucket.has("count"))fact(dist,bucket.optString("bucket")+"%",bucket.optString("count"));
            }
        }
    }
    private void number(LinearLayout box,String label,JSONObject obj,String field){if(obj!=null && !obj.isNull(field))fact(box,label,obj.optString(field));}
    private void fact(LinearLayout box,String label,String value){
        if(value.isEmpty())return;line(box,label,11,LILAC,true,8);line(box,value,15,WHITE,false,2);
    }
}

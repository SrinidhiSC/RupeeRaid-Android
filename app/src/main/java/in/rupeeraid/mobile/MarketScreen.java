package in.rupeeraid.mobile;

import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.text.DateFormat;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;
import static in.rupeeraid.mobile.MarketWidgets.*;

final class MarketScreen {
    private final MainActivity a;
    private final MarketWidgets w;
    private final LinearLayout root,header,nav;
    private final FrameLayout stage;
    private FrameLayout splash;
    private boolean restoredActivity;
    private ScrollView settingsScroll;
    private int settingsOffset;
    private String startupTab(){return a.getSharedPreferences("appearance",0).getString("startupTab","home");}
    boolean showingLibrary(){return selected==null && "library".equals(section);}
    private boolean splashElapsed;
    private final TextView message;
    private final ProgressBar progress;
    private final Map<String,int[]> positions=new HashMap<>();
    private String section="home",query="",order="Default",listKey="",headerMode="",returnFromSearch="home";
    private JSONObject selected;
    private GamePage gamePage;
    private Set<String> drm;
    private final Deque<JSONObject> trail=new ArrayDeque<>();
    private String restoredId="",restoredTab="";
    private JSONArray catalogResults=new JSONArray();
    private String catalogQuery="";
    private int carouselX;
    private boolean openKeyboard;
    private ListView list;
    private HorizontalScrollView shelf;
    private EditText search;
    private TextView refreshAction;
    private final LinearLayout syncIndicator;
    private final View syncIcon;
    private TextView unreadBadge,accountAvatar;
    private int navTarget=-1,navCount=-1;
    private float navPosition=-1;
    private ValueAnimator navSlide;
    private TextView setupKey;

    private final android.os.Handler typing=new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService matcher=java.util.concurrent.Executors.newSingleThreadExecutor();
    private TitleIndex index;
    private JSONObject indexedSnapshot;
    private List<JSONObject> localHits=Collections.emptyList();
    private volatile long revision;
    private boolean active=true,lookupRunning;
    private String searchStatus="",attempted="";
    private long nextLookup;
    private final Runnable localTask=this::matchSaved,remoteTask=this::lookup;

    private boolean searching(){return active && a.signedIn && selected==null && section.equals("search");}
    void pauseSearch(){
        active=false;attempted="";revision++;typing.removeCallbacks(localTask);typing.removeCallbacks(remoteTask);
    }
    void resumeSearch(){active=true;if(searching())queueSearch();}
    void dispose(){pauseSearch();matcher.shutdownNow();}
    private void queueSearch(){
        revision++;typing.removeCallbacks(localTask);typing.removeCallbacks(remoteTask);
        if(!searching())return;
        typing.postDelayed(localTask,35);
        if(a.personal && TitleIndex.words(query).length()>=2 && !query.trim().equalsIgnoreCase(catalogQuery)){
            searchStatus="Searching the PC catalog…";
            typing.postDelayed(remoteTask,Math.max(700,nextLookup-android.os.SystemClock.uptimeMillis()));
        }else searchStatus="";
    }
    private void matchSaved(){
        long generation=revision;String term=query;JSONObject snapshot=a.data;
        matcher.execute(()->{
            if(revision!=generation)return;
            if(indexedSnapshot!=snapshot){index=new TitleIndex(snapshot);indexedSnapshot=snapshot;}
            List<JSONObject> hits=index.suggestions(term);
            typing.post(()->{if(revision==generation && searching()){localHits=hits;updateSearchList();}});
        });
    }
    private void lookup(){
        if(!searching() || !a.personal || lookupRunning)return;
        String term=query.trim();
        if(TitleIndex.words(term).length()<2 || term.equalsIgnoreCase(attempted))return;
        if(a.backoff>MainActivity.now()){
            searchStatus="Catalog cooling down · showing saved matches";updateSearchList();
            typing.postDelayed(remoteTask,1000*(a.backoff-MainActivity.now()+1));return;
        }
        long generation=revision;lookupRunning=true;attempted=term;status();
        a.onlineSearch(term,()->generation==revision,(hits,error)->{
            lookupRunning=false;status();nextLookup=android.os.SystemClock.uptimeMillis()+1800;
            if(!searching())return;
            if(generation!=revision){attempted="";queueSearch();return;}
            catalogQuery=hits==null?"":term;catalogResults=hits==null?new JSONArray():hits;
            searchStatus=error;updateSearchList();
            if(hits==null && a.backoff>MainActivity.now()){
                attempted="";
                typing.postDelayed(remoteTask,1000*(a.backoff-MainActivity.now()+1));
            }
        });
    }
    MarketScreen(MainActivity activity){
        a=activity;drm=DrmFilter.saved(a);section=startupTab();order=defaultSort();w=new MarketWidgets(a);root=w.column();root.setBackgroundColor(BAR);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        a.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                |View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        a.setContentView(root);
        header=w.row();header.setBackgroundColor(BAR);w.pad(header,12,4,12,4);root.addView(header,new LinearLayout.LayoutParams(-1,w.dp(56)));
        progress=new ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        root.addView(progress,new LinearLayout.LayoutParams(-1,w.dp(2)));
        syncIndicator=w.row();syncIndicator.setGravity(Gravity.CENTER);w.pad(syncIndicator,8,5,8,5);
        syncIndicator.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        syncIcon=new Icon(a,"refresh",ACCENT);syncIndicator.addView(syncIcon,square(16));
        TextView syncLabel=w.text("Refreshing…",12,LILAC,true);w.pad(syncLabel,7,0,0,0);syncIndicator.addView(syncLabel);
        syncIndicator.setVisibility(View.GONE);root.addView(syncIndicator);
        message=w.text("",13,LILAC,false);message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        w.pad(message,16,9,16,9);message.setBackgroundColor(RAISED);root.addView(message);
        FrameLayout viewport=new FrameLayout(a);root.addView(viewport,new LinearLayout.LayoutParams(-1,0,1));
        stage=new FrameLayout(a);stage.setBackgroundColor(BG);viewport.addView(stage);
        nav=new LinearLayout(a){
            private final GradientDrawable marker=w.shape(ACCENT_SURFACE,16);
            @Override protected void dispatchDraw(android.graphics.Canvas canvas){
                if(navTarget>=0 && getChildCount()==4){
                    View first=getChildAt(0),last=getChildAt(3);
                    int left=Math.round(first.getLeft()+(last.getLeft()-first.getLeft())*navPosition/3f);
                    marker.setBounds(left,first.getTop(),left+first.getWidth(),first.getBottom());
                    marker.setStroke(w.dp(1),ACCENT);marker.draw(canvas);
                }
                super.dispatchDraw(canvas);
            }
        };GradientDrawable rail=w.shape(PANEL,24);rail.setStroke(w.dp(1),LINE);nav.setBackground(rail);w.pad(nav,7,5,7,5);
        FrameLayout.LayoutParams navParams=new FrameLayout.LayoutParams(-1,w.dp(62),Gravity.BOTTOM);navParams.setMargins(w.dp(14),0,w.dp(14),w.dp(10));viewport.addView(nav,navParams);
    }
    void status(){
        if(accountAvatar!=null){
            String name=a.accountPrefs().getString("nickname","");
            if(name.isEmpty())name=Market.accountName(a.data);
            String initial=name.substring(0,name.offsetByCodePoints(0,1)).toUpperCase(Locale.ROOT);
            if(!initial.contentEquals(accountAvatar.getText()))accountAvatar.setText(initial);
        }
        if(setupKey!=null)setupKey.setEnabled(!a.busy);
        boolean animate=motion(a);
        if(progress.isIndeterminate()!=animate){progress.setIndeterminate(animate);if(!animate)progress.setProgress(100);}
        boolean manual=a.manualSync;
        progress.setVisibility(!manual && (a.busy || !a.ready || lookupRunning && searching())?View.VISIBLE:View.GONE);
        syncIndicator.setVisibility(manual?View.VISIBLE:View.GONE);
        if(manual && animate && syncIcon.getAnimation()==null){
            android.view.animation.RotateAnimation spin=new android.view.animation.RotateAnimation(0,360,
                    android.view.animation.Animation.RELATIVE_TO_SELF,.5f,android.view.animation.Animation.RELATIVE_TO_SELF,.5f);
            spin.setDuration(850);spin.setRepeatCount(android.view.animation.Animation.INFINITE);
            spin.setInterpolator(new android.view.animation.LinearInterpolator());syncIcon.startAnimation(spin);
        }else if(!manual || !animate)syncIcon.clearAnimation();
        if(!message.getText().toString().equals(a.note))message.setText(a.note);
        message.setVisibility(a.note.isEmpty()?View.GONE:View.VISIBLE);
        if(unreadBadge!=null){
            int count=0;for(JSONObject e:Market.objects(a.alertState.optJSONArray("inbox")))if(!e.optBoolean("read"))count++;
            unreadBadge.setText(count>99?"99+":Integer.toString(count));unreadBadge.setVisibility(count==0?View.GONE:View.VISIBLE);
        }
        clock();
    }
    void clock(){
        if(refreshAction==null)return;
        long wait=a.remaining();
        String label=a.busy?"Syncing…":wait==0?"Refresh now":String.format(Locale.ROOT,"Refresh in %d:%02d",wait/60,wait%60);
        if(!label.contentEquals(refreshAction.getText()))refreshAction.setText(label);
        ((View)refreshAction.getParent().getParent()).setEnabled(!a.busy && wait==0);
    }
    void reset(){
        section=startupTab();query="";order=defaultSort();selected=null;positions.clear();
        gamePage=null;trail.clear();restoredId="";restoredTab="";
        revision++;localHits=Collections.emptyList();attempted="";searchStatus="";catalogResults=new JSONArray();catalogQuery="";listKey="";list=null;carouselX=0;
    }
    void restore(Bundle bundle){
        restoredActivity=true;settingsOffset=bundle.getInt("settingsOffset");
        section=bundle.getString("route",startupTab());
        order=bundle.getString("sort",defaultSort());query=bundle.getString("query","");
        listKey=bundle.getString("listKey","");int[] position=bundle.getIntArray("position");
        if(position!=null)positions.put(listKey,position);
        carouselX=bundle.getInt("carousel");
        restoredId=bundle.getString("gameId","");restoredTab=bundle.getString("gameTab","offers");
    }
    void save(Bundle bundle){
        bundle.putInt("settingsOffset",settingsScroll==null?0:settingsScroll.getScrollY());
        remember();bundle.putString("route",section);bundle.putString("sort",order);
        bundle.putString("query",query);bundle.putString("listKey",listKey);bundle.putIntArray("position",positions.get(listKey));bundle.putInt("carousel",carouselX);
        if(selected!=null){bundle.putString("gameId",Market.text(selected,"id"));bundle.putString("gameTab",gamePage==null?"offers":gamePage.tab);}
    }
    private void remember(){
        if(list!=null && list.getChildCount()>0)positions.put(listKey,new int[]{list.getFirstVisiblePosition(),list.getChildAt(0).getTop()});
        if(shelf!=null)carouselX=shelf.getScrollX();
    }
    void go(String destination){
        if(!a.signedIn)return;
        if("search".equals(destination) && !"search".equals(section)){
            returnFromSearch=section;openKeyboard=true;
        }
        if(!"search".equals(destination)){revision++;attempted="";localHits=Collections.emptyList();hideKeyboard();query="";catalogQuery="";catalogResults=new JSONArray();}
        selected=null;gamePage=null;trail.clear();section=destination;order=defaultSort();a.note="";render(true);
        a.refreshIfExpired();
        if("library".equals(destination))a.libraryPrices();
    }
    boolean back(){
        if(selected!=null){gamePage=null;if(!trail.isEmpty()){detail(trail.pop());}else{selected=null;render(true);}return true;}
        if("search".equals(section)){go(returnFromSearch);return true;}
        if(!"home".equals(section)){go("home");return true;}
        return false;
    }
    private void detail(JSONObject game){
        revision++;hideKeyboard();selected=game;gamePage=null;a.note="";render(true);a.detailPrices(game);
    }
    void openRelated(JSONObject item){
        if(selected!=null && Market.text(selected,"id").equals(Market.text(item,"id"))){a.notice("You’re viewing this game.");return;}
        if(selected!=null)trail.push(selected);
        JSONObject saved=findGame(Market.text(item,"id"));detail(saved==null?item:saved);
    }
    JSONObject findGame(String id){
        for(String src:Arrays.asList("wishlist","library","deals","trending","lows","best","free","ignored"))
            for(JSONObject item:Market.objects(a.data.optJSONArray(src)))if(id.equals(Market.text(item,"id")))return item;
        return null;
    }
    boolean isGame(GamePage page){return selected!=null && page==gamePage;}
    void relatedSearch(String term){
        go("search");if(search!=null){search.setText(term);search.setSelection(search.length());}
        hideKeyboard();
    }
    void offerError(JSONObject game,String message){
        if(gamePage!=null && gamePage.id.equals(Market.text(game,"id"))){gamePage.offerMessage=message;render(false);}
    }
    void keyChanged(){attempted="";catalogQuery="";catalogResults=new JSONArray();if(searching())queueSearch();if(gamePage!=null)gamePage.credentialsChanged();render(false);if(selected!=null)a.detailPrices(selected);}
    void detailResult(JSONObject game){
        if(selected!=null && selected.optString("id").equals(game.optString("id"))){selected=game;if(gamePage!=null)gamePage.offerMessage=Market.text(game,"offerWarning");render(false);}
    }
    private void hideKeyboard(){
        if(search!=null)((InputMethodManager)a.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(),0);
    }
    void render(boolean transition){
        if(a.ready && splashElapsed)finishSplash();
        if(!transition && a.signedIn && selected!=null && gamePage!=null && headerMode.equals("game")){
            gamePage.update(selected);status();return;
        }
        if(selected!=null && gamePage!=null)gamePage.remember();
        if(a.ready && a.signedIn && !restoredId.isEmpty()){selected=findGame(restoredId);restoredId="";}
        if(settingsScroll!=null){settingsOffset="settings".equals(section)?settingsScroll.getScrollY():0;settingsScroll=null;}
        remember();shelf=null;list=null;refreshAction=null;stage.removeAllViews();
        int intro=a.accountPrefs().getInt("introStep",0);
        boolean keyStep=a.signedIn && !a.personal && intro==1;
        setupKey=null;
        String mode=!a.signedIn || keyStep?"welcome":selected!=null?"game":section.equals("settings")?"settings":selected==null && section.equals("search")?"search":"browse";
        header.setVisibility(mode.equals("welcome")?View.GONE:View.VISIBLE);
        if(!mode.equals(headerMode)){makeHeader(mode);headerMode=mode;}
        nav.setVisibility(a.signedIn && !keyStep && selected==null && !section.equals("settings")?View.VISIBLE:View.GONE);
        if(!a.ready){if(splash==null && !restoredActivity)showSplash();}
        else if(a.storageUnavailable){
            LinearLayout recovery=page();
            addText(recovery,"Unlock your saved account",24,WHITE,true,26);
            addText(recovery,"Unlock your phone and retry to open your encrypted sign-in and pricing key.",15,MUTED,false,8);
            pageAction(recovery,"Retry",true,a::restoreAccount);
        }else if(!a.signedIn || keyStep){welcome(intro,keyStep);}
        else{
            if(nav.getVisibility()==View.VISIBLE)makeNav(transition);
            if(selected!=null)details();
            else if(section.equals("settings"))settings();
            else if(section.equals("alerts"))alerts();
            else browse();
        }
        status();
        if(transition && motion(a)){
            stage.animate().cancel();stage.setTranslationY(w.dp(5));
            stage.animate().translationY(0f).setInterpolator(EASE).setDuration(180).start();
        }
    }
    private void makeHeader(String mode){
        header.removeAllViews();search=null;unreadBadge=null;accountAvatar=null;
        if(mode.equals("settings") || mode.equals("game")){
            header.addView(w.iconButton("back","Back",this::back),square(44));
            header.addView(w.text(mode.equals("settings")?"Account & Settings":"Game details",18,WHITE,true),w.flex());
            header.addView(w.iconButton("refresh","Refresh prices and details",a::manualRefresh),square(44));
            return;
        }
        if(mode.equals("search")){
            FrameLayout back=w.iconButton("back","Back",this::back);header.addView(back,square(46));
            search=new EditText(a);search.setSingleLine();search.setTextColor(WHITE);search.setTextSize(16);
            search.setHint(a.personal?"Search games":"Search your games and deals");search.setHintTextColor(MUTED);
            search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);search.setBackground(w.shape(PANEL,7));w.pad(search,12,0,10,0);
            search.setText(query);search.setSelection(search.length());
            header.addView(search,new LinearLayout.LayoutParams(0,w.dp(40),1));
            header.addView(w.iconButton("close","Clear search",()->search.setText("")),square(44));
            search.addTextChangedListener(new TextWatcher(){
                public void beforeTextChanged(CharSequence s,int start,int count,int after){}
                public void onTextChanged(CharSequence s,int start,int before,int count){
                    query=s.toString();catalogResults=new JSONArray();catalogQuery="";attempted="";queueSearch();
                }
                public void afterTextChanged(Editable e){}
            });
            search.setOnEditorActionListener((v,id,event)->{if(id==EditorInfo.IME_ACTION_SEARCH){
                hideKeyboard();return true;}return false;});
            queueSearch();
            if(openKeyboard){
                openKeyboard=false;EditText field=search;
                field.post(()->{field.requestFocus();((InputMethodManager)a.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(field,InputMethodManager.SHOW_IMPLICIT);});
            }
            return;
        }
        if(!mode.equals("welcome"))header.addView(w.iconButton("menu","Open navigation menu",this::menu),square(38));
        LinearLayout brand=w.row();w.pad(brand,4,0,4,0);
        ImageView logo=brandLogo(40);logo.setElevation(w.dp(5));brand.addView(logo,square(40));
        {
            LinearLayout name=w.column();w.pad(name,8,0,0,0);
            TextView word=wordmark(19);name.addView(word);
            TextView sub=w.text("PC DEALS",9,MUTED,true);sub.setLetterSpacing(.14f);name.addView(sub);brand.addView(name);
        }
        brand.setContentDescription("RupeeRaid home");header.addView(brand);
        if(mode.equals("welcome"))return;
        w.action(brand,w.shape(BAR,6),()->go("home"));
        View spacer=new View(a);header.addView(spacer,new LinearLayout.LayoutParams(0,1,1));
        header.addView(w.iconButton("search","Search games",()->go("search")),square(42));
        FrameLayout bell=w.iconButton("bell","Price alert inbox",()->go("alerts"));
        unreadBadge=w.text("",9,INK,true);unreadBadge.setGravity(Gravity.CENTER);w.pad(unreadBadge,3,0,3,0);unreadBadge.setBackground(w.shape(LILAC,8));
        bell.addView(unreadBadge,new FrameLayout.LayoutParams(-2,w.dp(14),Gravity.TOP|Gravity.END));header.addView(bell,square(42));
        accountAvatar=w.text("",15,ACCENT,true);accountAvatar.setGravity(Gravity.CENTER);accountAvatar.setContentDescription("Account settings");
        w.action(accountAvatar,w.shape(PANEL,13),()->go("settings"));header.addView(accountAvatar,square(42));
    }
    private ImageView brandLogo(int size){
        return w.logo(a.getSharedPreferences("appearance",0).getString("logo",LOGOS[0]),size);
    }
    private TextView wordmark(int size){
        TextView word=w.text("",size,WHITE,true);
        android.text.SpannableString name=new android.text.SpannableString("RupeeRaid");
        name.setSpan(new android.text.style.CharacterStyle(){
            public void updateDrawState(android.text.TextPaint paint){
                float start=paint.measureText("Rupee");
                paint.setShader(new android.graphics.LinearGradient(start,0,start+paint.measureText("Raid"),0,LILAC,SECONDARY,android.graphics.Shader.TileMode.CLAMP));
            }
        },5,9,0);
        word.setText(name);word.setLetterSpacing(-.045f);return word;
    }
    private LinearLayout.LayoutParams square(int size){return new LinearLayout.LayoutParams(w.dp(size),w.dp(size));}
    private void menu(){
        android.app.Dialog sheet=new android.app.Dialog(a);
        ScrollView scroll=new ScrollView(a);LinearLayout body=w.column();w.pad(body,18,10,18,22);scroll.addView(body);
        body.setBackground(w.shape(PANEL,26));
        View handle=new View(a);handle.setBackground(w.shape(MUTED,4));
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(w.dp(40),w.dp(4));hp.gravity=Gravity.CENTER_HORIZONTAL;body.addView(handle,hp);
        String name=a.accountPrefs().getString("nickname","").trim();
        LinearLayout profile=w.row();w.pad(profile,12,10,12,10);
        LinearLayout.LayoutParams profileParams=new LinearLayout.LayoutParams(-1,-2);profileParams.topMargin=w.dp(12);
        body.addView(profile,profileParams);
        profile.addView(brandLogo(40),square(40));
        LinearLayout identity=w.column();w.pad(identity,10,0,4,0);profile.addView(identity,w.flex());
        String display=name.isEmpty()?Market.accountName(a.data):name;
        boolean generic=name.isEmpty() && display.equals("IsThereAnyDeal member");
        TextView profileName=w.text(generic?"Your profile":display,16,WHITE,true);
        profileName.setShadowLayer(w.dp(4),0,0,(ACCENT & 0x00ffffff) | 0xaa000000);
        w.ellipsis(profileName,2);w.pad(profileName,2,2,2,2);identity.addView(profileName);
        identity.addView(w.text("IsThereAnyDeal member · "+PricingRegion.current.name(),12,MUTED,false));
        TextView badge=w.text("SETTINGS  ›",10,LILAC,true);w.pad(badge,7,3,7,3);
        GradientDrawable badgeFill=w.shape(ACCENT_SURFACE,7);badgeFill.setStroke(w.dp(1),ACCENT);badge.setBackground(badgeFill);
        profile.addView(badge);w.action(profile,w.shape(RAISED,14),()->{sheet.dismiss();go("settings");});
        LinearLayout actions=w.row();w.pad(actions,0,10,0,8);body.addView(actions);
        for(String[] item:new String[][]{{"refresh","Refresh prices"},{"bell","Price alerts"}}){
            boolean refresh=item[0].equals("refresh");LinearLayout cell=w.row();w.pad(cell,10,8,10,8);
            cell.setMinimumHeight(w.dp(48));cell.addView(new Icon(a,item[0],refresh?ACCENT:SECONDARY),square(18));
            TextView label=w.text(item[1],13,WHITE,true);w.pad(label,8,0,0,0);cell.addView(label);
            w.action(cell,w.shape(RAISED,12),()->{sheet.dismiss();if(refresh)a.manualRefresh();else go("alerts");});
            LinearLayout.LayoutParams lp=w.flex();if(!refresh)lp.setMarginStart(w.dp(8));actions.addView(cell,lp);
        }
        LinearLayout links=w.column();links.setBackground(w.shape(PANEL,14));w.pad(links,6,1,6,1);body.addView(links);
        for(String key:new String[]{"trending","lows","wishlist","library","replay"}){
            JSONArray entries=a.data.optJSONArray(key);
            String value=key.equals("lows")?"HL":entries!=null && (key.equals("wishlist") || key.equals("library"))
                    ?Integer.toString(entries.length())+(key.equals("library")?" PC":""):"";
            String label=key.equals("replay")?"Replay loading screen":key.equals("wishlist")?"Wishlist & target alerts":
                    key.equals("library")?"PC library":key.equals("trending")?"Discover & trending":title(key);
            settingRow(links,label,value,()->{sheet.dismiss();if(key.equals("replay"))showSplash();else go(key);});
        }
        LinearLayout extra=w.column();
        settingRow(links,"More options","",()->extra.setVisibility(extra.getVisibility()==View.GONE?View.VISIBLE:View.GONE));
        extra.setVisibility(View.GONE);links.addView(extra);
        for(String key:new String[]{"deals","best","free","ignored"})
            settingRow(extra,title(key),"",()->{sheet.dismiss();go(key);});
        sheet.setContentView(scroll);android.view.Window window=sheet.getWindow();
        window.setBackgroundDrawableResource(android.R.color.transparent);window.setGravity(Gravity.BOTTOM);window.setLayout(-1,-2);
        window.setDimAmount(.68f);sheet.show();
        if(motion(a)){scroll.setTranslationY(w.dp(48));scroll.animate().translationY(0).setInterpolator(EASE).setDuration(320).start();}
    }
    private void makeNav(boolean transition){
        List<String> destinations=Arrays.asList("home","deals","wishlist","library");
        int target=destinations.indexOf(section);
        if(Arrays.asList("best","lows","trending","free","budget").contains(section))target=1;
        if(section.equals("ignored"))target=3;
        int wishlistSize=a.data.optJSONArray("wishlist")==null?0:a.data.optJSONArray("wishlist").length();
        if(target==navTarget && wishlistSize==navCount && nav.getChildCount()>0)return;
        if(target!=navTarget){
            if(navSlide!=null)navSlide.cancel();
            if(target>=0 && navTarget>=0 && transition && motion(a)){
                navSlide=ValueAnimator.ofFloat(navPosition,target);
                navSlide.setDuration(210);navSlide.setInterpolator(EASE);
                navSlide.addUpdateListener(anim->{navPosition=(float)anim.getAnimatedValue();nav.invalidate();});navSlide.start();
            }else navPosition=target;
        }
        navTarget=target;navCount=wishlistSize;
        nav.removeAllViews();
        for(String dest:destinations){
            boolean current=destinations.indexOf(dest)==target;
            LinearLayout item=w.column();item.setGravity(Gravity.CENTER);
            GradientDrawable tab=w.shape(Color.TRANSPARENT,16);
            w.action(item,tab,()->go(dest));
            FrameLayout pill=new FrameLayout(a);
            pill.addView(new Icon(a,dest,current?LILAC:MUTED),new FrameLayout.LayoutParams(w.dp(20),w.dp(20),Gravity.CENTER));
            if(dest.equals("wishlist") && a.data.optJSONArray("wishlist")!=null){
                int n=a.data.optJSONArray("wishlist").length();if(n>0){TextView count=w.text(n>99?"99+":String.valueOf(n),9,INK,true);
                    w.pad(count,3,0,3,0);count.setBackground(w.shape(LILAC,9));pill.addView(count,new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.END));}
            }
            item.addView(pill,new LinearLayout.LayoutParams(w.dp(48),w.dp(26)));
            TextView text=w.text(dest.equals("deals")?"Deals":title(dest),11,current?LILAC:MUTED,current);
            text.setGravity(Gravity.CENTER);w.pad(text,0,2,0,0);item.addView(text);
            item.setContentDescription(text.getText());item.setSelected(current);
            nav.addView(item,new LinearLayout.LayoutParams(0,-1,1));
        }
    }
    private static String title(String key){
        switch(key){
            case "home":return "Discover";case "deals":return "New deals";case "trending":return "Trending games";
            case "best":return "Best discounts";case "budget":return "Under "+money(30000);case "lows":return "Historical lows";case "free":return "Free offers";
            case "wishlist":return "Wishlist";case "library":return "Library";case "ignored":return "Ignored games";
            case "alerts":return "Price alerts";case "settings":return "Account";case "search":return "Search results";default:return key;
        }
    }
    private void showSplash(){
        if(splash!=null)return;
        final FrameLayout cover=new FrameLayout(a);
        splash=cover;splashElapsed=false;
        cover.setClickable(true);cover.setFocusable(true);
        root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        GradientDrawable glow=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{ACCENT_SURFACE,PANEL,BG});
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);glow.setGradientCenter(.5f,.43f);
        glow.setGradientRadius(w.dp(270));cover.setBackground(glow);
        LinearLayout box=w.column();box.setGravity(Gravity.CENTER);
        cover.addView(box,new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER));
        FrameLayout rim=new FrameLayout(a);GradientDrawable edge=w.shape(ACCENT_SURFACE,26);
        edge.setStroke(w.dp(1),ACCENT);rim.setBackground(edge);box.addView(rim,square(94));
        ImageView icon=brandLogo(84);
        rim.addView(icon,new FrameLayout.LayoutParams(w.dp(84),w.dp(84),Gravity.CENTER));
        TextView title=wordmark(29);w.pad(title,0,19,0,0);box.addView(title,new LinearLayout.LayoutParams(-2,-2));
        FrameLayout track=new FrameLayout(a);track.setBackground(w.shape(0xff192027,4));
        LinearLayout.LayoutParams bar=new LinearLayout.LayoutParams(w.dp(180),w.dp(4));bar.topMargin=w.dp(18);box.addView(track,bar);
        View fill=new View(a);GradientDrawable gradient=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{ACCENT,SECONDARY});
        gradient.setCornerRadius(w.dp(4));fill.setBackground(gradient);track.addView(fill,new FrameLayout.LayoutParams(-1,-1));
        TextView label=w.text("Opening your saved games…",13,MUTED,false);w.pad(label,0,13,0,0);label.setGravity(Gravity.CENTER);box.addView(label);
        int hz=Math.round(a.getWindowManager().getDefaultDisplay().getRefreshRate());
        TextView footer=w.text("●  "+PricingRegion.current.name().toUpperCase(Locale.ROOT)+" · "+PricingRegion.current.currency+" · "+hz+"Hz ENGINE",10,MUTED,true);
        footer.setGravity(Gravity.CENTER);footer.setLetterSpacing(.1f);
        FrameLayout.LayoutParams foot=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);
        cover.addView(footer,foot);
        cover.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom()+w.dp(14));return i;});
        a.addContentView(cover,new ViewGroup.LayoutParams(-1,-1));cover.requestApplyInsets();
        boolean animate=motion(a);
        if(animate){
            fill.setPivotX(0);fill.setScaleX(0);fill.animate().scaleX(1).setDuration(2050).start();
            rim.setScaleX(.84f);rim.setScaleY(.84f);rim.animate().scaleX(1).scaleY(1).setInterpolator(EASE).setDuration(480).start();
        }
        cover.postDelayed(()->{if(splash==cover){splashElapsed=true;if(a.ready)finishSplash();}},animate?2050:250);
    }
    private void finishSplash(){
        if(splash==null || !splashElapsed)return;
        FrameLayout cover=splash;splashElapsed=false;
        Runnable remove=()->{
            if(cover.getParent() instanceof ViewGroup)((ViewGroup)cover.getParent()).removeView(cover);
            root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);splash=null;
        };
        if(motion(a))cover.animate().alpha(0).setDuration(220).withEndAction(remove).start();else remove.run();
    }
    private void centerText(String value){
        TextView t=w.text(value,16,MUTED,false);t.setGravity(Gravity.CENTER);stage.addView(t);
    }
    private void browse(){
        listKey=section+"|PC|"+order+"|"+query;
        list=new ListView(a);list.setDivider(null);list.setCacheColorHint(Color.TRANSPARENT);
        if(android.os.Build.VERSION.SDK_INT>=35 && a.getSharedPreferences("appearance",0).getBoolean("highRefresh",true)){
            list.setOnScrollListener(new AbsListView.OnScrollListener(){
                public void onScroll(AbsListView view,int first,int visible,int total){}
                public void onScrollStateChanged(AbsListView view,int state){
                    view.setRequestedFrameRate(state==SCROLL_STATE_IDLE?View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT:View.REQUESTED_FRAME_RATE_CATEGORY_HIGH);
                }
            });
        }
        list.setVerticalScrollBarEnabled(false);list.setSelector(android.R.color.transparent);
        list.setClipToPadding(false);w.pad(list,0,0,0,84);
        list.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);stage.addView(list);
        DealFeed feed=buildFeed();list.setAdapter(feed);
        int[] saved=positions.get(listKey);
        if(saved!=null)list.setSelectionFromTop(Math.min(saved[0],Math.max(0,feed.getCount()-1)),saved[1]);
    }
    private void updateSearchList(){
        if(list==null || !searching())return;
        listKey="search|PC|"+order+"|"+query;
        list.setAdapter(buildFeed());
    }
    private DealFeed buildFeed(){
        DealFeed feed=new DealFeed(w,Market.ALL,this::detail,g->a.gameRule(Market.text(g,"id"))!=null,g->AlertEditor.open(a,g));
        if(section.equals("home")){
            feed.add(quickFilters());
            if(a.data.optLong("saved")==0){
                if(a.loadingFirstFeed){
                    feed.add(w.loadingPosters());
                    for(int i=0;i<4;i++)feed.add(w.loadingRows("Loading deals"));
                }else feed.add(empty("Sync your game deals",true));
                return feed;
            }
            List<JSONObject> trending=rows("trending","Default");
            if(!trending.isEmpty()){
                feed.add(heading("Trending games","trending"));
                feed.add(carousel(trending));
            }
            boolean any=false;
            for(String key:Arrays.asList("deals","best","lows","wishlist")){
                List<JSONObject> items=rows(key,"Default");
                if(items.isEmpty())continue;
                any=true;feed.add(heading(title(key),key));feed.addGames(items,6);
            }
            if(trending.isEmpty() && !any)feed.add(empty(emptyMessage(),false));
            feed.add(attribution());return feed;
        }
        List<JSONObject> entries=section.equals("search")?searchRows():rows(section,order);
        feed.add(listHeading(entries.size()));
        boolean grid=section.equals("library") && a.getSharedPreferences("appearance",0).getBoolean("libraryGrid",true);
        if(section.equals("library")){
            long total=0;int priced=0;
            for(JSONObject game:entries){JSONObject q=Market.best(game,Market.ALL,drm);
                if(q!=null && q.optLong("regular",-1)>=0){total+=q.optLong("regular");priced++;}}
            LinearLayout stats=w.row();w.pad(stats,16,3,16,10);
            for(String value:new String[]{"Owned games\n"+entries.size()+" PC","Retail value · "+priced+" priced\n"+(priced==0?"—":total==0?"0 "+PricingRegion.current.currency:money(total))}){
                TextView tile=w.text(value,14,WHITE,true);w.pad(tile,10,8,10,8);tile.setBackground(w.shape(PANEL,16));
                LinearLayout.LayoutParams tileLayout=w.flex();
                if(stats.getChildCount()>0)tileLayout.setMarginStart(w.dp(8));
                stats.addView(tile,tileLayout);
            }
            feed.add(stats);

        }
        if(section.equals("wishlist")){
            JSONObject universal=a.alertState.optJSONObject("universal");
            String label=universal==null?"Set one alert for all wishlist games":"Wishlist-wide alert  ·  "+AlertJob.label(universal.optString("mode"));
            feed.add(insetButton(label,()->AlertEditor.openUniversal(a)));
        }
        if(section.equals("search") && !a.personal && query.trim().length()>=2)
            feed.add(insetButton("Enable full catalog with a personal key",a::editKey));
        if(section.equals("ignored")){
            String other=section.equals("library")?"ignored":"library";
            feed.add(insetButton(other.equals("ignored")?"View ignored games":"View library",()->go(other)));
        }
        if(entries.isEmpty() && section.equals("search") && searchStatus.startsWith("Searching"))feed.add(w.loadingRows(searchStatus));
        else if(entries.isEmpty() && a.loadingFirstFeed && !section.equals("search")){
            for(int i=0;i<5;i++)feed.add(w.loadingRows("Loading games"));
        }else if(entries.isEmpty())feed.add(empty(emptyMessage(),a.data.optLong("saved")==0));
        else if(grid)feed.addLibrary(entries);
        else feed.addGames(entries,entries.size(),section.equals("wishlist"));
        feed.add(attribution());return feed;
    }
    private List<JSONObject> rows(String key,String sort){
        boolean hideOwned=a.getSharedPreferences("appearance",0).getBoolean("hideOwned",false);
        return Market.rows(a.data,key,Market.ALL,"",sort,hideOwned,drm);
    }
    private List<JSONObject> searchRows(){
        LinkedHashMap<String,JSONObject> matches=new LinkedHashMap<>();
        for(JSONObject game:Market.objects(catalogResults))matches.put(Market.text(game,"id"),game);
        for(JSONObject game:localHits)matches.putIfAbsent(Market.text(game,"id"),game);
        List<JSONObject> rows=new ArrayList<>(matches.values());
        if("Default".equals(order))return rows;
        JSONObject wrap=new JSONObject();
        try{wrap.put("results",new JSONArray(rows));}catch(Exception ignored){}
        return Market.rows(wrap,"search",Market.ALL,"",order,false,drm);
    }
    private String emptyMessage(){
        if(a.data.optLong("saved")==0)return a.busy?"Syncing your IsThereAnyDeal account…":"Refresh to load your games.";
        if(section.equals("search"))return searchStatus.isEmpty()?"No games match this search.":searchStatus;
        if(section.equals("lows"))return "No confirmed historical lows in the current feed.";
        if(section.equals("free"))return "No verified free offers in the current feed.";
        return "This list is empty.";
    }
    private LinearLayout empty(String s,boolean retry){
        LinearLayout c=w.column();w.pad(c,16,20,16,20);c.addView(w.text(s,15,MUTED,false));
        if(retry && !a.busy)c.addView(w.button("Refresh",false,a::manualRefresh));
        return c;
    }
    private View attribution(){
        TextView t=w.text("Prices from IsThereAnyDeal  ·  "+PricingRegion.current.name()+" / "+PricingRegion.current.currency,11,MUTED,false);w.pad(t,16,20,16,20);return t;
    }
    private LinearLayout heading(String name,String target){
        LinearLayout h=w.row();w.pad(h,15,12,10,6);h.setMinimumHeight(w.dp(52));
        TextView t=w.text(name,21,WHITE,true);h.addView(t,w.flex());
        TextView all=w.text("See all  ›",13,LILAC,true);all.setGravity(Gravity.CENTER);w.pad(all,6,2,6,2);
        all.setMinHeight(w.dp(40));all.setContentDescription("See all "+name.toLowerCase(Locale.ROOT));
        w.action(all,w.shape(BG,6),()->go(target));h.addView(all);return h;
    }
    private LinearLayout listHeading(int count){
        LinearLayout h=w.row();w.pad(h,15,13,10,12);
        LinearLayout stack=w.column();stack.addView(w.text(section.equals("search") && catalogQuery.isEmpty()?"Suggestions":title(section),25,WHITE,true));
        String countLabel=count+(count==1?" game":" games");
        if(section.equals("wishlist")){
            int sale=0;long saved=0;
            for(JSONObject game:Market.objects(a.data.optJSONArray("wishlist"))){JSONObject offer=Market.best(game,Market.ALL,drm);
                if(offer!=null && offer.optLong("regular",-1)>offer.optLong("paise",-1)){sale++;saved+=offer.optLong("regular")-offer.optLong("paise");}}
            if(sale>0)countLabel+="  ·  "+sale+" on sale · Save "+money(saved);
        }
        if(section.equals("search") && catalogQuery.isEmpty())countLabel+=" in saved lists";
        if(section.equals("search") && !catalogQuery.isEmpty())countLabel+=" in the ITAD PC catalog";
        if(section.equals("search") && !searchStatus.isEmpty() && count>0)countLabel=searchStatus;
        TextView sub=w.text(countLabel,12,MUTED,false);w.pad(sub,0,4,0,0);stack.addView(sub);h.addView(stack,w.flex());
        LinearLayout sort=w.row();w.pad(sort,10,8,9,8);sort.addView(new Icon(a,"sort",LILAC),square(16));
        TextView sortLabel=w.text(order.equals("Default")?"Sort":order,13,LILAC,true);w.pad(sortLabel,5,0,0,0);sort.addView(sortLabel);
        sort.setMinimumHeight(w.dp(40));sort.setContentDescription("Sort games");
        w.action(sort,w.shape(PANEL,7),()->chooseSort("Sort games",order,choice->{order=choice;render(false);}));
        if(section.equals("library")){
            boolean grid=a.getSharedPreferences("appearance",0).getBoolean("libraryGrid",true);
            String layout=grid?"Switch to list":"Switch to grid";
            h.addView(w.iconButton(grid?"list":"grid",layout,()->{
                a.getSharedPreferences("appearance",0).edit().putBoolean("libraryGrid",!grid).apply();render(false);
            }),square(44));
        }
        h.addView(sort);return h;
    }
    private String defaultSort(){return a.getSharedPreferences("appearance",0).getString("defaultSort","Default");}
    private void chooseSort(String title,String selected,java.util.function.Consumer<String> apply){
        chooseOption(title,selected,new String[]{"Default","Recently updated","Price","Discount","Title"},apply);
    }
    private void chooseOption(String title,String selected,String[] choices,java.util.function.Consumer<String> apply){
        android.app.Dialog dialog=new android.app.Dialog(a);
        LinearLayout body=w.column();body.setBackground(w.shape(PANEL,20));w.pad(body,16,14,16,16);
        addText(body,title,20,WHITE,true,0);
        for(String choice:choices){
            boolean checked=choice.equals(selected);
            LinearLayout row=w.row();w.pad(row,14,10,14,10);row.setMinimumHeight(w.dp(48));row.setSelected(checked);
            row.addView(w.text(choice,15,checked?LILAC:WHITE,true),w.flex());
            if(checked)row.addView(w.text("✓",17,LILAC,true));
            w.action(row,w.shape(checked?ACCENT_SURFACE:RAISED,12),()->{dialog.dismiss();apply.accept(choice);});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=w.dp(6);body.addView(row,lp);
        }
        dialog.setContentView(body);android.view.Window window=dialog.getWindow();
        window.setBackgroundDrawableResource(android.R.color.transparent);window.setGravity(Gravity.BOTTOM);
        window.setLayout(-1,-2);dialog.show();
    }

    private void appearancePicker(boolean themes){
        android.content.SharedPreferences prefs=a.getSharedPreferences("appearance",0);
        android.app.Dialog dialog=new android.app.Dialog(a);
        ScrollView scroll=new ScrollView(a);
        LinearLayout body=w.column();body.setBackground(w.shape(PANEL,20));w.pad(body,16,16,16,16);scroll.addView(body);
        addText(body,themes?"App theme":"App icon",20,WHITE,true,0);
        for(int i=0;i<(themes?AppTheme.values().length:LOGOS.length);i++){
            AppTheme theme=themes?AppTheme.values()[i]:AppTheme.saved(a);
            String label=themes?theme.label:LOGOS[i];
            boolean selected=themes?theme==AppTheme.saved(a):label.equals(prefs.getString("logo",LOGOS[0]));
            LinearLayout row=w.row();w.pad(row,12,8,12,8);row.setMinimumHeight(w.dp(48));
            if(themes){
                for(int color:theme.swatch(a)){
                    View dot=new View(a);GradientDrawable fill=w.shape(color,10);fill.setStroke(w.dp(1),LINE);dot.setBackground(fill);
                    row.addView(dot,square(15));
                }
            }else row.addView(w.logo(label,32),square(32));
            TextView caption=w.text(label,15,WHITE,true);w.pad(caption,12,0,0,0);row.addView(caption,w.flex());
            if(selected)row.addView(w.text("✓",17,LILAC,true));
            row.setSelected(selected);
            w.action(row,w.shape(selected?ACCENT_SURFACE:RAISED,12),()->{
                dialog.dismiss();if(selected)return;
                if(themes){
                    boolean black=prefs.getBoolean("blackCanvas","AMOLED".equals(prefs.getString("theme","Midnight")));
                    prefs.edit().putBoolean("blackCanvas",black).putString("theme",theme.key).apply();a.recreate();
                }else{LauncherIcons.select(a,label);prefs.edit().putString("logo",label).apply();headerMode="";render(false);}
            });
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=w.dp(6);body.addView(row,lp);
        }
        dialog.setContentView(scroll);android.view.Window window=dialog.getWindow();
        window.setBackgroundDrawableResource(android.R.color.transparent);window.setGravity(Gravity.BOTTOM);
        window.setLayout(-1,-2);dialog.show();
        if(motion(a)){body.setTranslationY(w.dp(18));body.animate().translationY(0).setInterpolator(EASE).setDuration(240).start();}
    }
    private void chooseRegion(){
        List<String> countries=new ArrayList<>(Arrays.asList(Locale.getISOCountries()));
        countries.sort(Comparator.comparing(code->new Locale("",code).getDisplayCountry(),String.CASE_INSENSITIVE_ORDER));
        String[] labels=new String[countries.size()];
        for(int i=0;i<labels.length;i++)labels[i]=new Locale("",countries.get(i)).getDisplayCountry()+"  ·  "+countries.get(i);
        new AlertDialog.Builder(a).setTitle("Pricing region").setSingleChoiceItems(labels,countries.indexOf(PricingRegion.current.country),
                (dialog,i)->{dialog.dismiss();a.changeRegion(countries.get(i));}).setNegativeButton("Cancel",null).show();
    }

    void wishlistBell(String id){
        go("wishlist");
        if(list==null)return;
        for(int i=0;i<list.getCount();i++){
            Object item=list.getItemAtPosition(i);
            if(item instanceof JSONObject && id.equals(Market.text((JSONObject)item,"id"))){list.setSelection(i);break;}
        }
        android.widget.Toast.makeText(a,"Tap this game’s Wishlist bell to edit its alert.",android.widget.Toast.LENGTH_SHORT).show();
    }
    private View quickFilters(){
        HorizontalScrollView scroll=new HorizontalScrollView(a);scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row=w.row();w.pad(row,16,5,6,10);scroll.addView(row);
        String[][] filters={{"All","home"},{"Historical lows","lows"},{"Under "+money(30000),"budget"},{"Top discounts","best"},{"Free","free"}};
        for(String[] item:filters){
            boolean current=section.equals(item[1]);TextView chip=w.text(item[0],13,current?INK:MUTED,true);w.pad(chip,14,8,14,8);
            w.action(chip,w.shape(current?ACCENT:PANEL,999),()->go(item[1]));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.rightMargin=w.dp(9);row.addView(chip,lp);
        }return scroll;
    }
    private View carousel(List<JSONObject> games){
        HorizontalScrollView rail=new HorizontalScrollView(a);rail.setHorizontalScrollBarEnabled(false);rail.setFillViewport(false);
        LinearLayout cards=w.row();w.pad(cards,14,0,4,4);rail.addView(cards);
        int width=Math.min(216,Math.max(150,(int)(a.getResources().getConfiguration().screenWidthDp*.44)));
        for(int i=0;i<Math.min(9,games.size());i++){
            JSONObject game=games.get(i),quote=Market.best(game,Market.ALL,drm);
            LinearLayout card=w.column();card.setClipToOutline(true);w.action(card,w.shape(PANEL,20),()->detail(game));
            FrameLayout frame=new FrameLayout(a);card.addView(frame,new LinearLayout.LayoutParams(-1,w.dp((int)(width*1.5))));
            ImageView art=w.artView();frame.addView(art,new FrameLayout.LayoutParams(-1,-1));w.art(art,game,"boxart","banner400","banner300");
            View shade=new View(a);shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x00000000,(BG & 0x00ffffff) | 0xea000000}));
            frame.addView(shade,new FrameLayout.LayoutParams(-1,w.dp(56),Gravity.BOTTOM));
            TextView rank=w.text("#"+(i+1),12,WHITE,true);w.pad(rank,7,3,7,3);rank.setBackground(w.shape(BAR,5));
            FrameLayout.LayoutParams rp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.START);rp.setMargins(w.dp(9),w.dp(8),0,0);frame.addView(rank,rp);
            if(quote!=null){
                if(Market.supports(quote,"PC")){
                    TextView tag=w.text("PC",10,WHITE,true);w.pad(tag,6,3,6,3);tag.setBackground(w.shape(BAR,4));
                    FrameLayout.LayoutParams tp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.END);tp.setMargins(0,w.dp(8),w.dp(8),0);frame.addView(tag,tp);
                }
            }
            LinearLayout caption=w.column();w.pad(caption,10,9,9,10);card.addView(caption);
            TextView title=w.text(Market.text(game,"title"),15,WHITE,true);w.ellipsis(title,1);caption.addView(title);
            if(quote!=null)addText(caption,Market.text(quote,"shop"),12,MUTED,false,4);
            LinearLayout priceLine=w.row();w.pad(priceLine,0,7,0,0);caption.addView(priceLine);
            Market.LowPeriod tier=Market.lowPeriod(quote);
            TextView price=w.text(quote==null?"—":money(quote.optLong("paise")),17,WHITE,true);w.priceStyle(price,tier);
            priceLine.addView(price,w.flex());
            LinearLayout badges=w.row();caption.addView(badges);
            if(tier!=Market.LowPeriod.NONE){TextView mark=w.badge(tier.badge,false);mark.setContentDescription(tier.description);w.tierBadge(mark,tier,true);w.addBadge(badges,mark);}
            if(Market.storeLow(quote)){TextView mark=w.badge("S",false);mark.setContentDescription("Store low");w.tierBadge(mark,Market.LowPeriod.YEAR,true);w.addBadge(badges,mark);}
            if(quote!=null && quote.optInt("cut")>0){TextView mark=w.badge("−"+quote.optInt("cut")+"%",false);w.tierBadge(mark,tier,false);w.addBadge(badges,mark);}
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(w.dp(width),-2);cp.setMarginEnd(w.dp(14));cards.addView(card,cp);
        }
        int x=carouselX;rail.post(()->rail.scrollTo(x,0));shelf=rail;
        LinearLayout block=w.column();block.addView(rail);
        LinearLayout dots=w.row();dots.setGravity(Gravity.CENTER);w.pad(dots,0,6,0,4);block.addView(dots);
        int count=cards.getChildCount();
        for(int i=0;i<count;i++){
            View dot=new View(a);dot.setBackground(w.shape(i==0?ACCENT:LINE,2));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(w.dp(10),w.dp(3));lp.rightMargin=w.dp(3);dots.addView(dot,lp);
        }
        final int[] marked={0};
        rail.setOnScrollChangeListener((View v,int sx,int sy,int prevX,int prevY)->{
            int range=Math.max(1,cards.getWidth()-rail.getWidth());
            int active=Math.min(count-1,Math.round((float)sx*(count-1)/range));
            if(marked[0]!=active){
                dots.getChildAt(marked[0]).getBackground().setTint(LINE);
                dots.getChildAt(active).getBackground().setTint(ACCENT);marked[0]=active;
            }
        });
        return block;
    }
    private View insetButton(String label,Runnable click){
        LinearLayout wrap=w.column();w.pad(wrap,14,0,14,10);wrap.addView(w.button(label,false,click));return wrap;
    }
    private LinearLayout page(){
        ScrollView scroll=new ScrollView(a);scroll.setVerticalScrollBarEnabled(false);LinearLayout page=w.column();
        w.pad(page,16,18,16,24);
        scroll.addView(page);
        FrameLayout.LayoutParams bounds=new FrameLayout.LayoutParams(-1,-1);
        if(nav.getVisibility()==View.VISIBLE) bounds.bottomMargin=w.dp(74);
        stage.addView(scroll,bounds);
        return page;
    }
    private LinearLayout panel(LinearLayout page){
        LinearLayout panel=w.column();w.pad(panel,15,14,15,14);panel.setBackground(w.shape(PANEL,10));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=w.dp(12);page.addView(panel,p);return panel;
    }
    private void addText(LinearLayout box,String text,int size,int color,boolean bold,int top){
        TextView v=w.text(text,size,color,bold);w.pad(v,0,top,0,0);box.addView(v);
    }
    private TextView pageAction(LinearLayout box,String label,boolean primary,Runnable run){
        TextView v=w.button(label,primary,run);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.topMargin=w.dp(8);box.addView(v,p);return v;
    }
    void refreshDetails(){
        attempted="";catalogQuery="";
        if(gamePage!=null)gamePage.credentialsChanged();
    }
    void refreshSelectedPrices(){
        if(selected!=null)a.detailPrices(selected);
    }
    void updated(){
        if(searching())queueSearch();
        // Keep an open game visible when a background refresh replaces its source data.
        if(selected!=null)for(String key:Arrays.asList("wishlist","deals","trending","lows","best","free","library","ignored")){
            boolean found=false;
            for(JSONObject g:Market.objects(a.data.optJSONArray(key)))if(g.optString("id").equals(selected.optString("id"))){
                if(g.optJSONArray("offers")!=null && (g.optJSONArray("offers").length()>0 || catalogQuery.isEmpty()))selected=g;
                found=true;break;
            }
            if(found)break;
        }
        render(false);
        if(section.equals("library") && selected==null)a.libraryPrices();
    }
    private void details(){
        if(gamePage==null){
            gamePage=new GamePage(this,a,w,Market.text(selected,"id"));
            if(!restoredTab.isEmpty()){gamePage.tab=Arrays.asList("lows","packs","bundles").contains(restoredTab)?"offers":restoredTab;restoredTab="";}
        }
        gamePage.mount(page(),selected,Market.ALL,title(section));
    }
    private void alerts(){
        LinearLayout page=page();addText(page,"Price alerts",26,WHITE,true,0);
        JSONObject rules=a.alertState.optJSONObject("rules");
        JSONArray inbox=a.alertState.optJSONArray("inbox");int count=rules==null?0:rules.length(),unread=0;
        for(JSONObject event:Market.objects(inbox))if(!event.optBoolean("read"))unread++;
        addText(page,count+" active  ·  "+unread+" unread",14,MUTED,false,5);
        JSONObject universal=a.alertState.optJSONObject("universal");
        LinearLayout all=panel(page);addText(all,"Wishlist-wide notifier",18,WHITE,true,0);
        addText(all,universal==null?"Off · choose one rule for every game in your Wishlist":
                "On · "+AlertJob.label(universal.optString("mode"))+" · game-specific rules can override it",13,MUTED,false,5);
        pageAction(all,universal==null?"Set wishlist-wide alert":"Edit wishlist-wide alert",false,()->AlertEditor.openUniversal(a));
        if(!a.getSystemService(android.app.NotificationManager.class).areNotificationsEnabled())
            pageAction(page,"Android notification settings",false,()->a.startActivity(new android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,a.getPackageName())));
        if(count>0)pageAction(page,"Check selected stores now",false,a::checkAlerts);
        long checked=a.alertState.optLong("checked");
        if(checked>0)addText(page,"Last check  ·  "+DateFormat.getDateTimeInstance().format(new Date(checked*1000)),12,MUTED,false,8);
        if(!Market.text(a.alertState,"error").isEmpty())addText(page,Market.text(a.alertState,"error"),13,LILAC,false,6);
        addText(page,"Notification history",18,WHITE,true,20);
        if(unread>0)pageAction(page,"Mark all as read",false,a::readInbox);
        if(inbox==null || inbox.length()==0)addText(page,count==0?"Tap a bell in your Wishlist to set a game-specific alert.":"New price events will appear here after a selected store meets your rule.",14,MUTED,false,8);
        for(int i=inbox==null?0:inbox.length();i>0;i--){
            JSONObject e=inbox.optJSONObject(i-1);if(e==null || e.optJSONObject("game")==null)continue;
            String title=Market.text(e.optJSONObject("game"),"title"),price=money(e.optLong("amount"));
            String label=AlertJob.label(Market.text(e,"mode")),shop=Market.text(e,"shop");
            boolean unreadEvent=!e.optBoolean("read");
            LinearLayout row=w.row();w.pad(row,10,0,10,0);row.setMinimumHeight(w.dp(48));page.addView(row);
            TextView name=w.text((unreadEvent?"●  ":"")+title,14,WHITE,unreadEvent);
            name.setSingleLine();name.setEllipsize(android.text.TextUtils.TruncateAt.END);row.addView(name,w.flex());
            TextView value=w.text(price,14,LILAC,true);w.pad(value,8,0,0,0);row.addView(value);
            String details=label+" · "+price+" at "+shop+"\nPreviously "+money(e.optLong("previous"))
                +"\n"+DateFormat.getDateTimeInstance().format(new Date(e.optLong("when")*1000));
            if(e.has("previousStoreLow"))details+="\nPreviously reported store low · "+money(e.optLong("previousStoreLow"));
            final String message=details;
            row.setContentDescription(title+", "+label+", "+price+" at "+shop+(unreadEvent?", unread":""));
            w.action(row,w.shape(PANEL,6),()->{
                AlertDialog.Builder dialog=new AlertDialog.Builder(a).setTitle(title).setMessage(message)
                    .setPositiveButton("View game",(v,n)->a.openEvent(Market.text(e,"id"))).setNegativeButton("Close",null);
                Uri url=Uri.parse(Market.text(e,"url"));
                if("https".equals(url.getScheme()))dialog.setNeutralButton("Visit store",(v,n)->a.open(url));
                dialog.show();
            });
        }
    }
    private void settings(){
        LinearLayout page=page();
        ScrollView scroll=(ScrollView)page.getParent();settingsScroll=scroll;
        int offset=settingsOffset;if(offset>0)scroll.post(()->scroll.scrollTo(0,offset));

        LinearLayout profile=panel(page);LinearLayout account=w.row();profile.addView(account);
        String connected=Market.accountName(a.data),name=a.accountPrefs().getString("nickname","");
        String display=name.isEmpty()?connected:name;
        TextView avatar=w.text(display.substring(0,display.offsetByCodePoints(0,1)).toUpperCase(Locale.ROOT),24,INK,true);
        avatar.setGravity(Gravity.CENTER);avatar.setBackground(w.shape(ACCENT,16));
        account.addView(avatar,square(48));LinearLayout desc=w.column();w.pad(desc,12,0,0,0);account.addView(desc,w.flex());
        addText(desc,display,21,WHITE,true,0);addText(desc,name.isEmpty()?"Connected to IsThereAnyDeal":connected,13,MUTED,false,3);
        LinearLayout counts=w.row();w.pad(counts,0,12,0,0);profile.addView(counts);
        for(String key:new String[]{"wishlist","library"}){
            JSONArray games=a.data.optJSONArray(key);
            TextView count=w.text((games==null?"—":games.length())+"  "+title(key),13,LILAC,true);
            w.pad(count,6,6,6,6);w.action(count,w.shape(RAISED,8),()->go(key));count.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams countLayout=w.flex();
            if(counts.getChildCount()>0)countLayout.setMarginStart(w.dp(8));
            counts.addView(count,countLayout);
        }
        LinearLayout profileActions=w.column();profile.addView(profileActions);
        settingRow(profileActions,"Edit profile","",()->{
            EditText input=new EditText(a);input.setSingleLine(true);input.setHint("Display name on this phone");
            input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(40)});
            input.setText(name);input.setSelectAllOnFocus(true);
            LinearLayout form=w.column();w.pad(form,20,8,20,0);form.addView(input);
            new AlertDialog.Builder(a).setTitle("Edit profile").setView(form).setNegativeButton("Cancel",null)
                    .setPositiveButton("Save",(d,i)->{a.accountPrefs().edit().putString("nickname",input.getText().toString().trim()).apply();headerMode="";render(false);}).show();
        });
        LinearLayout region=settingsGroup(page,"Region & store filters");
        detailRow(region,"Pricing region",PricingRegion.current.name()+" · "+PricingRegion.current.currency,
                "Prices and alert history follow this region.",WHITE,this::chooseRegion);
        detailRow(region,"Universal DRM filter",DrmFilter.label(drm),"Choose activation platforms for deal prices and game offers.",WHITE,()->
            DrmFilter.choose(a,drm,values->{DrmFilter.save(a,values);drm=values;render(false);}));
        settingSwitch(region,"Hide owned games","Skip games from your Library in discovery feeds","hideOwned",false);
        LinearLayout notify=settingsGroup(page,"Notifications");
        settingRow(notify,"Wishlist-wide alerts","",()->AlertEditor.openUniversal(a));
        settingRow(notify,"Price alerts and notification history","",()->go("alerts"));
        LinearLayout appearance=settingsGroup(page,"Display & motion");
        android.content.SharedPreferences prefs=a.getSharedPreferences("appearance",0);
        AppTheme theme=AppTheme.saved(a);
        String logo=prefs.getString("logo",LOGOS[0]);
        settingRow(appearance,"Theme",theme.label,()->appearancePicker(true));
        settingRow(appearance,"App icon",logo,()->appearancePicker(false));
        settingSwitch(appearance,"AMOLED black","Use a black background with any accent","blackCanvas","AMOLED".equals(prefs.getString("theme","Midnight")));
        String[] routes={"home","deals","wishlist","library"},labels={"Discover","Deals","Wishlist","Library"};
        int start=Math.max(0,Arrays.asList(routes).indexOf(startupTab()));
        settingRow(appearance,"Startup tab",labels[start],()->chooseOption("Startup tab",labels[start],labels,choice->{
            prefs.edit().putString("startupTab",routes[Arrays.asList(labels).indexOf(choice)]).apply();render(false);
        }));
        settingSwitch(appearance,"Compact deal rows","Show smaller covers and tighter spacing","compactRows",false);
        settingSwitch(appearance,"Tap feedback","Feel a short vibration when selecting an action","haptics",false);
        settingSwitch(appearance,"Reduce motion","Use simpler transitions","reduceMotion",false);
        settingSwitch(appearance,"High refresh rate","Use the fastest supported display mode","highRefresh",true);
        detailRow(appearance,"Replay loading screen","PLAY","Preview your theme and app icon.",WHITE,this::showSplash);
        settingRow(appearance,"Default sorting",defaultSort(),()->chooseSort("Default sorting",defaultSort(),choice->{
            prefs.edit().putString("defaultSort",choice).apply();order=choice;render(false);
        }));
        LinearLayout sync=settingsGroup(page,"Storage & sync");
        settingRow(sync,"Check for updates","v"+BuildConfig.VERSION_NAME,a::checkForUpdates);
        settingSwitch(sync,"Cache game images","Keep artwork on this device","cacheImages",true);
        detailRow(sync,"Clear image cache","","Remove downloaded game artwork.",WHITE,a::clearArtwork);
        long saved=a.data.optLong("saved");
        String lastSync=saved==0?"Waiting for first sync":"Last sync · "+DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(new Date(saved*1000));
        refreshAction=detailRow(sync,"Refresh now","",lastSync,WHITE,a::manualRefresh);
        settingRow(region,"View ignored games","",()->go("ignored"));

        LinearLayout prices=settingsGroup(page,"Account & pricing key");
        detailRow(prices,"Reconnect IsThereAnyDeal","","Reconnect your linked account.",WHITE,a::permissionDialog);
        detailRow(prices,a.personal?"Replace personal key":"Add personal API key",a.personal?"Active":"",
                a.personal?"Personal pricing key saved on this device.":"Unlock catalog search and store prices.",WHITE,a::editKey);
        LinearLayout danger=w.column();
        GradientDrawable dangerFill=w.shape(0xff190e12,14);dangerFill.setStroke(w.dp(1),0xff542030);
        danger.setBackground(dangerFill);w.pad(danger,6,2,6,2);
        LinearLayout.LayoutParams gap=new LinearLayout.LayoutParams(-1,-2);gap.topMargin=w.dp(10);page.addView(danger,gap);
        int warning=0xffff7989;
        if(a.personal)detailRow(danger,"Remove personal key","","",warning,()->a.setKey(""));
        detailRow(danger,"Sign out","","",warning,()->new AlertDialog.Builder(a).setTitle("Sign out on this phone?")
                .setMessage("Your local alerts, notification history, cached lists and personal key will be cleared from this phone.")
                .setNegativeButton("Cancel",null).setPositiveButton("Sign out",(d,i)->a.signOut()).show());
    }
    private TextView settingRow(LinearLayout group,String label,String value,Runnable click){
        return detailRow(group,label,value,"",WHITE,click);
    }
    private void rowDivider(LinearLayout group){
        if(group.getChildCount()==0)return;
        View divider=new View(a);divider.setBackgroundColor(LINE);
        group.addView(divider,new LinearLayout.LayoutParams(-1,w.dp(1)));
    }
    private TextView detailRow(LinearLayout group,String label,String value,String detail,int color,Runnable click){
        rowDivider(group);
        LinearLayout row=w.row();row.setMinimumHeight(w.dp(48));w.pad(row,10,8,8,8);
        LinearLayout copy=w.column();row.addView(copy,w.flex());
        TextView caption=w.text(label,14,color,true);copy.addView(caption);
        if(!detail.isEmpty())addText(copy,detail,12,MUTED,false,3);
        if(!value.isEmpty()){
            TextView trailing=w.text(value,12,LILAC,true);w.pad(trailing,8,0,2,0);
            trailing.setMaxWidth(a.getResources().getDisplayMetrics().widthPixels/3);
            row.addView(trailing);
        }
        row.addView(new Icon(a,"next",color==WHITE?MUTED:color),square(14));group.addView(row);
        String description=value.isEmpty()?label:label+", "+value;
        row.setContentDescription(detail.isEmpty()?description:description+". "+detail);
        w.action(row,w.shape(Color.TRANSPARENT,8),click);
        return caption;
    }
    private LinearLayout settingsGroup(LinearLayout page,String label){
        TextView heading=w.text(label.toUpperCase(Locale.ROOT),11,MUTED,true);heading.setLetterSpacing(.1f);
        w.pad(heading,2,14,0,4);page.addView(heading);LinearLayout group=w.column();w.pad(group,6,2,6,2);group.setBackground(w.shape(PANEL,14));page.addView(group);return group;
    }
    private void settingSwitch(LinearLayout parent,String label,String detail,String key,boolean defaultValue){
        android.content.SharedPreferences prefs=a.getSharedPreferences("appearance",0);
        rowDivider(parent);LinearLayout row=w.row();w.pad(row,10,6,8,6);parent.addView(row);
        LinearLayout copy=w.column();row.addView(copy,w.flex());
        addText(copy,label,14,WHITE,true,0);addText(copy,detail,12,MUTED,false,3);
        Switch toggle=new Switch(a);toggle.setContentDescription(label);toggle.setMinHeight(w.dp(48));
        toggle.setThumbTintList(android.content.res.ColorStateList.valueOf(WHITE));
        toggle.setTrackTintList(new android.content.res.ColorStateList(new int[][]{
                new int[]{android.R.attr.state_checked},new int[]{}},new int[]{ACCENT,LINE}));
        toggle.setChecked(prefs.getBoolean(key,defaultValue));row.addView(toggle);
        toggle.setOnCheckedChangeListener((view,checked)->{
            prefs.edit().putBoolean(key,checked).apply();
            if(key.equals("blackCanvas"))a.recreate();
            else if(key.equals("highRefresh"))a.applyRefreshRate();else status();
        });
        row.setOnClickListener(view->toggle.toggle());
    }
    private void welcome(int step,boolean keyStep){
        ScrollView scroll=new ScrollView(a);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);
        stage.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout layout=w.column();scroll.addView(layout);
        layout.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{BG,PANEL,BG}));
        LinearLayout brand=w.row();w.pad(brand,24,20,24,6);layout.addView(brand);
        brand.addView(brandLogo(36),square(36));TextView word=wordmark(21);w.pad(word,10,0,0,0);brand.addView(word);
        View landscape=new WelcomeLandscape(a);
        layout.addView(landscape,new LinearLayout.LayoutParams(-1,w.dp(175),1));
        LinearLayout page=w.column();w.pad(page,24,16,24,24);layout.addView(page);
        LinearLayout card=w.column();w.pad(card,0,0,0,16);page.addView(card);
        if(keyStep){
            addText(card,"Compare prices across stores",25,WHITE,true,18);
            addText(card,"Your personal key unlocks store offers, price history and catalog search. You can add it later in Settings.",16,MUTED,false,8);
            setupKey=pageAction(page,"Add personal API key",true,a::editKey);
            pageAction(page,"Continue without a key",false,()->{
                a.accountPrefs().edit().putInt("introStep",2).apply();go(startupTab());
            });
            return;
        }
        if(step==0){
            addText(card,"Your next game,\nat the right price.",34,WHITE,true,0);
            addText(card,"Follow your PC wishlist across stores. See how today’s price compares and get an alert when it drops.",16,MUTED,false,10);
            pageAction(page,"Get started",true,()->{
                a.accountPrefs().edit().putInt("introStep",1).apply();render(true);
            });
            return;
        }
        addText(card,"Bring your wishlist.",32,WHITE,true,0);
        addText(card,"Sign in with IsThereAnyDeal to sync the games you follow. Your sign-in tokens are encrypted on this phone.",16,MUTED,false,10);
        pageAction(page,"Sign in with IsThereAnyDeal",true,a::signIn).setEnabled(!a.busy);
        pageAction(page,"OAuth app settings",false,a::clientDialog).setEnabled(!a.busy);
    }
}

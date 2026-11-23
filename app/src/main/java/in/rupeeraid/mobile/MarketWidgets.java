package in.rupeeraid.mobile;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import androidx.core.graphics.PathParser;
import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.Locale;
import org.json.JSONObject;

final class MarketWidgets {
    static int BG=0xff08090c,BAR=BG,PANEL=0xff12141a,RAISED=0xff191c24,LINE=0xff292c33;
    static final int WHITE=0xfff8fafc,MUTED=0xff94a3b8,GREEN=0xff34d399,DARK_GREEN=0xff063f32,INK=0xff04130d;
    static int ACCENT=0xff10b981,LILAC=ACCENT,SECONDARY=0xff06b6d4,ACCENT_SURFACE=0xff102923;
    static final android.view.animation.Interpolator EASE=new android.view.animation.PathInterpolator(.22f,1f,.36f,1f);

    static void applyPalette(Context context,boolean black){
        android.content.res.TypedArray c=context.obtainStyledAttributes(new int[]{android.R.attr.windowBackground,
                R.attr.raidPanel,R.attr.raidRaised,R.attr.raidLine,android.R.attr.colorAccent,R.attr.raidBright,R.attr.raidSecondary});
        try {
            BG=BAR=black?android.graphics.Color.BLACK:c.getColor(0,BG);
            PANEL=c.getColor(1,PANEL);RAISED=c.getColor(2,RAISED);LINE=c.getColor(3,LINE);
            ACCENT=c.getColor(4,ACCENT);LILAC=c.getColor(5,LILAC);SECONDARY=c.getColor(6,SECONDARY);
            ACCENT_SURFACE=androidx.core.graphics.ColorUtils.blendARGB(BG,ACCENT,.20f);
        } finally { c.recycle(); }
    }
    static final String[] LOGOS={"Bolt Rupee","Stealth Shield","Pixel Loot","Neon Mono","AMOLED Pitch"};
    ImageView logo(String style,int size){
        ImageView image=new ImageView(context);
        int index=java.util.Arrays.asList(LOGOS).indexOf(style);
        if(index<=0){
            image.setImageResource(R.drawable.raid_icon);
        }else{
            int[] marks={R.drawable.logo_stealth_shield,R.drawable.logo_pixel_loot,R.drawable.logo_neon_mono,R.drawable.logo_amoled_pitch};
            image.setImageResource(marks[index-1]);
            int[] backgrounds={R.color.launcher_stealth_shield_bg,R.color.launcher_pixel_loot_bg,R.color.launcher_neon_mono_bg,R.color.launcher_amoled_pitch_bg};
            GradientDrawable bg=shape(context.getColor(backgrounds[index-1]),Math.round(size*.3f));
            bg.setStroke(dp(1),index==4?LINE:ACCENT);image.setBackground(bg);
            int inset=dp(size*.18f);image.setPadding(inset,inset,inset,inset);
        }
        return image;
    }
    final Context context;
    private final Typeface display,body,mono;
    MarketWidgets(Context context) {
        this.context=context;
        display=context.getResources().getFont(R.font.space_grotesk_semibold);
        body=context.getResources().getFont(R.font.plus_jakarta_sans_regular);
        mono=context.getResources().getFont(R.font.jetbrains_mono_semibold);
    }
    static boolean motion(Context context){
        return android.animation.ValueAnimator.areAnimatorsEnabled() && !context.getSharedPreferences("appearance",0).getBoolean("reduceMotion",false);
    }
    int dp(float n) { return Math.round(n*context.getResources().getDisplayMetrics().density); }
    void pad(View view,int l,int t,int r,int b) { view.setPadding(dp(l),dp(t),dp(r),dp(b)); }
    LinearLayout column() { LinearLayout v=new LinearLayout(context);v.setOrientation(LinearLayout.VERTICAL);return v; }
    LinearLayout row() { LinearLayout v=new LinearLayout(context);v.setGravity(Gravity.CENTER_VERTICAL);return v; }
    LinearLayout.LayoutParams flex() { return new LinearLayout.LayoutParams(0,-2,1); }
    TextView text(String label,float size,int color,boolean bold) {
        TextView v=new TextView(context);
        v.setText(label);v.setTextSize(size);v.setTextColor(color);v.setIncludeFontPadding(false);
        v.setTypeface(bold?display:body);
        if(bold)v.setFontVariationSettings("'wght' 700");
        v.setFontFeatureSettings("tnum");
        if(bold && size>=18)v.setLetterSpacing(-.025f);
        return v;
    }
    void ellipsis(TextView text,int lines) {text.setMaxLines(lines);text.setEllipsize(TextUtils.TruncateAt.END);}
    GradientDrawable shape(int color,int curve) {
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(curve));
        if(color==PANEL || color==RAISED)d.setStroke(dp(1),LINE);return d;
    }
    void action(View view,Drawable background,Runnable click) {
        view.setBackground(new RippleDrawable(ColorStateList.valueOf((ACCENT&0x00ffffff)|0x33000000),background,new ColorDrawable(0xffffffff)));
        view.setOnClickListener(v->{
            if(context.getSharedPreferences("appearance",0).getBoolean("haptics",false))
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            click.run();
        });view.setFocusable(true);
        view.setOnTouchListener((v,event)->{
            if(!motion(context))return false;
            float target=event.getActionMasked()==android.view.MotionEvent.ACTION_DOWN?.965f:1f;
            if(event.getActionMasked()==android.view.MotionEvent.ACTION_DOWN || event.getActionMasked()==android.view.MotionEvent.ACTION_UP
                    || event.getActionMasked()==android.view.MotionEvent.ACTION_CANCEL)v.animate().scaleX(target).scaleY(target).setInterpolator(EASE).setDuration(target==1f?180:85).start();
            return false;
        });
    }
    TextView button(String label,boolean primary,Runnable click) {
        TextView v=text(label,15,primary?INK:WHITE,true);v.setGravity(Gravity.CENTER);pad(v,14,8,14,8);
        v.setMinHeight(dp(46));GradientDrawable fill=shape(primary?ACCENT:RAISED,12);
        if(primary){fill.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);fill.setColors(new int[]{ACCENT,SECONDARY});}
        action(v,fill,click);return v;
    }
    TextView badge(String label,boolean low) {
        TextView v=text(label,11,low?GREEN:LILAC,true);v.setTypeface(mono);v.setGravity(Gravity.CENTER);
        pad(v,6,2,6,2);v.setBackground(shape(low?DARK_GREEN:0xff49455e,4));return v;
    }
    static int lowColor(Market.LowPeriod tier){
        switch(tier){
            case ALL_TIME:return 0xfff43f5e;
            case YEAR:return 0xff34d399;
            case QUARTER:return 0xff06b6d4;
            default:return WHITE;
        }
    }
    void priceStyle(TextView price,Market.LowPeriod tier){
        int color=lowColor(tier);
        if(price.getTag()==tier && price.getCurrentTextColor()==color)return;
        price.setTag(tier);price.setTextColor(color);
        price.setShadowLayer(tier==Market.LowPeriod.NONE?0:dp(3),0,0,(color&0x00ffffff)|0x66000000);
    }
    void tierBadge(TextView view,Market.LowPeriod tier,boolean outline){
        if(view.getTag()==tier)return;
        view.setTag(tier);
        int accent=tier==Market.LowPeriod.NONE?GREEN:lowColor(tier);
        int top=outline?android.graphics.Color.rgb(android.graphics.Color.red(accent)/4,android.graphics.Color.green(accent)/4,android.graphics.Color.blue(accent)/4):accent;
        int bottom=outline?PANEL:android.graphics.Color.rgb(android.graphics.Color.red(accent)*4/5,android.graphics.Color.green(accent)*4/5,android.graphics.Color.blue(accent)*4/5);
        GradientDrawable bg=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{top,bottom});
        bg.setCornerRadius(dp(4));if(outline)bg.setStroke(dp(1),accent);
        view.setBackground(tier==Market.LowPeriod.NONE?shape(DARK_GREEN,6):bg);view.setTextColor(outline || tier==Market.LowPeriod.NONE?accent:tier==Market.LowPeriod.ALL_TIME?WHITE:INK);
    }
    FrameLayout iconButton(String name,String description,Runnable click) {
        FrameLayout box=new FrameLayout(context);
        box.addView(new Icon(context,name,WHITE),new FrameLayout.LayoutParams(dp(23),dp(23),Gravity.CENTER));
        box.setContentDescription(description);action(box,shape(0x00000000,9),click);return box;
    }
    ImageView artView() {
        ImageView image=new ImageView(context);image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return image;
    }
    private RequestBuilder<Drawable> artwork(ImageView view,String url) {
        boolean cache=context.getSharedPreferences("appearance",0).getBoolean("cacheImages",true);
        return Glide.with(view).load(url).diskCacheStrategy(cache
                ? com.bumptech.glide.load.engine.DiskCacheStrategy.AUTOMATIC
                : com.bumptech.glide.load.engine.DiskCacheStrategy.NONE);
    }
    void addBadge(LinearLayout row, TextView badge) {
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);
        if(row.getChildCount()>0)lp.setMarginStart(dp(4));
        row.addView(badge,lp);
    }
    void heroArt(ImageView view,String url,boolean portrait,Runnable settled) {
        if(url.equals(view.getTag()))return;
        view.setTag(url);
        // A landscape-only edition is letterboxed inside the portrait slot.
        view.setScaleType(portrait?ImageView.ScaleType.CENTER_CROP:ImageView.ScaleType.FIT_CENTER);
        if(url.isEmpty()){
            view.setImageResource(R.drawable.art_placeholder);
            settled.run();return;
        }
        RequestBuilder<Drawable> request=artwork(view,url).dontAnimate().error(R.drawable.art_placeholder);
        (portrait?request.centerCrop():request.fitCenter()).listener(new com.bumptech.glide.request.RequestListener<Drawable>() {
            public boolean onLoadFailed(com.bumptech.glide.load.engine.GlideException error,Object model,
                    com.bumptech.glide.request.target.Target<Drawable> target,boolean first){
                settled.run();return false;
            }
            public boolean onResourceReady(Drawable image,Object model,com.bumptech.glide.request.target.Target<Drawable> target,
                    com.bumptech.glide.load.DataSource source,boolean first){
                settled.run();return false;
            }
        }).into(view);
    }
    void rowArt(ImageView view,String url,String thumbnail){
        if(url.equals(view.getTag()))return;
        view.setTag(url);
        RequestBuilder<Drawable> request=artwork(view,url.isEmpty()?null:url)
                .centerCrop().dontAnimate().placeholder(R.drawable.art_placeholder).error(R.drawable.art_placeholder);
        if(!thumbnail.isEmpty() && !url.equals(thumbnail)){
            RequestBuilder<Drawable> small=artwork(view,thumbnail).centerCrop().dontAnimate();
            request.thumbnail(small).error(small.clone().error(R.drawable.art_placeholder));
        }
        request.into(view);
    }
    void art(ImageView view,JSONObject game,String... sizes) {
        String url=Market.art(game,sizes);
        if(url.equals(view.getTag()))return;
        view.setTag(url);
        RequestBuilder<Drawable> request=artwork(view,url.isEmpty()?null:url).centerCrop()
                .placeholder(R.drawable.art_placeholder).fallback(R.drawable.art_placeholder).error(R.drawable.art_placeholder)
                .transition(DrawableTransitionOptions.withCrossFade(140));
        if(!motion(context))request.dontAnimate();
        String smaller=Market.art(game,"banner145","banner300");
        boolean portrait=sizes.length>0 && sizes[0].equals("boxart") && url.equals(Market.art(game,"boxart"));
        if(!portrait && !url.isEmpty() && !smaller.isEmpty() && !smaller.equals(url)){
            RequestBuilder<Drawable> thumbnail=artwork(view,smaller).centerCrop();
            request.thumbnail(thumbnail).error(thumbnail.clone().error(R.drawable.art_placeholder));
        }
        request.into(view);
    }
    static String money(long paise) {
        if(paise==0)return "Free";
        PricingRegion region=PricingRegion.current;
        NumberFormat f=NumberFormat.getCurrencyInstance(new Locale("en",region.country));
        try { f.setCurrency(java.util.Currency.getInstance(region.currency)); }
        catch (IllegalArgumentException unknown) { return region.currency+" "+java.math.BigDecimal.valueOf(paise,2).toPlainString(); }
        f.setMaximumFractionDigits(paise%100==0?0:2);f.setMinimumFractionDigits(paise%100==0?0:2);
        return f.format(paise/100.0);
    }
    static String age(String value) {
        try{
            long sec=Math.max(0,Instant.now().getEpochSecond()-Instant.parse(value).getEpochSecond());
            if(sec<60)return "Just now";
            if(sec<3600)return sec/60+"m ago";
            if(sec<86400)return sec/3600+"h ago";
            return sec/86400+"d ago";
        }catch(RuntimeException ex){return "";}
    }

    View loadingRows(String label){
        View skeleton=new LoadingRows(context,motion(context));
        skeleton.setMinimumHeight(dp(75));skeleton.setContentDescription(label);
        skeleton.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return skeleton;
    }
    private static final class LoadingRows extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        final android.graphics.Matrix shift=new android.graphics.Matrix();
        android.graphics.LinearGradient light;
        final float density;
        final boolean animate;
        LoadingRows(Context context,boolean animate){
            super(context);density=getResources().getDisplayMetrics().density;this.animate=animate;
        }
        @Override protected void onMeasure(int width,int height){
            setMeasuredDimension(resolveSize(getSuggestedMinimumWidth(),width),resolveSize(getSuggestedMinimumHeight(),height));
        }
        @Override protected void onSizeChanged(int width,int height,int oldWidth,int oldHeight){
            if(width>0)light=new android.graphics.LinearGradient(0,0,width*.75f,0,
                new int[]{PANEL,RAISED,LINE,RAISED,PANEL},null,android.graphics.Shader.TileMode.CLAMP);
        }
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);if(light==null)return;
            float width=getWidth(),d=density;
            float phase=animate?(android.os.SystemClock.uptimeMillis()%1550)/1550f:.55f;
            shift.setTranslate((phase*1.9f-.8f)*width,0);light.setLocalMatrix(shift);paint.setShader(light);
            c.drawRoundRect(14*d,12*d,70*d,61*d,8*d,8*d,paint);
            c.drawRoundRect(82*d,19*d,Math.max(83*d,width*.78f),30*d,4*d,4*d,paint);
            c.drawRoundRect(82*d,40*d,Math.max(83*d,width*.57f),48*d,4*d,4*d,paint);
            if(animate && isShown() && getWindowVisibility()==VISIBLE)postInvalidateOnAnimation();
        }
    }

    static final class Icon extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path;
        Icon(Context c,String name,int color) {
            super(c);
            String d;
            switch(name) {
                case "heart-on":
                case "heart":d="M12 20C10 18 3 14 3 8a4.5 4.5 0 0 1 9-1 4.5 4.5 0 0 1 9 1c0 6-7 10-9 12z";break;
                case "bell-on":d="M4 17l2-3V9a6 6 0 0 1 12 0v5l2 3z M9.8 19h4.4a2.3 2.3 0 0 1-4.4 0z";break;
                case "bell":d="M4 17l2-3V9a6 6 0 0 1 12 0v5l2 3z M10 20q2 3 4 0";break;
                case "own":d="M5 3h14q2 0 2 2v14q0 2-2 2H5q-2 0-2-2V5q0-2 2-2z";break;
                case "own-on":d="M5 3h14q2 0 2 2v14q0 2-2 2H5q-2 0-2-2V5q0-2 2-2z M6 12l1.5-1.5 3 3 6-6L18 9l-7.5 7.5z";break;
                case "ignore-on":d="M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0z M6.5 8l1.5-1.5 9.5 9.5-1.5 1.5z";break;
                case "ignore":d="M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0 M6 6l12 12";break;
                case "home": d="M3 10.5L12 3l9 7.5V21h-6v-7H9v7H3z";break;
                case "deals": d="M3 4h9l9 9-8 8-10-10z M7.7 8h.2";break;
                case "wishlist": d="M6 3.5h12v17l-6-4-6 4z";break;
                case "library": d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20 M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z";break;
                case "grid": d="M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z";break;
                case "list": d="M3 5h2 M9 5h12 M3 12h2 M9 12h12 M3 19h2 M9 19h12";break;
                case "menu": d="M3 6h18 M3 12h13 M3 18h18";break;
                case "account": d="M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0 M15 10a3 3 0 1 1-6 0a3 3 0 1 1 6 0 M5.4 18c2.7-4.2 10.5-4.2 13.2 0";break;
                case "search": d="M16.5 10a6.5 6.5 0 1 1-13 0a6.5 6.5 0 1 1 13 0 M15 15l6 6";break;
                case "close": d="M5 5l14 14 M19 5L5 19";break;
                case "back": d="M20 12H4 M10 5l-7 7 7 7";break;
                case "next": d="M9 5l7 7-7 7";break;
                case "sort": d="M3 6h18 M3 12h13 M3 18h8";break;
                case "PC": d="M2.5 4h19v13h-19z M8 21h8 M12 17v4";break;
                case "Xbox": d="M3 14l2-6q1-2 4-2l3 1 3-1q3 0 4 2l2 6q2 7-3 4l-3-3H9l-3 3q-5 3-3-4z M7 10v4 M5 12h4 M16 10h.1 M18 12h.1";break;
                case "PlayStation": d="M7 4l4-2v17l-4 2z M14 5l3-1 2 15-4 1z";break;
                case "Switch": d="M6 4h12q3 0 3 3v10q0 3-3 3H6q-3 0-3-3V7q0-3 3-3z M8 4v16 M16 4v16 M5.6 9h.1 M18.3 15h.1";break;
                case "Other": d="M4 5h6v6H4z M14 5h6v6h-6z M4 15h6v5H4z M14 15h6v5h-6z";break;
                case "refresh": d="M20 9a8 8 0 1 0 .3 6 M20 3v6h-6";break;
                default: d="M4 12h16";
            }
            path=PathParser.createPathFromPathData(d);
            boolean filled=name.endsWith("-on");
            if(filled)path.setFillType(Path.FillType.EVEN_ODD);
            paint.setStyle(filled?Paint.Style.FILL:Paint.Style.STROKE);paint.setStrokeWidth(1.8f);paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);paint.setColor(color);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override protected void onDraw(Canvas canvas) {
            float scale=Math.min(getWidth(),getHeight())/24f;
            canvas.save();canvas.translate((getWidth()-24*scale)/2,(getHeight()-24*scale)/2);
            canvas.scale(scale,scale);canvas.drawPath(path,paint);canvas.restore();
        }
    }
}

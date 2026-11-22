package in.rupeeraid.mobile;

import android.content.Context;
import android.content.res.TypedArray;
import android.view.ContextThemeWrapper;

enum AppTheme {
    EMERALD("Midnight", "Obsidian Emerald", R.style.RaidTheme),
    AMETHYST("Amethyst", "Cyber Amethyst", R.style.RaidAmethyst),
    EMBER("Ember", "Crimson Ember", R.style.RaidEmber),
    COBALT("Cobalt", "Cobalt Frost", R.style.RaidCobalt),
    GOLD("Gold", "Solar Gold", R.style.RaidGold),
    SLATE("Slate", "Slate", R.style.RaidSlate);

    final String key, label;
    final int style;
    AppTheme(String key, String label, int style) {
        this.key = key;
        this.label = label;
        this.style = style;
    }

    static AppTheme saved(Context context) {
        String key = context.getSharedPreferences("appearance",0).getString("theme","Midnight");
        for (AppTheme theme : values()) if (theme.key.equals(key)) return theme;
        return EMERALD;
    }

    int[] swatch(Context context) {
        TypedArray colors = new ContextThemeWrapper(context, style).obtainStyledAttributes(
                new int[]{android.R.attr.windowBackground, android.R.attr.colorAccent, R.attr.raidSecondary});
        try { return new int[]{colors.getColor(0,0), colors.getColor(1,0), colors.getColor(2,0)}; }
        finally { colors.recycle(); }
    }
}

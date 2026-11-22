package in.rupeeraid.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Locale;

final class PricingRegion {
    static volatile PricingRegion current = new PricingRegion("IN", "INR");
    final String country, currency;

    PricingRegion(String country, String currency) {
        this.country = country;
        this.currency = currency;
    }

    static PricingRegion load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("pricingRegion", 0);
        return current = new PricingRegion(prefs.getString("country", "IN"), prefs.getString("currency", "INR"));
    }

    void save(Context context) {
        context.getSharedPreferences("pricingRegion", 0).edit()
                .putString("country", country).putString("currency", currency).apply();
        current = this;
    }

    String name() { return new Locale("", country).getDisplayCountry(); }
    String prefix() { return country.equals("IN") && currency.equals("INR") ? "" : "region-" + country + "-" + currency + "-"; }
}

# RupeeRaid for Android

RupeeRaid runs directly on Android 8.0 and newer. The phone connects to IsThereAnyDeal (ITAD), saves an encrypted cache and schedules its own price checks.

**[Download the installable 2.0 development APK](https://github.com/SrinidhiSC/RupeeRaid-Android/releases/download/v2.0/RupeeRaid-2.0-dev.apk)**. This build uses the same development signing certificate as 1.8. Keep your production signing keystore outside the repository.

## Develop on another device

Open the cloned repository root in Android Studio and install SDK Platform 37. Set `ISTHEREANYDEAL_CLIENT_ID` to your public native OAuth client ID before starting Gradle, or enter it from the sign-in screen. Run `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug` on Windows or `./gradlew :app:testDebugUnitTest :app:assembleDebug` on macOS and Linux. The debug APK is written to `app/build/outputs/apk/debug/`. Gradle downloads dependencies on the first build. See the [signing and privacy notes](SHARING.md) for release builds.

Homepage prices use red **HL** for an all-time low, green **1Y** for a one-year low and cyan **3M** for a three-month low. White indicates a verified price without a qualifying low. When several records match, the longest period wins. An additional **S** marks the all-time low at that store. A missing price shows a dash. The feed and batch endpoints supply the comparison amounts directly in INR.

## Install and connect

1. Open the development APK linked above on your phone and choose **Install** or **Update**.
2. In the ITAD public-client registration, choose **This app will NOT store OAuth secret securely**. Register the exact redirect URI `in.rupeeraid.mobile://signin`.
3. Use **Sign in with IsThereAnyDeal** and approve in your browser. The app loads your Waitlist, Collection, Ignore List and India discovery feeds. Sign-in uses authorization code with S256 PKCE. Only the public client ID is distributed in the APK.
4. To unlock all available store offers, searchable catalog, store lows, charts and game metadata, open **Account → Add personal API key**. Enter your own ITAD pricing key on the device. ITAD grants access to these endpoints through its key authentication scheme. The signed-in Waitlist and selected-store alert checks can use OAuth alone.

Editing the Collection, Ignore List or game notes can prompt for additional consent. ITAD remains the source of those lists and notes across devices. An account switch clears local private data only after confirmation. [ITAD API and authorization scopes](https://github.com/IsThereAnyDeal/API/blob/master/dist/openapi.json)

## Browse and open a game

Discover has a ranked trending carousel, recent deals, best discounts, historical lows and Wishlist previews. Open the menu for free offers, the Ignore List and refresh. Artwork comes from the ITAD asset URLs. The catalog hides explicitly console-only editions and offers. ITAD sometimes omits platform metadata. For full catalog searches, unknown platforms are checked against ITAD’s Steam, GOG and Epic catalog mappings. Confirmed editions stay searchable after delisting. Priced editions appear first by default. Cached account lists preserve titles with unknown platforms. Wishlist rows show the original and current price and open the device alert settings directly. Search suggests saved games offline as you type. A reusable local index ranks exact titles, prefixes, initials, token prefixes and substring matches, ignoring accents and punctuation. With a personal key, two or more characters start an automatic catalog lookup after a 700 ms pause. Only one lookup runs at a time, with a pause of at least 1.8 seconds between completed and subsequent lookups. Obsolete responses are discarded. The encrypted query cache holds up to 16 recent terms for one hour and the price cache reuses exact edition quotes. Each request counts toward its credential budget, including store-ID lookups. Provider retry windows survive an app restart.

The game page includes Wishlist, Notify, Own and Ignore. The store comparison retains original purchase URLs and coupons reported by ITAD for eligible India offers. An offer at zero is labeled **Free**. The History tab lets you select a shop, choose a period and move through the dates and prices. Missing records and withdrawn offers remain visible as gaps.

| Game section | Provider data and behavior |
| --- | --- |
| Offers | OAuth store-filtered deals for Waitlist games or key-authorized batch prices across ITAD shops. Shows original price, discount, source update, expiration, activation and coupon when supplied. |
| Historical lows and price history | Offers presents the current best, all-time, one-year and three-month lows with eligible store observations and dates where available. Each store lists its current and all-time price. The separate Price history tab shows the historical change log for India. The app checks a low flag against the game-wide record and cheaper verified current offers. Three months, one year and all available history are supported. Each chart covers one store. |
| DLC and packages in Offers, and Series | ITAD title search with product-type and title-word checks. Matches are suggestions. ITAD publishes neither a parent-DLC relationship nor a franchise membership field in v2.11. When the provider returns a Steam App ID, Offers can also open the publisher-listed DLC page on Steam. |
| Bundles in Offers | ITAD’s exact game-to-bundle records, including past offers. Tiers open their actual included games. A tier gets a rupee amount only when its provider shop and currency qualify. |
| Subscriptions and Giveaways | Country-specific subscription memberships and leaving dates. Giveaways come from a multi-region endpoint and link to the provider’s terms. |
| About and Community | Release, studios, tags, features, reviews, player counts, community rankings and Waitlist statistics when present. Steam App IDs link to store descriptions and media. The ITAD notes control reads and updates the signed-in user’s game note. |

The GG.deals mobile references informed the navigation, artwork density, game actions and detail tabs. ITAD supplies the underlying data. [Reference game](https://gg.deals/game/red-dead-redemption/) · [ITAD API contract](https://github.com/IsThereAnyDeal/API/blob/master/dist/openapi.json)

## Price alerts

Set a wishlist-wide rule from the alerts screen to cover current and newly added wishlist games. A game can use that rule, use its own rule or mute alerts. To configure one game, tap its wishlist bell or open the game and tap **Notify**, select a rule and tick the stores to follow. For example, select **Any price drop**, **Steam** and **Epic Games Store**, then **Save alert**. Every selected store has its own baseline. A cheaper price at another store cannot trigger this rule.

| Rule | When a new event is recorded |
| --- | --- |
| Historical low across stores | An eligible selected-store offer reaches an ITAD historical or new historical low after its baseline. |
| One-year or three-month low | A selected store reaches the chosen game-wide low or a longer-period low after the baseline. |
| A selected store’s own historical low | Its price reaches that shop’s reported low or receives a qualifying low flag. |
| Any price drop | A store’s current verified price falls below its previous verified price for the same game. |
| At or below a target price | A store’s price crosses the user’s threshold from above. The target accepts rupees and paise. |

The first observation establishes the baseline. The same offer update produces one event per qualifying store and rule. Eligible alert stores in this build are Steam, Epic Games Store, Green Man Gaming and Microsoft Store. They have confirmed INR checkout support and known ITAD shop IDs. The phone requests `country=IN` and checks the current and original currency independently. ITAD may also return INR display values for shops whose checkout uses another currency. Game details shows those regional display prices, store lows and price history for all stores returned by ITAD. The checkout currency can differ. [Steam pricing](https://partner.steamgames.com/doc/store/pricing) · [Epic regional prices](https://dev.epicgames.com/docs/epic-games-store/store-presence/custom-price-tiers) · [Green Man Gaming terms](https://www.greenmangaming.com/terms-and-conditions) · [Microsoft India store](https://www.microsoft.com/en-in/store/most-popular/games/xbox)

Android schedules a network-connected check approximately every 30 minutes. Battery management, network availability and force-stop can delay checks. A sale that starts and ends between observations can be missed. Without a personal key, monitoring uses the user's ITAD Waitlist. The alert dialog asks before adding a game. Removing the game from the Waitlist elsewhere pauses OAuth-only monitoring for that game.

The bell opens the device inbox. Received events, rules and read status survive a restart and can be viewed offline. New sign-ins and new price observations require internet. Android 13 and newer ask permission to show system notifications. The in-app inbox continues to work if permission is declined. Signing out clears this phone’s rules, cache, personal key and inbox. [Android periodic jobs](https://developer.android.com/reference/android/app/job/JobInfo.Builder#setPeriodic(long,long))

## Caching and build

Browse data, game metadata, history, offers, related titles, notes and search results are cached for one hour. Tap Refresh prices to sync the account, feeds and library and reload the open game. Other game tabs refresh on their next visit. Previously saved game details and offers remain available during outages. Artwork uses a separate image cache controlled in Settings. Background alerts keep their shorter price-check window. API errors keep their retry windows across restarts. The app reserves each outgoing call against a rolling budget of 100 per five minutes for API-key traffic and a separate budget for OAuth traffic. ITAD may return a longer retry window.

OAuth tokens, personal keys, game notes, snapshots and alert state are held in encrypted app-private storage backed by Android Keystore. The background worker and foreground account operations share a serialized executor so they can safely rotate tokens and commit observations.

Build with Android SDK Platform 37 and the Android Studio JDK. Supply `ISTHEREANYDEAL_CLIENT_ID` in the project-root `.env` or build environment. The sign-in screen also accepts a public client ID. Run in the `android` directory.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

Run `:app:connectedDebugAndroidTest` on an isolated emulator with an empty app account. The instrumentation suite signs in locally with test fixtures, opens game pages, sets a Steam/Epic rule, closes the activity, exercises the Android background job and checks delivery in the system tray and saved inbox. Live read-only checks covered Far Cry 3, Red Dead Redemption, the GTA catalog and Cyberpunk 2077. On 21 November 2026 the compiled Android pricing logic returned ₹899 at Steam for all three Cyberpunk periods. The original GTA V had no current offer, while Enhanced returned ₹1,249.50. Private account writes and browser consent are covered by contract tests and require an authorized ITAD account for a live end-to-end check. This artifact uses a debug signing key. Consult ITAD’s terms for public distribution. [ITAD API terms](https://github.com/IsThereAnyDeal/API/blob/master/dist/openapi.json)


## Appearance and motion

Account includes a device-local display name, Wishlist and Library shortcuts, the Midnight, Amethyst and Ember palettes, reduced motion and a high-refresh preference. Clearing the nickname restores the ITAD account name. Signing out clears the nickname. The appearance settings belong to the phone. Outlined and filled bells indicate alert state and have 48 dp touch targets and spoken labels. Store purchase links retain their provider-supplied destinations. Browser authorization opens ITAD during sign-in and reconnection.

Barlow Semi Condensed ships inside the app for headings, the wordmark and prices. Android’s system sans-serif handles body copy and font scaling. The font license is bundled in assets/licenses. Scrolling reuses native list rows. The app requests the fastest supported display mode at the current resolution when enabled. Android controls the final refresh rate. The app’s scrolling benchmark captures frame durations, and a 144 Hz physical device is required to validate the 6.94 ms frame budget for that mode. [Barlow Semi Condensed](https://github.com/google/fonts/tree/main/ofl/barlowsemicondensed) · [Android touch targets](https://developer.android.com/guide/topics/ui/accessibility/apps) · [Android frame pacing](https://developer.android.com/topic/performance/issues/render) · [Adaptive refresh rate](https://developer.android.com/develop/ui/views/animations/adaptive-refresh-rate)

## Release packaging

Run the following tasks from the Android project. Release builds use R8 and resource shrinking.

```powershell
.\gradlew.bat :app:assembleRelease :app:bundleRelease
```

The phone APK uses the development certificate associated with the earlier RupeeRaid APKs. The release app bundle is for signing with the publisher’s upload key. The publisher must also confirm eligibility under ITAD’s API terms, including its restriction on competing products, before public distribution. [ITAD API terms](https://github.com/IsThereAnyDeal/API/blob/master/dist/openapi.json)

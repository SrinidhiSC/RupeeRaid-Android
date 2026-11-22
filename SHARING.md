# RupeeRaid for Android

RupeeRaid is an independent Android client for [IsThereAnyDeal](https://isthereanydeal.com/). It compares regional PC game prices and checks price alerts for the user's wishlist.

## Sign-in and storage

Sign-in opens ITAD in the user's browser and uses authorization code with S256 PKCE. The Android build accepts a public OAuth client ID. Each user can optionally supply a personal ITAD API key for additional catalog and pricing endpoints. Register a public native OAuth client with the redirect URI `in.rupeeraid.mobile://signin`. Keep all confidential client secrets on the server that owns them.

The Android app talks directly to ITAD. It encrypts OAuth tokens, the optional personal key, cached lists and prices, notes, and alert rules and history in app-private files using AES-GCM and Android Keystore. Alerts can run while the app is in the background. General preferences and cached artwork also live on the phone. Account & Settings provides separate controls to remove the personal key, clear artwork, and sign out. Signing out clears the local session and account records. Users can manage authorization with ITAD.

## Build and sign

Open the exported project in Android Studio with Android SDK Platform 37 installed. Set the environment variable `ISTHEREANYDEAL_CLIENT_ID` to your registered public client ID before starting Gradle, or enter the ID in the app's OAuth settings. The build also accepts the public ID from a parent-directory `.env` for existing development checkouts.

On Windows, run `.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease`. On macOS or Linux, run `sh gradlew :app:testDebugUnitTest :app:assembleRelease`. To produce an installable release from Android Studio, choose **Build → Generate Signed Bundle / APK → APK**, select **release**, and use a dedicated signing keystore. Keep the keystore outside the project and keep an encrypted backup and its password under your control. Future direct-distribution updates need the same signing identity.

The 1.8 test APK used the Android debug certificate. Devices with that build will need to uninstall it before installing a differently signed release of the same package. Uninstalling clears device-local alerts, preferences and credentials. ITAD account lists can be synced again.

## Distribution

Review the [ITAD API terms](https://docs.isthereanydeal.com/) with the intended distribution in mind. ITAD requires approval for private use beyond personal use and restricts competing products. For a friends beta or public deal tracker, describe the app to ITAD and confirm eligibility, including the intended use of personal keys. Bundled font notices are in `app/src/main/assets/licenses`.

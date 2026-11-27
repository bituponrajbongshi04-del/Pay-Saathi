# PaySaathi Native Android

Kotlin + Jetpack Compose Android client. This is a real native Android project, not a Chrome/PWA wrapper.

## Build
From repository root: `cd android && gradle assembleDebug`. Output: `android/app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions also builds an APK artifact for pushes and pull requests.

## In-app updates
- The app checks the repository's latest GitHub Release when it opens, shows an in-app update card, downloads the APK, and launches Android Package Installer through a secure FileProvider URI.
- Android still requires the user to confirm installation; ordinary apps cannot silently install themselves. The user does not need to find or download the APK manually.
- Only APKs attached to official GitHub Releases are offered. Release APKs must use the same persistent signing key as the installed app or Android will reject the update.
- To publish signed updates, add repository Actions secrets `PAYSAATHI_KEYSTORE_BASE64`, `PAYSAATHI_KEYSTORE_PASSWORD`, `PAYSAATHI_KEY_ALIAS`, and `PAYSAATHI_KEY_PASSWORD`. Keep the keystore private and back it up. Bump `versionCode` and `versionName` for every release.
- Existing 1.0.1 debug installations may need one uninstall/reinstall when moving to a separately signed release key; after that, preserve that key for all future updates.

## Google Play preparation
Build an Android App Bundle with `gradle bundleRelease`. Before publishing, configure a private upload keystore and signing values as GitHub Actions secrets; never commit signing files or passwords. Complete Play Console app-content, privacy, data-safety, target-SDK and internal-testing requirements.

## Current implementation boundaries
- Supabase publishable key only; no service-role key or payment-provider secret is bundled.
- Password check + six-digit email OTP; session tokens use AndroidX encrypted preferences.
- Private KYC document bucket, owner-scoped uploads, admin review and server-side service-access RPC.
- KYC verification is manual until an authorised verification provider is contracted and configured.
- Recharge, DMT, AEPS, payout and wallet funding remain preview-only until provider APIs, signed callbacks and end-to-end tests are in place.

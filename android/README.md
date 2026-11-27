# PaySaathi Native Android

Kotlin + Jetpack Compose Android client. This is a real native Android project, not a Chrome/PWA wrapper.

## Build
From repository root: `cd android && gradle assembleDebug`. Output: `android/app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions also builds an APK artifact for pushes and pull requests.

## Google Play preparation
Build an Android App Bundle with `gradle bundleRelease`. Before publishing, configure a private upload keystore and signing values as GitHub Actions secrets; never commit signing files or passwords. Complete Play Console app-content, privacy, data-safety, target-SDK and internal-testing requirements.

## Current implementation boundaries
- Supabase publishable key only; no service-role key or payment-provider secret is bundled.
- Password check + six-digit email OTP; session tokens use AndroidX encrypted preferences.
- Private KYC document bucket, owner-scoped uploads, admin review and server-side service-access RPC.
- KYC verification is manual until an authorised verification provider is contracted and configured.
- Recharge, DMT, AEPS, payout and wallet funding remain preview-only until provider APIs, signed callbacks and end-to-end tests are in place.

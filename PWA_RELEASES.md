# PaySaathi installable app and release updates

PaySaathi is delivered as a Progressive Web App (PWA) on the existing GitHub Pages site. No separate APK or Play Store release is required for the initial installable app.

## Install
- Android: open the website in Chrome, tap **Install app** when prompted, or use Chrome menu → Install app / Add to Home screen.
- iPhone/iPad: open the website in Safari → Share → Add to Home Screen.
- Desktop: continue using the website normally; supported desktop browsers may optionally offer Install.

The app and website use the same hosted PaySaathi pages and Supabase account. A PWA install does not create a separate native Android APK.

## Publish an app update
For every release:
1. Edit `release.json`: increment `version`, update `date`, `title`, and list the user-visible changes in `notes`.
2. Edit `service-worker.js`: set `APP_VERSION` to exactly the same version. This cache version change triggers the browser's service-worker update lifecycle.
3. Commit both files to `main`.
4. Wait for GitHub Pages deployment to finish successfully.
5. When an installed app next opens online and detects the new service worker, PaySaathi displays the release title and notes. The user can select **Update now** or **Later**. Selecting Update now activates the waiting worker and reloads the app.

The service worker keeps an update in the waiting state until the user selects **Update now** in the in-app prompt. Only that action sends the skip-waiting message and reloads the app. An update cannot be downloaded while the app is closed or offline; the browser checks when the app is opened online.

## Files
- `manifest.webmanifest`: app name, icon, display mode, start URL and scope.
- `assets/paysaathi-app.svg`: app icon.
- `pwa.js`: install prompt, update prompt and release-note UI.
- `service-worker.js`: update lifecycle and offline navigation fallback.
- `release.json`: current user-facing version and release notes.

## Scope and safety
The PWA wraps the existing website; it does not turn the current HTML project into a separately compiled native APK. Existing web authentication and payment integrations remain the same. Real money services must remain disabled until the relevant payment providers and secure backend flows are live and tested.

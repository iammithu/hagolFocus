# hagolFocus

A focused Instagram for your real account.
- Messages: your real inbox (read + send, attach photos). Reels people send you open in a clean single-reel player.
- Stories: your friends' stories only, native tray + viewer (does not mark stories as seen).
- Profile: your own profile.
No Explore, no home feed, no endless Reels.

## Build the APK (GitHub Actions, no Android Studio)
1. New PRIVATE GitHub repo -> upload everything in this folder except `.github`.
2. Repo -> Add file -> Create new file -> name: `.github/workflows/build.yml` -> paste the contents of that file -> Commit.
3. Actions tab -> "Build APK" -> when green, download artifact `hagolFocus-apk` -> unzip -> install `app-release.apk`.
4. Open the app, log in once on Instagram's own login page (Messages tab).

Tips: long-press a tab to refresh it. Tap the active tab to go back / scroll to top.
Each build is signed with a fresh debug key, so uninstall the previous build before installing a new one.

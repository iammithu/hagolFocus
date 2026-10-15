# hagolFocus

A focused Instagram for your real account: Messages (read + send), Stories, Profile. No Explore, no home feed, no endless Reels.
Reels people send you open from the chat (or from a link opened in this app) as a single reel.

## Build the APK (GitHub Actions, no Android Studio)
1. New PRIVATE GitHub repo -> upload everything in this folder except `.github`.
2. Repo -> Add file -> Create new file -> name: `.github/workflows/build.yml` -> paste the contents of that file -> Commit.
3. Actions tab -> "Build APK" -> when green, download artifact `hagolFocus-apk` -> unzip -> install `app-release.apk`.
4. Open the app and log in once on Instagram's own login page.

Note: each build is signed with a fresh debug key, so uninstall the previous build before installing a new one.

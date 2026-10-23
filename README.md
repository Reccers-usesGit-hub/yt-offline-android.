# Offline Downloads (for RVX)

A YouTube-style app that saves videos and playlists for offline viewing.
RVX's own Download button sends videos here.

## Get the app
Open the **Releases** page of this repository on your phone and download the newest
`OfflineDownloads-….apk`, then open it to install (allow "Install unknown apps" for your browser
when asked). New versions install over the old one and keep your downloads.

## Connect RVX
1. In RVX, open **Settings** and search for **External downloader**.
2. Turn it on and set the package name to `app.ytod.offline`
   (the app's own Settings screen has a copy button for it).
3. Optional: turn on RVX's option to **hook the download button**, so the normal Download
   button under every video opens this app.

To download a **whole playlist**: open the playlist in RVX, tap **Share**, and choose
**Download offline**.

## Where videos are kept
In the app's private storage. They don't appear in your gallery or Downloads folder, and
uninstalling the app deletes them.

## How it's built
GitHub Actions builds a signed APK on every push to `main`, using the newest
NewPipeExtractor release (the library that reads YouTube). If downloads stop working after a
YouTube change, re-running the "Build APK" workflow usually picks up the fix.





this entire project is made with ai hahahahhhahhahahahha

## iPhone / iPad

The iPhone/iPad version is in the `ios/` folder. GitHub builds it on every change.
Get the newest **`OfflineDownloads-iOS-….ipa`** from Releases (the ones named "for iPhone/iPad").

1. Sideload the `.ipa` with AltStore, SideStore or Sideloadly. With a free Apple ID you need to refresh it every 7 days.
2. In the YouTube app, open a video or playlist, tap **Share**, then **Offline Downloads**.
   If you don't see it, scroll to the end of the app row, tap **More**, and turn it on.
3. Pick a quality and tap **Download**. The app opens and starts the download. Keep it open while big downloads run.

You can also copy a link and tap **Paste** in the app.

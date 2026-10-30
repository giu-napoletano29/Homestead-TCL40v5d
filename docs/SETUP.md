# Setup, your own photo, removing Salotto

The short version is in the [README](../README.md). This page has the whole of it.

## Setup

Fire OS doesn't list Salotto under Settings, Accessibility, so the Home button has to be
switched on over ADB, the TV's debugging connection. You can do that from the TV itself or
from a computer. Without it Salotto still works as an app you open from Amazon's app row. Only
the Home button needs it.

The accessibility service is how the app learns that Home was pressed. It sees only which app
came to the front. It can't read what is on screen and gets no key presses.

### From the TV, no computer

1. Turn on **ADB debugging** under Settings, My Fire TV, Developer options. If Developer
   options isn't there, open My Fire TV, About, and press Select on the device name seven
   times.
2. Open **Salotto** from Amazon's app row. The first-run setup walks you through the rest.
   The same steps are later under the **Settings** tile, **Setup**.
3. Select **Set it up** (or **On-device tunnel (advanced)** in Setup) and accept Android's VPN
   prompt. In both prompts, press Down to reach OK.
4. The TV asks whether to allow the app's ADB key. Check that the fingerprint matches the one
   Salotto shows, tick **Always allow from this computer**, then select OK. Without the tick
   the TV asks every time the app connects.
5. The first-run setup then switches the Home button on by itself. From Setup, **Finish setup
   automatically** does the same and also grants what TV Health, Debloat and the Home button
   need.
6. Press Home.

The tunnel runs only while the app is making changes and shuts itself off afterwards.
[SECURITY.md](../SECURITY.md) says what the app can access and what goes over the network.

Some Amazon packages switch themselves back on after a restart. With **Re-apply after reboot**
on (the default), Salotto switches them off again about a minute after the TV starts, which
needs ADB debugging left on. Firmware updates can switch packages back on too. With **Warn if
the system undoes Debloat** on (off by default), the home screen shows a tile for ten seconds
that offers to switch them off again.

If the Home button ever goes back to Amazon's home screen, for example after a Fire OS update,
select **Finish setup automatically** again. The *Finish setup* page shows up by itself when
that happens.

### From a computer

```sh
adb connect <your TV's address>          # shown on the Commands page in the app
adb shell settings put secure enabled_accessibility_services com.homestead.launcher/com.homestead.launcher.HomeRedirectService
adb shell settings put secure accessibility_enabled 1
adb shell pm grant com.homestead.launcher android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant com.homestead.launcher android.permission.READ_LOGS
```

The first two commands switch the Home button on, and the first replaces any other
accessibility service you have on. The first grant lets TV Health change settings from the
remote. The second lets Salotto catch the Home press early, so Amazon's home screen doesn't
flash first, and makes Home work while Amazon's home screen is showing. To switch packages
off, open **Commands for a computer** in the app. It lists exactly the commands your TV
still needs.

## Your own background photo

### From the TV

Save a JPEG or PNG to the TV's Download folder. The Downloader app and the Silk browser both
save there. Then open Settings, Background, **Choose a photo on this TV** and pick it. Salotto
looks in Download and hands the picture over through the tunnel, so this needs the tunnel set
up (see [Setup](#setup)) but no storage permission. Pictures whose names have anything other
than letters, numbers, spaces, dots, dashes, brackets or `+` aren't listed, and the page says
how many were left out. The name goes into a shell command, so anything unusual is skipped
rather than escaped.

### From a computer

Send a JPEG or PNG in two steps. The first copies it to the TV's Download folder. The second
hands it to Salotto, which keeps its own copy in private storage. No storage permission is
needed, and it works on Fire OS 7 and 8:

```sh
adb push background.jpg /sdcard/Download/
adb shell "content write --uri content://com.homestead.launcher.background/photo < /sdcard/Download/background.jpg"
```

Then open Settings, **Background**, and select **Photo**. Any size or shape works. The picture
is scaled and cropped to fill the screen, and only that screen-sized copy is kept in memory.
**Photo dimming** darkens it (Off, Light or Dark) so the labels stay readable. Light is the
default.

- To change the picture, run both commands again with the new file. It shows on the next Home
  press. The old picture is replaced only once the new one has arrived whole and reads as an
  image. Anything else is discarded, and the log says why.
- To remove it: `adb shell content delete --uri content://com.homestead.launcher.background/photo`.
  The background is then black, and the Settings row says there is no file.
- Run the second command on the TV, as shown. Piping the file from a Windows computer
  (`adb shell "content write …" < background.jpg`) gave a damaged file in testing.
- On Fire OS 7 only, copying the file straight into Salotto's folder also works:
  `adb push background.jpg /sdcard/Android/data/com.homestead.launcher/files/`. Fire OS 8
  refuses that folder to adb. A picture sent with the commands above takes precedence.

The picture is removed when Salotto is uninstalled.

## Removing Salotto

1. Open **Debloat** and select **Undo everything this app switched off**. This switches back
   on exactly the packages Salotto turned off. Uninstalling the app doesn't do it for you. If
   you switched off Amazon's launcher, this step matters most: without it the TV has no home
   screen until a computer switches the launcher back on.
2. If you used the tunnel, select **Forget our key** in Setup, then revoke the TV's copy under
   Settings, My Fire TV, Developer options, and turn ADB debugging off.
3. Uninstall Salotto under Settings, Applications, Manage Installed Applications.

Uninstalling also removes the accessibility service, so Home goes back to Amazon's home screen
straight away.

To keep Salotto but give the Home button back to Amazon, select **Turn off the Home button**
in Settings or Setup (press it twice to confirm). Salotto stays installed as an ordinary app,
and Re-apply after reboot stops. To turn it back on, select **Finish setup automatically** in
Setup, which needs ADB debugging still on. This switch needs Fire OS 6 or later. To visit
Amazon's home screen for a moment without turning anything off, use the **Amazon Home** tile.

# Building and developing

You only need this to build from source or to change the code. To use Salotto, install the
signed `homestead.apk` from the
[releases](https://github.com/giu-napoletano29/Salotto-FireTv/releases/latest).

## Building

There's no Gradle and no dependencies, and a build takes under ten seconds:

```sh
./build.sh            # build + sign
./build.sh install    # and adb install -r
```

On Windows without Git Bash, `build.ps1` does the same from PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1            # build + sign
powershell -ExecutionPolicy Bypass -File build.ps1 install    # and adb install -r
```

The two scripts must make the same APK, byte for byte. After changing either one, run
`tools/test-builds.sh` from Git Bash. It builds with each script, under Windows PowerShell and
PowerShell 7, in a few setups, and compares the results.

You need the Android SDK (`build-tools/36.1.0`, `platforms/android-34`) and a JDK. The signing
key isn't in this repository. `build.sh` looks in `~/.homestead-keys/`, or at the
`HOMESTEAD_KEYSTORE` and `HOMESTEAD_KEYPASS` environment variables (to make your own key see
[Signing your own copy](#signing-your-own-copy)). Set `ADB_TARGET` to your TV's `ip:port` for
`./build.sh install`.

The ADB client is written from the AOSP sources (`system/core/adb`,
`libcrypto_utils/android_pubkey.c`). No code is copied.

## For developers

Everything is in `src/com/homestead/launcher/`. Each class starts with a comment that says why
it exists. Read it before changing the class.

| Area | Classes |
|---|---|
| Home screen | `MainActivity`, `HomeGrid` (the UI, shared by the activity and the overlay), `AppModel` (tiles, artwork, order), `Backgrounds` (presets, paintings and the photo), `BackgroundProvider` (receives the photo over adb), `UsageTracker`, `HdmiActivity`, `BootReceiver` |
| Home button | `HomeRedirectService` (accessibility service and overlay; its timings are deliberate), `HomeKeyWatcher` (Home presses, from the system log) |
| Pages | `Panel` (a settings page: a sheet over the home screen, or a full page with an explanation pane), `OnboardingPanel` (first-run setup), `SetupPanel`, `SettingsPanel`, `SystemSettingsPanel`, `DisplaySoundPanel` (Screen & sound), `ChannelsPanel` (Canali TV), `HealthPanel` (TV Health), `DebloatPanel`, `CommandsPanel`, `UpdatePanel`, `PhotoPanel` |
| ADB | `AdbBridge` (one worker thread, link state, command batches), `AdbConnection` (the client), `AdbCrypto` (the key), `TunnelVpnService` and `VpnSetupActivity` (the tunnel on the TV) |
| Debloat list | `Debloat` (the package catalogue, the never-touch lists, live locks) |
| Updates | `UpdateChecker` (the GitHub repository is `UpdateChecker.REPO`), `UpdateInstaller`, `UpdateFileProvider` |
| Preferences | `Prefs` |

### Signing your own copy

`./build.sh` needs a JDK (11 or newer), Android build-tools 36.1.0 and platform android-34. It
has been run on Windows (Git Bash) only. It picks the right tool names for Linux and macOS, but
those are untested. `minSdk` is 22 and `targetSdk` is 28. Don't raise `targetSdk`: newer
levels tighten the background-start rules the Home button relies on.

Make a key once:

```sh
mkdir -p ~/.homestead-keys
keytool -genkeypair -keystore ~/.homestead-keys/homestead-release.keystore \
  -alias homestead -keyalg RSA -keysize 2048 -validity 10000
export HOMESTEAD_KEYPASS='<the password you chose>'
./build.sh
```

A build signed with your own key can't install over an official one, or the other way round,
so uninstall first. Uninstalling loses Salotto's record of what it switched off, so run
**Undo everything this app switched off** in Debloat before you do.

### Debugging on a TV

Test on real Fire TV hardware. What the app works around (Amazon's pinned home screen, the
delay on app starts after Home, protected packages, the missing Accessibility entry) doesn't
exist on an emulator or stock Android.

```sh
adb connect <tv-ip>:5555
adb logcat -s Homestead                                        # every log line uses this tag
adb shell settings get secure enabled_accessibility_services   # is the Home button switched on?
adb shell dumpsys accessibility                                # ...and running? look for label=Salotto
adb shell dumpsys activity activities | grep mResumedActivity  # what is on screen
adb shell pm list packages -d                                  # what is switched off
adb exec-out screencap -p > screen.png
```

Log lines worth knowing: `HomeRedirectService connected` (the service started), `Home
pressed` then `overlay shown in … ms` (one Home press, caught by `HomeKeyWatcher`),
`dispatchLag=…` (Amazon's launcher reached the front), `handing off to activity`, `foreground
is now …, removing overlay`, `tunnel up`, `adb connected`, `adb idle, closing`, `package change
[…], grid unchanged` or `…, reloading model once` (package and HDMI input events, gathered
for 1.5 seconds), `opening Settings (key … from device …, source …)` (device -1 is an injected
key and not the remote, and `(HDMI-CEC)` marks one sent over HDMI), and `update check: …` (the
result of each check for a new release).

Things that look like bugs but are Fire OS:

- Fire OS's Accessibility menu doesn't list third-party services. The service has to be
  switched on over ADB.
- The system's Home broadcast is restricted to Amazon-signed apps. The accessibility event for
  Amazon's launcher arrives as its screen is drawn, and not at all if it is already in front.
  The earliest trace an app can read is the `START ... cat=[android.intent.category.HOME] ...
  from uid 1000` line in the system log (tagged ActivityManager on Fire OS 7 and
  ActivityTaskManager on Fire OS 8, where some TVs log it `from uid 0`). That is what
  `HomeKeyWatcher` looks for. `READ_LOGS` takes effect only in a process started after the
  grant, which is why the watcher runs in its own process (`:homekey`).
- Some Amazon apps, such as Luna, handle Home themselves (`CustomHomeButtonHandler: Bypassing
  HOME key` in the log) and then start the home screen under their own uid, so a HOME start
  from the app in front counts as a press too. That hook needs an Amazon-only permission, so
  Salotto can't use it for its own screens.
- Amazon's Settings opens by starting the home screen under its own uid
  (`com.amazon.tv.settings.v2`), then its settings page. That HOME start isn't a press, and
  for a few seconds after it Amazon's home windows don't bring Salotto back.
- Fire OS refuses an ADB connection that starts on the TV itself, which is why the tunnel
  exists. The connection over Wi-Fi from a computer also drops often, so reconnect before each
  command when scripting.
- `pm disable-user` on some Amazon packages fails with "Cannot disable a protected package".
  Batches carry on past it, and TV Health counts those as protected.
- In the system VPN and USB-debugging prompts, no button has focus at first. Press Down to
  reach OK.
- `uiautomator dump` can't read Salotto's windows. Use screenshots.

Salotto pauses the Home redirect while you are on a setup screen (settings, VPN prompt, key
prompt) and lifts the pause when you come back. If Home does nothing during testing, check
whether a pause is active before suspecting the service.

### Rules for changes

- Debloat list: add a package only after switching it off, restarting the TV and switching it
  back on, on a real device, with nothing broken. Say which device in the pull request. `SAFE`
  entries are part of the one-press switch-off. Anything with a visible side effect is
  `CAUTION`. Never add anything on the never-touch lists.
- Everything the app changes must be reversible from the app.
- Keep the accessibility service as narrow as it is (`canRetrieveWindowContent="false"`, no
  key filtering, no package filter). [SECURITY.md](../SECURITY.md) promises this.
- Release notes show as raw text in the app's Update available page, so keep them plain: no
  Markdown links, code spans or headings. Raise `versionCode` and `versionName` in
  `AndroidManifest.xml`. The app compares the release tag (`v1.2`) with `versionName`.
- Write down only what you tested. "Should work on X" belongs in a pull request, not here.

## Reporting a device

If you try Salotto on a device that isn't in the table above, open an issue with the **Device
report** template and say:

- the device's name;
- the model, from `adb shell getprop ro.product.model`;
- the Fire OS version, from `adb shell getprop ro.build.version.name`;
- what you checked: the launcher, the Home button, setup from the TV, TV Health, Debloat.

List only what you actually tried. If Debloat shows rows as "not present", or your TV has
Amazon packages the list doesn't know, attach the output of `adb shell pm list packages -s`
(system packages only, so it doesn't list your own apps).

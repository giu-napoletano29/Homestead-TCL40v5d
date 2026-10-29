# Making a release

A release is a signed `homestead.apk` attached to a GitHub release. The app checks this
repository's latest release once a day and offers it (see the README).

## The signing key comes first

Android only installs an update over an app signed with the **same key**. Lose the key and
nobody can update from the next release: they would have to uninstall Homestead, and its
settings and the granted permissions go with it. So:

1. Keep the keystore (`~/.homestead-keys/homestead-release.keystore`, or whatever
   `HOMESTEAD_KEYSTORE` points to) and its password **in at least two places**, one of them
   off this computer (a password manager, an encrypted drive).
2. Never commit it, and never paste the password into an issue, a log or a chat.
3. After a change of computer, check that `./build.sh` signs and that `apksigner verify`
   accepts the result before relying on it.

To see which key an installed or built APK carries:

```sh
apksigner verify --print-certs homestead.apk
```

Two builds from the same key print the same certificate digest.

## Steps

1. **Version.** In `AndroidManifest.xml` raise `versionName` (the release tag compares against
   it as dotted numbers: `1.7.0` is newer than `1.6.14.1`) and `versionCode` (this fork counts
   from 7810 for 1.7.0, so it stays above the original's own codes).
2. **Changelog.** Add the section to `CHANGELOG.md`; the release notes are the same text.
3. **Build and sign**: `./build.sh` (or `build.ps1`). Install it on the reference devices
   (the Stick and the TCL) and go through the list below.
4. **Commit and tag**: `git tag v1.7.0` on the commit you built, then push the branch and the
   tag.
5. **Publish** on GitHub: a release for that tag, with `homestead.apk` attached and the
   changelog section as its notes. Attach **exactly one** `.apk`, called `homestead.apk`: the
   updater takes the first asset whose name ends in `.apk`.
6. Within a day the devices offer it; **Settings → Check for updates** asks straight away.

## Before publishing: what to try

On each reference device, after installing the signed build over the previous one:

- Home and the red button return to Homestead (Setup shows the redirect **on**; an install
  does not switch it off, but a force-stop of the app does).
- The wallpaper is whole, at the start and after a wake from standby.
- The rail opens and closes, and Back from it returns to Canali TV.
- Canali TV opens each tab, and one Rai and one Mediaset channel play.
- Settings → System settings → Screen & sound opens, and its switches read the right state.
- Memory and frames: `adb shell dumpsys meminfo com.homestead.launcher` and
  `adb shell dumpsys gfxinfo com.homestead.launcher` after about twenty moves of the focus.

Do not `am force-stop` the launcher while testing: Android drops its accessibility service
from the enabled list, and the Home button stops working until Setup is run again.

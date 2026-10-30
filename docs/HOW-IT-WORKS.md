# How it works, and what to know

The Home button, the devices Salotto was tried on, and the known limitations.

## How the Home button works

Fire OS doesn't let any app replace Amazon's launcher as the system home screen. Salotto uses
an accessibility service to notice when Amazon's launcher comes to the front, and covers it
straight away.

Fire OS holds back app launches for several seconds after a Home press, so Salotto first
shows its home screen as an overlay window, which isn't held back. Its normal screen takes
over once Fire OS allows it, with the same tile selected, the same scroll position and any
page you had open, such as Settings. The overlay is an accessibility overlay and needs no
extra permission.

The accessibility service only hears about Amazon's launcher once its screen is being drawn,
a few hundred milliseconds too late to hide it. The system log records the Home press earlier,
as the system starts Amazon's launcher. So a small separate process watches the log for that
one line, and the overlay goes up first. It is also the only way to notice a Home press made
while Amazon's home screen is already in front, since that changes no window. This needs
`READ_LOGS`, which setup grants over ADB. [SECURITY.md](../SECURITY.md) says exactly what is
read.

## Known limitations

- Without `READ_LOGS`, Amazon's home screen shows for a moment on each Home press, and Home
  does nothing while Amazon's home screen is already showing (open any app, then press Home).
  Setup grants it.
- With it, the screen can still go black for a moment between the app you leave and Salotto.
  That is usually under a quarter of a second, longer on the first press after an update.
  Pressing Home while Salotto itself is in front can still show Amazon's home briefly.
- The Amazon Home tile pauses Salotto for 30 seconds. After that, Salotto may take over again
  while you are still browsing Amazon's home screen. Pressing Home brings it back at any
  time.
- Some Android versions forget an "Always allow" debugging key that hasn't been used for some
  days. If that happens and Re-apply after reboot needs to switch something off, the TV's key
  prompt could appear on screen. This hasn't been seen on the Stick or the TCL.
- A Mediaset channel opens on its own link only on the apps and links tested. Canale 5, Italia
  1 and Rete 4 are checked. The others use the same pattern but weren't tried one by one, and
  Mediaset can change the app's links at any time.
- On Fire OS 7, Back from a Settings page can land on Salotto. Amazon's main Settings screen
  there is a tab of its home screen, so going back to it from a page such as Network looks
  like going home. Open Settings again to carry on.
- The TCL's input menu is opened by pressing the TV's INPUT key through the tunnel. The first
  press after a long idle can take a few seconds while the tunnel reconnects. The Inputs
  entry starts that when it gets focus. If it can't, Salotto shows its own list of inputs.

## Devices and Debloat

The original's README lists a few more devices where Homestead worked (an Insignia and a
Hisense TV, and the Fire TV Stick 4K). Salotto has not been tried on them, but it should
behave the same. If you try it, please [say so](#reporting-a-device).

Debloat's package list was checked by hand on an Insignia TV. Another model or Fire OS
version may use different package names. A package the list has but your TV doesn't is shown
as "not present" and skipped. Packages the list doesn't know are only reachable through
**Browse every system app**, which locks what the system is using but can't tell you what is
safe to switch off.

---
name: Device report
about: Tell me Salotto works (or not) on a device that is not in the README table
labels: device
---

**Device**
- Name:
- Model (`adb shell getprop ro.product.model`):
- Fire OS version (`adb shell getprop ro.build.version.name`):

**What you tried** (only what you actually did):
- [ ] Launcher shows and works
- [ ] Home button comes to Salotto (and the red button, if you set it)
- [ ] Setup from the TV, or from a computer
- [ ] Screen & sound (HDMI-CEC, overscan, sleep)
- [ ] Canali TV, and a channel opens
- [ ] Debloat / TV Health

**What did not work, if anything**

If Debloat shows rows as "not present", or the device has Amazon packages the list does not
know, add the output of `adb shell pm list packages -s`.

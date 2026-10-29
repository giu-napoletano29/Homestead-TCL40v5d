---
name: Bug report
about: Something does not work on your TV or stick
labels: bug
---

**Device**
- Model (`adb shell getprop ro.product.model`):
- Fire OS version (`adb shell getprop ro.build.version.name`):
- Homestead version (Settings, or the release you installed):
- TV or display it is connected to:

**What happened**

**What you expected**

**Steps**
1.
2.

**Setup page** (open Setup from Settings and say what it shows for *Home-button redirect*
and *Instant home screen*):

**Log**, if you have a computer. Right after the problem:

```
adb logcat -d -s Homestead:I
```

Do not paste anything secret: the log has none of your passwords or keys, but look it over.

If this is a **Vega OS** stick (Fire TV Stick 4K Select, or the 2026 Fire TV Stick 4K with no
"Plus" or "Max"), Homestead cannot run on it and there is nothing to report.

## High-level summary
SurfaceFlinger dump from Android 14 emulator (Television_1080p_ATV, 1920x1080@60Hz). System idle/uptime ~14h, running Google TV Launcher (MainActivity + FavoriteLaunchItemsActivity). No crashes, ANRs, or explicit errors visible. Minor jank with 49 HWC-missed frames total.

## Key findings
- **Performance/jank**: 49 total missed frames (all HWC, 0 GPU). VSync idle; app/SF durations ~16.67ms (matches 60Hz). Last VSync dispatches 25s+ ago.
- **Layers**: 63 visible layers (mostly overlays: magnifiers, cutouts, OneHanded, IME— all empty, zero bounds/buffers). Active: TV Launcher MainActivity (full-screen buffer), dim layer (Task=1), FavoriteLaunchItemsActivity (full-screen buffer, PREMULTIPLIED blend).
- **Composition**: HWC DEVICE/SOLID_COLOR for 3 output layers. No client/GPU composition.
- **Resources**: Skia GPU cache ~1.3MB (normal). No memory/ANR traces in dump.
- **Emulator artifacts**: QEMU ATV config; hwcodec enabled; no wide-color/HDR.

## Suspected root causes
- None definitive (partial dump, no logs/traces). Missed frames likely emulator VSync desync or layer stack overhead (63 layers). No app-specific perf issues evident.

## Suggested next steps
- Capture full bugreport (incl. traces, meminfo, system_log, ANR/events). 
- Reproduce with `adb shell dumpsys gfxinfo <pkg>` or systrace (surfaceflinger, 10s) for frame timelines/jank.
- Check app-side: Profile TV Launcher FavoriteLaunchItemsActivity for buffer latency.
- If jank persists: Reduce emulator layers (disable accessibility/magnification); test on physical TV device.
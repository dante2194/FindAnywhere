# Find Anywhere — Ctrl+F for any app

Accessibility service + overlay find bar. Reads the on-screen UI tree of whatever app is in
front, highlights matches, scrolls to off-screen ones, falls back to OCR when a screen exposes
no text.

## Trigger it
- Quick Settings tile "Find on screen"
- Accessibility button / shortcut
- MacroDroid: Launch Activity -> "Find on screen"
- Broadcast / activity intent: action `com.findanywhere.action.SHOW|HIDE|TOGGLE`,
  extras `query` (string), `fuzzy` (bool), `ocr` (bool)
- URL: `findanywhere://search?q=hello&mode=show&fuzzy=1&ocr=0`

## Notes
- Sideloaded APK on Android 13+: App info -> ⋮ -> "Allow restricted settings" before enabling the service.
- Xiaomi/HyperOS: enable Autostart + "Display pop-up windows while running in background", battery = No restrictions.
- OCR uses ML Kit (Latin script only) and Android 11+ `takeScreenshot`. FLAG_SECURE windows cannot be captured.
- During scroll scans, rows with identical text count once.

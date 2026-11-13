#!/usr/bin/env bash
# Aura launcher – one-time device setup (safe to re-run; re-run after every Chromecast reboot
# to bring back the "power features" bridge).
set -euo pipefail
PKG=com.nodevortex.aura
A11Y="$PKG/$PKG.system.AuraService"

echo "• Permissions (Wi-Fi scanning / Bluetooth discovery need location; visualiser needs audio)"
for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION RECORD_AUDIO; do adb shell pm grant $PKG android.permission.$p || true; done

echo "• Allow over-Wi-Fi self updates + compile Aura to native code"
adb shell appops set $PKG REQUEST_INSTALL_PACKAGES allow || true
adb shell cmd package compile -m speed -f $PKG >/dev/null || true

echo "• Notification access (pop-ups, now-playing pill)"
adb shell cmd notification allow_listener $PKG/$PKG.system.NotificationService

echo "• Accessibility helper (volume slider, pop-ups, music screensaver, lock)"
cur=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
if [[ "$cur" != *"$A11Y"* ]]; then
  if [[ -z "$cur" || "$cur" == "null" ]]; then new="$A11Y"; else new="$cur:$A11Y"; fi
  adb shell settings put secure enabled_accessibility_services "$new"
fi
adb shell settings put secure accessibility_enabled 1

echo "• Sleep-timer permission (device admin: lets Aura put the TV to sleep)"
adb shell dpm set-active-admin $PKG/.system.SleepAdmin 2>/dev/null || true

echo "• Make Aura the real default home screen (survives reboots)"
bash "$(dirname "$0")/make-default.sh"

echo "• Power-features bridge (adb over 127.0.0.1:5555 – resets when the Chromecast reboots)"
adb tcpip 5555 >/dev/null && sleep 3
adb wait-for-usb-device 2>/dev/null || true
echo "Done. On the TV: Aura settings → Power features → OK, then allow \"aura@launcher\" (tick Always allow)."

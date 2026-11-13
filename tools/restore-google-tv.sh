#!/usr/bin/env bash
# Undo everything: Google TV's launcher back as home, VeloCinema's helpers off.
PKG=com.nodevortex.aura
adb shell am start -n $PKG/.HomeActivity --es aura.open real-home-off >/dev/null 2>&1
adb shell pm enable com.google.android.tungsten.setupwraith
adb shell pm enable com.google.android.apps.tv.launcherx
adb shell cmd role add-role-holder android.app.role.HOME com.google.android.apps.tv.launcherx 0
cur=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
adb shell settings put secure enabled_accessibility_services "$(echo "$cur" | sed "s#:\?$PKG/$PKG.system.AuraService##")"
adb shell cmd notification disallow_listener $PKG/$PKG.system.NotificationService
adb shell dpm remove-active-admin $PKG/.system.SleepAdmin 2>/dev/null || true
adb usb >/dev/null 2>&1 || true
echo "Google TV launcher restored."

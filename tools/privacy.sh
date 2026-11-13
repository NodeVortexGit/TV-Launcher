#!/usr/bin/env bash
# Turn off the Google TV telemetry that can be switched off without breaking anything.
# Reversible: see the "undo" lines at the bottom.
set -u
echo "• Error/crash report sending off";   adb shell settings put global send_action_app_error 0; adb shell settings put secure send_action_app_error 0
echo "• Upload of sideloaded apps to Google off"; adb shell settings put global upload_apk_enable 0
echo "• Google feedback / crash-report apps disabled"
for p in com.google.android.feedback com.android.tv.feedbackconsent; do adb shell pm disable-user --user 0 $p 2>&1 | sed 's/^/    /'; done
echo "• Let Aura's optional tracker-blocking DNS switch work"
adb shell pm grant com.nodevortex.aura android.permission.WRITE_SECURE_SETTINGS
echo "Done. Google's own 'Usage & diagnostics' switch: Aura settings → Поверителност."
# Undo:
#   adb shell settings put global send_action_app_error 1; adb shell settings put secure send_action_app_error 1
#   adb shell settings put global upload_apk_enable 1
#   adb shell pm enable com.google.android.feedback; adb shell pm enable com.android.tv.feedbackconsent

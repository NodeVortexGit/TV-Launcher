#!/usr/bin/env bash
# Make VeloHome the real default home screen (survives reboots).
#
# Why this is needed: Google TV's launcher declares Home intent priority 2 and Google's setup app
# priority 1. Android picks the highest-priority Home app before it even looks at the user's chosen
# default, and normal apps are capped at priority 0 — so no "set default" can win while those two
# are enabled. Disabling them (fully reversible, no root) lets Android pick VeloHome natively.
# VeloHome re-enables Google's launcher for a few seconds whenever you switch Google TV profiles.
set -u
PKG=com.nodevortex.aura
adb shell am start -n $PKG/.HomeActivity --es aura.open real-home-on >/dev/null
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
adb shell cmd package set-home-activity $PKG/.HomeActivity >/dev/null
adb shell cmd role add-role-holder android.app.role.HOME $PKG 0 || true
echo -n "Home now resolves to: "
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1

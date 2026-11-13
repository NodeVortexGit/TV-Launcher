# Run by VeloCinema through the system connection (adb shell). Switches Google's setup app on until
# the cast service has loaded the Chromecast's factory credentials from it, then off again.
# Prints "loaded <seconds>" or "timeout <seconds>".
trap '' HUP                      # finish (and switch the app off) even if the connection drops
W=com.google.android.tungsten.setupwraith
T0=$(date +%s)
pm enable --user 0 $W >/dev/null
res=timeout; i=0
while [ $i -lt 45 ]; do
  sleep 2; i=$((i+1))
  st=$(logcat -d -v epoch -s cast_ClientAuthCredsProvisioning:I cast_ClientAuthCredsFactoryProvisioned:E | awk -v t=$T0 -v now=$(date +%s) '
    $1+0 < t { next }
    /Loading factory credentials/ { p=1; ts=$1+0 }
    /Cannot get device credentials/ { p=0 }
    END { if (p && now - ts >= 3) print "loaded"; else print "wait" }')
  [ "$st" = loaded ] && { res=loaded; break; }
done
pm disable-user --user 0 $W >/dev/null
echo "$res $((i*2))"

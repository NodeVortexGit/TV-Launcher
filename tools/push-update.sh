#!/usr/bin/env bash
# Install a new VeloCinema build over Wi-Fi (no USB): VeloCinema receives the APK and Android asks "Update?" on the TV.
# Usage: tools/push-update.sh [tv-ip]   (finds the TV on the local network if no IP is given)
set -euo pipefail
cd "$(dirname "$0")/.."
APK=app/build/outputs/apk/release/app-release.apk
IP=${1:-}
if [ -z "$IP" ]; then
  NET=$(ip -4 -o addr show | awk '!/ lo /{print $4}' | head -1 | cut -d. -f1-3)
  for i in $(seq 1 254); do (timeout 0.5 bash -c "echo > /dev/tcp/$NET.$i/8765" 2>/dev/null && echo "$NET.$i") & done | head -1 > /tmp/.aura_ip || true
  wait; IP=$(cat /tmp/.aura_ip)
fi
[ -n "$IP" ] || { echo "TV not found on the network"; exit 1; }
echo "Sending $(du -h "$APK" | cut -f1) to $IP …"
curl -sS --max-time 120 -X POST --data-binary @"$APK" -H "Content-Type: application/vnd.android.package-archive" "http://$IP:8765/api/update"; echo

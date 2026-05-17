#!/usr/bin/env bash
# Pre-cache OSM tiles for the Kognis Lite demo region so the offline map
# renders real terrain in the demo video instead of gray squares.
#
# Default region: Hispaniola — Santo Domingo metropolitan area.
# Default zooms: 12..16 (city level → street level). Add 17 if needed (≈ 4x size).
#
# Usage:
#   ./scripts/precache_tiles.sh                       # downloads + pushes default region
#   BBOX="18.40,-69.95,18.55,-69.85" ./scripts/precache_tiles.sh
#   ZOOMS="12 13 14 15 16 17" ./scripts/precache_tiles.sh
#
# Output: /sdcard/osmdroid/ on the connected ADB device (osmdroid's default
#         offline tile path). After this, the app can render the region with
#         the device in airplane mode.
#
# Tile source: tile.openstreetmap.org (ODbL — no API key needed; rate-limited).
# Tiles are stored in osmdroid's filesystem-provider format:
#   {base}/Mapnik/{z}/{x}/{y}.png.tile

set -euo pipefail

BBOX="${BBOX:-18.40,-69.95,18.55,-69.85}"   # lat_min,lon_min,lat_max,lon_max
ZOOMS="${ZOOMS:-12 13 14 15 16}"
LOCAL_DIR="${LOCAL_DIR:-/tmp/kognis_tiles}"
REMOTE_DIR="${REMOTE_DIR:-/sdcard/osmdroid/tiles}"
TILE_SOURCE="${TILE_SOURCE:-Mapnik}"
USER_AGENT="${USER_AGENT:-KognisLiteTilePrecache/1.0 (io.kognis.lite.sar)}"

IFS=',' read -r LAT_MIN LON_MIN LAT_MAX LON_MAX <<<"$BBOX"

deg2num() {
  local lat="$1" lon="$2" z="$3"
  python3 - <<PY
import math
lat, lon, z = $lat, $lon, $z
n = 2 ** z
x = int((lon + 180.0) / 360.0 * n)
lat_rad = math.radians(lat)
y = int((1.0 - math.log(math.tan(lat_rad) + 1/math.cos(lat_rad)) / math.pi) / 2.0 * n)
print(f"{x} {y}")
PY
}

echo "BBOX=$BBOX  ZOOMS='$ZOOMS'"
mkdir -p "$LOCAL_DIR"

total=0
for z in $ZOOMS; do
  read -r X_MIN Y_MAX <<<"$(deg2num "$LAT_MIN" "$LON_MIN" "$z")"
  read -r X_MAX Y_MIN <<<"$(deg2num "$LAT_MAX" "$LON_MAX" "$z")"
  # Swap if needed
  if (( X_MIN > X_MAX )); then tmp=$X_MIN; X_MIN=$X_MAX; X_MAX=$tmp; fi
  if (( Y_MIN > Y_MAX )); then tmp=$Y_MIN; Y_MIN=$Y_MAX; Y_MAX=$tmp; fi
  echo "z=$z  x=$X_MIN..$X_MAX  y=$Y_MIN..$Y_MAX  ($(( (X_MAX-X_MIN+1)*(Y_MAX-Y_MIN+1) )) tiles)"
  for (( x=X_MIN; x<=X_MAX; x++ )); do
    for (( y=Y_MIN; y<=Y_MAX; y++ )); do
      out="$LOCAL_DIR/$TILE_SOURCE/$z/$x"
      mkdir -p "$out"
      file="$out/$y.png.tile"
      if [[ -s "$file" ]]; then continue; fi
      url="https://tile.openstreetmap.org/$z/$x/$y.png"
      curl -sS --max-time 15 -A "$USER_AGENT" -o "$file" "$url" || { echo "  miss: $url"; rm -f "$file"; }
      total=$((total + 1))
      # Light rate-limit — OSM tile policy
      if (( total % 8 == 0 )); then sleep 1; fi
    done
  done
done

echo "Downloaded $total tiles to $LOCAL_DIR"
du -sh "$LOCAL_DIR"

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not in PATH — local cache ready at $LOCAL_DIR (skip device push)."
  exit 0
fi

if ! adb get-state >/dev/null 2>&1; then
  echo "No device attached — local cache ready at $LOCAL_DIR."
  echo "Push later with: adb push $LOCAL_DIR/* $REMOTE_DIR/"
  exit 0
fi

echo "Pushing tiles to $REMOTE_DIR …"
adb shell mkdir -p "$REMOTE_DIR" || true
adb push "$LOCAL_DIR/$TILE_SOURCE" "$REMOTE_DIR/" >/dev/null
echo "Done. Open the app, navigate to Santo Domingo, then toggle airplane mode."

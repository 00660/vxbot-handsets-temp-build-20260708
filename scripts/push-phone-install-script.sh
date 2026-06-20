#!/usr/bin/env sh
set -eu

DEVICE="${DEVICE:-192.168.2.89:5555}"
REMOTE_APK="${REMOTE_APK:-/data/local/tmp/hs-wechatbot-latest.apk}"
REMOTE_SCRIPT="${REMOTE_SCRIPT:-/data/local/tmp/phone-install-vxbot.sh}"

if [ "$#" -lt 1 ]; then
    echo "usage: $0 <apk-path>" >&2
    exit 2
fi

APK="$1"
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PHONE_SCRIPT="$SCRIPT_DIR/phone-install-vxbot.sh"

if [ ! -f "$APK" ]; then
    echo "apk not found: $APK" >&2
    exit 3
fi

if [ ! -f "$PHONE_SCRIPT" ]; then
    echo "phone install script not found: $PHONE_SCRIPT" >&2
    exit 4
fi

adb connect "$DEVICE"
adb -s "$DEVICE" push "$APK" "$REMOTE_APK"
adb -s "$DEVICE" push "$PHONE_SCRIPT" "$REMOTE_SCRIPT"

cat <<EOF
Pushed:
  APK:    $REMOTE_APK
  Script: $REMOTE_SCRIPT

Install from the phone shell:
  adb -s $DEVICE shell
  su
  id
  sh $REMOTE_SCRIPT
EOF

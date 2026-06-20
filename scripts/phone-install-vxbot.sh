#!/system/bin/sh

APK="${1:-/data/local/tmp/hs-wechatbot-latest.apk}"
PKG="com.vxbot.wechatbot"

CURRENT_ID="$(id 2>/dev/null || true)"
case "$CURRENT_ID" in
    uid=0*|*\ uid=0*) ;;
    *)
        echo "ERROR: run this script after entering adb shell and executing su."
        echo "$CURRENT_ID"
        exit 10
        ;;
esac

if [ ! -f "$APK" ]; then
    echo "ERROR: APK not found: $APK"
    exit 11
fi

pm install -r -d "$APK"
STATUS="$?"
if [ "$STATUS" -ne 0 ]; then
    echo "ERROR: pm install failed: $STATUS"
    exit "$STATUS"
fi

pm list packages --user 0 --show-versioncode | grep "$PKG" || true
dumpsys package "$PKG" | grep -E 'versionCode|versionName' || true

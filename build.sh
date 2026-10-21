#!/usr/bin/env bash
# build.sh <x.apk or merged .apkm> <keystore> <storepass> -> out/X-longpress.apk
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd); IN=$1; KS=$2; PASS=$3
SDK=${ANDROID_SDK:-/workspace/android-sdk}; BT=$(ls -d $SDK/build-tools/* | sort -V | tail -1)
mkdir -p "$HERE/tools" "$HERE/out"; W=$(mktemp -d)
[ -f "$HERE/tools/apktool.jar" ] || curl -sL -o "$HERE/tools/apktool.jar" https://github.com/iBotPeaches/Apktool/releases/download/v2.12.1/apktool_2.12.1.jar
[ -f "$HERE/tools/APKEditor.jar" ] || curl -sL -o "$HERE/tools/APKEditor.jar" https://github.com/REAndroid/APKEditor/releases/download/V1.4.5/APKEditor-1.4.5.jar
case "$IN" in *.apkm|*.xapk|*.apks) java -jar "$HERE/tools/APKEditor.jar" m -i "$IN" -o "$W/base.apk"; IN="$W/base.apk";; esac
java -jar "$HERE/tools/apktool.jar" d -r -f -o "$W/d" "$IN"
APK_IN="$IN" APKTOOL_JAR="$HERE/tools/apktool.jar" EXT_DEX_OUT="$W/classes.dex.ext" python3 "$HERE/patch.py" "$W/d"
java -jar "$HERE/tools/apktool.jar" b -o "$W/u.apk" "$W/d"
python3 -c "import zipfile,sys;zipfile.ZipFile(sys.argv[1],'a',zipfile.ZIP_DEFLATED).write(sys.argv[2],sys.argv[3])" "$W/u.apk" "$W/classes.dex.ext" "$(cat $W/ext_dex_name)"
"$BT/zipalign" -f -P 16 4 "$W/u.apk" "$W/a.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$PASS" --out "$HERE/out/X-longpress.apk" "$W/a.apk"
"$BT/apksigner" verify -v "$HERE/out/X-longpress.apk" | head -5

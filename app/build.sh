#!/bin/bash
# 手工构建（不依赖 Gradle/AGP）：aapt2 + javac + d8 + zipalign + apksigner
set -e
cd "$(dirname "$0")"   # 允许从任意目录调用

SDK=${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}
BT=$SDK/build-tools/36.0.0
JAR=$SDK/platforms/android-36/android.jar
OUT=build
PKG=app.tb320fc.ghostroot

[ -f "$JAR" ] || { echo "缺少 $JAR"; exit 1; }

mkdir -p libs
if [ ! -f libs/api.jar ]; then
  curl -sL -o libs/api.aar https://repo1.maven.org/maven2/dev/rikka/shizuku/api/13.1.5/api-13.1.5.aar
  (cd libs && unzip -o -q api.aar classes.jar && mv classes.jar api.jar && rm -f api.aar)
fi
if [ ! -f libs/aidl.jar ]; then
  curl -sL -o libs/aidl.aar https://repo1.maven.org/maven2/dev/rikka/shizuku/aidl/13.1.5/aidl-13.1.5.aar
  (cd libs && unzip -o -q aidl.aar classes.jar && mv classes.jar aidl.jar && rm -f aidl.aar)
fi
if [ ! -f libs/provider.jar ]; then
  curl -sL -o libs/provider.aar https://repo1.maven.org/maven2/dev/rikka/shizuku/provider/13.1.5/provider-13.1.5.aar
  (cd libs && unzip -o -q provider.aar classes.jar && mv classes.jar provider.jar && rm -f provider.aar)
fi

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

find src/main/java -name '*.java' > "$OUT/sources.txt"
javac --release 11 -nowarn \
      -cp "$JAR:libs/api.jar:libs/provider.jar:libs/aidl.jar" \
      -d "$OUT/classes" @"$OUT/sources.txt"

"$BT/d8" --lib "$JAR" --min-api 31 --output "$OUT/dex" \
      $(find "$OUT/classes" -name '*.class') libs/api.jar libs/provider.jar libs/aidl.jar

mkdir -p "$OUT/res"
if [ -d src/main/res ]; then
  "$BT/aapt2" compile --dir src/main/res -o "$OUT/res" 
else
  : > "$OUT/res/.keep"
fi
"$BT/aapt2" link -o "$OUT/base.apk" -I "$JAR" \
      $(ls "$OUT"/res/*.flat 2>/dev/null) \
      --manifest src/main/AndroidManifest.xml \
      -A src/main/assets \
      --min-sdk-version 31 --target-sdk-version 34 \
      --version-code 1 --version-name 1.0

cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f ghostroot.keystore ]; then
  keytool -genkeypair -keystore ghostroot.keystore -alias ghostroot \
      -keyalg RSA -keysize 2048 -validity 10000 \
      -storepass ghostroot -keypass ghostroot \
      -dname "CN=TB320FC temp root" >/dev/null 2>&1
fi

"$BT/apksigner" sign --ks ghostroot.keystore --ks-pass pass:ghostroot \
      --key-pass pass:ghostroot --out tb320fc-ghostroot.apk "$OUT/aligned.apk"
"$BT/apksigner" verify --print-certs tb320fc-ghostroot.apk | head -3
echo "OK -> tb320fc-ghostroot.apk"

#!/bin/bash
# 从上游 GhostLock 源码构建本机适配的 native exploit
#   用法： ./build.sh [上游目录]     默认 ./ghostlock-app
set -e
UPSTREAM=${1:-$PWD/ghostlock-app}
COMMIT=4bcf7043da542bc1c46fe0d3fd7dc336928218c4
PATCH=$(cd "$(dirname "$0")" && pwd)/patches/ghostlock-5.10.209-tb320fc.patch

[ -n "$ANDROID_NDK_HOME" ] || { echo "先 export ANDROID_NDK_HOME=<ndk 路径>"; exit 1; }

if [ ! -d "$UPSTREAM/.git" ]; then
  echo "== clone 上游 GhostLock"
  git clone https://github.com/YuKongA/ghostlock-app.git "$UPSTREAM"
fi
cd "$UPSTREAM"
git fetch --all --quiet
git checkout -q "$COMMIT"
git reset --hard -q "$COMMIT"
echo "== 应用补丁"; git apply "$PATCH"
echo "== 构建"
make -C src ghostlock API=34
echo "== 产物: $UPSTREAM/build/native/ghostlock"

#!/usr/bin/env bash
#
# Build a native Linux installer (.deb) for Mythosaur RE using jpackage.
# A self-contained Java runtime is bundled, so end users do NOT need Java installed.
# Run this ON a Linux machine (jpackage cannot cross-build for other OSes).
#
# Requirements: JDK 21+ (jpackage), dpkg-deb + fakeroot (for .deb).
#   Output: dist/mythosaur-re_<version>_<arch>.deb
#
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="1.0.0"
MAIN_JAR="mythosaur-jvm-${VERSION}.jar"

echo "==> Building application image (gradle installDist)…"
./gradlew clean installDist -q

echo "==> Running jpackage (.deb)…"
rm -rf dist && mkdir -p dist

jpackage \
  --type deb \
  --name mythosaur-re \
  --app-version "${VERSION}" \
  --description "Dedicated open-source APK reverse-engineering IDE" \
  --vendor "durgeshkt" \
  --copyright "Mythosaur RE — MIT License" \
  --input build/install/mythosaur-jvm/lib \
  --main-jar "${MAIN_JAR}" \
  --main-class com.mythosaur.Main \
  --java-options -Xmx6g \
  --java-options -XX:+UseG1GC \
  --java-options -XX:MaxMetaspaceSize=512m \
  --java-options -Dsun.java2d.opengl=false \
  --add-modules "java.base,java.compiler,java.desktop,java.sql,java.logging,java.naming,java.xml,java.management,jdk.jdi,jdk.unsupported,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.zipfs" \
  --jlink-options "--strip-debug --no-header-files --no-man-pages" \
  --license-file src/main/resources/TERMS.txt \
  --icon packaging/icon.png \
  --linux-shortcut \
  --linux-menu-group "Development" \
  --linux-app-category "devel" \
  --dest dist

echo
echo "==> Done. Installer:"
ls -lh dist/*.deb
echo
echo "Install with:  sudo apt install ./dist/mythosaur-re_${VERSION}_*.deb"
echo "Launch from your app menu (Development → Mythosaur RE) or:  mythosaur-re"

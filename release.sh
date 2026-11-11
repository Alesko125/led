#!/bin/bash
# Реліз «Підсвітки» на GitHub: збирає підписаний APK і викладає його як реліз v<versionCode>.
# Застосунок у тестя сам знайде його протягом 12 годин або одразу при відкритті.
#
# Перед запуском підніми versionCode і versionName в android/app/build.gradle.kts і закоміть.
# Використання: ./release.sh "Що змінилось — одним-двома реченнями"
set -euo pipefail
cd "$(dirname "$0")/android"

export JAVA_HOME="${JAVA_HOME:-C:/Program Files/Android/Android Studio/jbr}"
[ -f keystore.properties ] || { echo "Немає android/keystore.properties: без ключа оновлення не стане поверх"; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "Є незакомічені зміни — спершу закоміть"; exit 1; }

code=$(grep -E '^\s*versionCode\s*=' app/build.gradle.kts | grep -oE '[0-9]+')
name=$(grep -E '^\s*versionName\s*=' app/build.gradle.kts | grep -oE '"[^"]+"' | tr -d '"')
tag="v$code"
gh release view "$tag" >/dev/null 2>&1 && { echo "Реліз $tag уже є — підніми versionCode"; exit 1; }

./gradlew.bat --no-daemon -q assembleRelease
apk="app/build/outputs/apk/release/Pidsvitka-$name.apk"
cp app/build/outputs/apk/release/app-release.apk "$apk"

git push -q origin HEAD
gh release create "$tag" "$apk" --title "Підсвітка $name" --notes "${1:-Оновлення $name}"
echo "Готово: $tag ($name)"

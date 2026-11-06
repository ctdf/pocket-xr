#!/bin/sh
# 1.1.15: builds and tests the Ko-fi Menu row in the GitHub build and the Play build, with Android Studio's JBR.
cd "$(dirname "$0")/.." || exit 1
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
G=$(ls -d ~/.gradle/wrapper/dists/gradle-9.8.2-bin/*/*/bin/gradle | head -1)
summary() {
  for f in "$@"; do
    grep -o 'testsuite name="[^"]*" tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$f"
  done
}
totals() {
  cat "$1"/*.xml | grep -o '<testsuite [^>]*' | sed 's/.*tests="\([0-9]*\)" skipped="\([0-9]*\)" failures="\([0-9]*\)" errors="\([0-9]*\)".*/\1 \2 \3 \4/' |
    awk '{t+=$1; s+=$2; f+=$3; e+=$4} END {print "tests " t ", skipped " s ", failures " f ", errors " e}'
}

echo "== GitHub build: core:test, app:testDebugUnitTest, app:assembleDebug, app:lintDebug"
"$G" --console=plain --continue :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug > "$TEMP/kofi-github.log" 2>&1
echo "exit $?"
grep -E "BUILD (SUCCESSFUL|FAILED)|FAILED|^e: " "$TEMP/kofi-github.log" | head -30
echo "core: $(totals core/build/test-results/test)"
echo "app:  $(totals app/build/test-results/testDebugUnitTest)"
summary core/build/test-results/test/TEST-com.hayeco.flipspaces.core.SupportTest.xml \
  app/build/test-results/testDebugUnitTest/TEST-com.hayeco.flipspaces.SupportMenuRowTest.xml
grep -o 'errors="[0-9]*"\|severity="Error"' app/build/reports/lint-results-debug.xml 2>/dev/null | sort | uniq -c
grep -B1 -A3 'severity="Error"' app/build/reports/lint-results-debug.xml 2>/dev/null | grep -E 'id=|message=|file=' | head -20
ls -l app/build/outputs/apk/debug/*.apk

echo "== Play build: the Ko-fi and update tests"
"$G" --console=plain :app:testDebugUnitTest -Ppocketxr.play=true --tests '*SupportMenuRowTest' --tests '*UpdateNotice1115Test' > "$TEMP/kofi-play.log" 2>&1
echo "exit $?"
grep -E "BUILD (SUCCESSFUL|FAILED)|FAILED" "$TEMP/kofi-play.log" | head
summary app/build/test-results/testDebugUnitTest/TEST-com.hayeco.flipspaces.SupportMenuRowTest.xml \
  app/build/test-results/testDebugUnitTest/TEST-com.hayeco.flipspaces.UpdateNotice1115Test.xml

echo "== Copy: no em or en dashes, no \"we\" in the changed docs"
grep -n "—\|–" CHANGELOG.md docs/pr/ko-fi-support-card.md README.md | head
sed -n '/## Unreleased/,/## 1.1.14/p' CHANGELOG.md | grep -niw "we" | head

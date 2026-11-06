#!/bin/sh
# 1.1.15: the Ko-fi and update tests in the Play build (-Ppocketxr.play=true), with Android Studio's JBR.
# The Play path needs a keystore.properties at the repository root (docs/PLAY.md); unit tests never sign with it.
cd "$(dirname "$0")/.." || exit 1
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
G=$(ls -d ~/.gradle/wrapper/dists/gradle-9.8.2-bin/*/*/bin/gradle | head -1)
rm -f app/build/test-results/testDebugUnitTest/TEST-com.hayeco.flipspaces.SupportMenuRowTest.xml app/build/test-results/testDebugUnitTest/TEST-com.hayeco.flipspaces.UpdateNotice1115Test.xml
"$G" --console=plain :app:testDebugUnitTest -Ppocketxr.play=true --tests '*SupportMenuRowTest' --tests '*UpdateNotice1115Test' > "$TEMP/kofi-play.log" 2>&1
echo "exit $?"
grep -E "BUILD (SUCCESSFUL|FAILED)|FAILED|What went wrong" -A2 "$TEMP/kofi-play.log" | head -20
for f in SupportMenuRowTest UpdateNotice1115Test; do
  x=app/build/test-results/testDebugUnitTest/TEST-com.hayeco.flipspaces.$f.xml
  grep -o 'testsuite name="[^"]*" tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$x"
  grep -o '<testcase name="[^"]*"' "$x" | sed 's/<testcase name=//'
  grep -B1 '<skipped' "$x" | grep -o 'testcase name="[^"]*"' | sed 's/^/  skipped: /'
done

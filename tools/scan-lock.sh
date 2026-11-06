#!/bin/sh
# Scans used while building "Lock screens", the resize handle threshold and launch display fixes.
cd "$(dirname "$0")/.." || exit 1
S="app/src/main/kotlin core/src/main/kotlin"
case "$1" in
  resize) grep -rn -i -E 'corner|resizeHandle|HitZone|PanelHit|longPress|holdMs|HOLD_' $S | grep -v -i -E 'cornerRadius|round' | cut -c1-220 ;;
  launch) grep -rn -E 'setLaunchDisplayId|launchDisplayId|moveTaskToDisplay|onTaskMovedToDisplay|TaskStackListener|DEFAULT_DISPLAY|FLAG_ACTIVITY_NEW_TASK|startActivityAsUser|ActivityOptions' $S | cut -c1-220 ;;
  menu) grep -rn -i -E 'sidebar|Head tracking|headTrackingOn|MenuRow|menuItems' $S | cut -c1-220 ;;
  dashes) git diff origin/main -U0 | grep '^+' | grep -n -E '—|–|\bwe\b|\bWe\b' ;;
  linterrors) grep -E ': Error: ' app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt | sed -E 's#^.*s_16125922.##' | cut -c1-200 ;;
  results)
    for f in app/build/test-results/testDebugUnitTest/*.xml core/build/test-results/test/*.xml; do
      grep -o -E '<testsuite name="[^"]*" tests="[0-9]+" skipped="[0-9]+" failures="[0-9]+" errors="[0-9]+"' "$f"
    done | awk -F'"' '{t+=$4; f+=$8; e+=$10} /1115/ {print} END {print "total tests=" t " failures=" f " errors=" e}'
    ls -la app/build/outputs/apk/debug/ 2>&1 ;;
  *) grep -rn -E "$1" $S | cut -c1-220 ;;
esac

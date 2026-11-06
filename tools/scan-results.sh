#!/bin/sh
# Sums the unit test results and the lint errors of the last build.
cd "$(dirname "$0")/.." || exit 1
for f in core/build/test-results/test/*1115*.xml app/build/test-results/testDebugUnitTest/*1115*.xml; do
  grep -o -E '<testsuite name="[^"]*" tests="[0-9]+" skipped="[0-9]+" failures="[0-9]+" errors="[0-9]+"' "$f"
done
cat core/build/test-results/test/*.xml app/build/test-results/testDebugUnitTest/*.xml | grep -o -E 'tests="[0-9]+" skipped="[0-9]+" failures="[0-9]+" errors="[0-9]+"' | awk -F'"' '{t+=$2; f+=$6; e+=$8} END {print "all suites: tests", t, "failures", f, "errors", e}'
if [ -f app/build/reports/lint-results-debug.xml ]; then
  echo "lint errors: $(grep -c 'severity="Error"' app/build/reports/lint-results-debug.xml)"
  grep -B1 -A6 'severity="Error"' app/build/reports/lint-results-debug.xml | grep -E 'id=|file=' | head -40
fi

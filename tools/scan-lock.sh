#!/bin/sh
# Scans used while building "Lock screens", the resize handle threshold and launch display fixes.
cd "$(dirname "$0")/.." || exit 1
S="app/src/main/kotlin core/src/main/kotlin"
case "$1" in
  resize) grep -rn -i -E 'corner|resizeHandle|HitZone|PanelHit|longPress|holdMs|HOLD_' $S | grep -v -i -E 'cornerRadius|round' | cut -c1-220 ;;
  launch) grep -rn -E 'setLaunchDisplayId|launchDisplayId|moveTaskToDisplay|onTaskMovedToDisplay|TaskStackListener|DEFAULT_DISPLAY|FLAG_ACTIVITY_NEW_TASK|startActivityAsUser|ActivityOptions' $S | cut -c1-220 ;;
  menu) grep -rn -i -E 'sidebar|Head tracking|headTrackingOn|MenuRow|menuItems' $S | cut -c1-220 ;;
  hold) grep -n -E 'Holding\(|LongPress|longPressMs|is Touching ->|holdAfter' core/src/main/kotlin/com/hayeco/flipspaces/core/spatial/SpatialPointer.kt | cut -c1-220 ;;
  docs) grep -n -i -E 'resize|window control|Move pill|^## |^### ' README.md CHANGELOG.md | head -60 ;;
  *) grep -rn -E "$1" $S | cut -c1-220 ;;
esac

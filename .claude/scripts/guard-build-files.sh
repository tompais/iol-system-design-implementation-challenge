#!/usr/bin/env bash
# Warns when Claude is about to edit build configuration files.
# Exits 0 (non-blocking) but prints a visible warning so the edit is intentional.

FILE_PATH=$(echo "${CLAUDE_TOOL_INPUT:-}" | python3 -c "import sys, json; d=json.load(sys.stdin); print(d.get('file_path', ''))" 2>/dev/null || echo "")

GUARDED_FILES=("build.gradle.kts" "gradle.properties" "settings.gradle.kts")

for guarded in "${GUARDED_FILES[@]}"; do
  if [[ "$FILE_PATH" == *"$guarded"* ]]; then
    echo "⚠️  BUILD FILE EDIT: Modifying $guarded affects the entire build."
    echo "   Verify this change is intentional and matches the implementation plan."
    break
  fi
done

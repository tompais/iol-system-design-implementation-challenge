#!/usr/bin/env bash
set -euo pipefail

FILE_PATH=$(python3 -c "
import json, sys
data = json.load(sys.stdin)
print(data.get('tool_input', {}).get('file_path', ''))
" <<< "${CLAUDE_TOOL_INPUT:-{}}")

[[ "$FILE_PATH" == *.kt ]] || exit 0

GRADLE_FILE="$(dirname "$0")/../../build.gradle.kts"
grep -q "jlleitschuh.gradle.ktlint" "$GRADLE_FILE" 2>/dev/null || exit 0

echo "[ktlint-format] formatting $FILE_PATH"
cd "$(dirname "$0")/../.." && ./gradlew ktlintFormat -q 2>&1 | tail -3

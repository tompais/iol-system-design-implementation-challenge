#!/usr/bin/env bash
# Blocks Spring/Jakarta imports from being written into core/ or infra/ packages.
# Runs as a PreToolUse hook before every Edit or Write on a Kotlin file.

set -euo pipefail

FILE=$(echo "${CLAUDE_TOOL_INPUT:-}" | python3 -c "import sys, json; d=json.load(sys.stdin); print(d.get('file_path', ''))" 2>/dev/null || echo "")

# Only guard Kotlin files in core/ or infra/
if ! echo "$FILE" | grep -qE "src/main/kotlin/com/iol/ratelimiter/(core|infra)/"; then
  exit 0
fi

# Check the *incoming content* (new_string for Edit, content for Write)
CONTENT=$(echo "${CLAUDE_TOOL_INPUT:-}" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print(d.get('new_string', '') or d.get('content', ''))
" 2>/dev/null || echo "")

if echo "$CONTENT" | grep -qE "import org\.springframework|import jakarta"; then
  echo "BLOCK: Spring/Jakarta import detected in core/ or infra/ — these packages must be framework-free."
  echo "  Move the import to adapter/ or wire it through RateLimiterConfig.kt."
  exit 2
fi

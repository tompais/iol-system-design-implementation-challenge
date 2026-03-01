#!/usr/bin/env bash
# Runs Detekt after editing a Kotlin source file.
# Only triggers on .kt files; skips if Detekt is not yet configured.

set -euo pipefail

PROJECT_DIR="/Users/tom.pais/IdeaProjects/iol-system-design-implementation-challenge/sd-implementation-challenge"
FILE_PATH=$(echo "${CLAUDE_TOOL_INPUT:-}" | python3 -c "import sys, json; d=json.load(sys.stdin); print(d.get('file_path', ''))" 2>/dev/null || echo "")

# Only run for Kotlin files
if [[ "$FILE_PATH" != *.kt ]]; then
  exit 0
fi

# Only run if Detekt is configured in the build
if ! grep -q "io.gitlab.arturbosch.detekt" "$PROJECT_DIR/build.gradle.kts" 2>/dev/null; then
  exit 0
fi

cd "$PROJECT_DIR"

echo "--- Detekt (on save) ---"
# detekt covers both main and test source sets — catches violations in test code too
./gradlew detekt --continue -q 2>&1 | tail -30

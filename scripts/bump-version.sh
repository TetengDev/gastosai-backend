#!/usr/bin/env bash
#
# Bumps the application version — pom.xml's <project><version> — and nothing else.
#
# This is the app version, not the API contract version (see CONTRACT.md and
# contract/package.json, bumped separately on a contract-v* tag). auto-release.yml already
# tags v<version> from pom.xml on every push to main, so this script's only job is to land
# the right number in pom.xml before that push happens.
#
# Usage:
#   scripts/bump-version.sh                  # dry-run: show the recommended bump
#   scripts/bump-version.sh --bump minor     # dry-run with an override
#   scripts/bump-version.sh --bump minor --apply   # write the new version to pom.xml
#   scripts/bump-version.sh --apply          # auto-detect, then write
#
# Bump type auto-detects from Conventional Commit messages since the last v* tag:
#   any "!" breaking marker or BREAKING_CHANGE  -> major
#   any feat:                                    -> minor
#   any fix: or perf:, otherwise                 -> patch
#   nothing since the last tag                   -> no bump recommended

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
POM="$ROOT/pom.xml"

BUMP=""
APPLY=0

while [ $# -gt 0 ]; do
  case "$1" in
    --bump)
      BUMP="$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]')"
      shift 2
      ;;
    --apply)
      APPLY=1
      shift
      ;;
    *)
      echo "Unknown argument: $1" >&2
      echo "Usage: $0 [--bump major|minor|patch] [--apply]" >&2
      exit 1
      ;;
  esac
done

if [ -n "$BUMP" ] && [ "$BUMP" != "major" ] && [ "$BUMP" != "minor" ] && [ "$BUMP" != "patch" ]; then
  echo "Invalid --bump value: $BUMP (expected major, minor, or patch)" >&2
  exit 1
fi

CURRENT="$("$ROOT/scripts/project-version.sh" "$POM")"

LAST_TAG="$(git -C "$ROOT" describe --tags --abbrev=0 --match 'v*' 2>/dev/null || true)"
if [ -n "$LAST_TAG" ]; then
  COMMITS="$(git -C "$ROOT" log "$LAST_TAG"..HEAD --oneline)"
else
  COMMITS="$(git -C "$ROOT" log --oneline)"
fi

REASON=""
if [ -z "$BUMP" ]; then
  if printf '%s\n' "$COMMITS" | grep -qE '!(\s|:)|BREAKING[_ ]CHANGE'; then
    BUMP="major"
    REASON="$(printf '%s\n' "$COMMITS" | grep -mE1 '!(\s|:)|BREAKING[_ ]CHANGE')"
  elif printf '%s\n' "$COMMITS" | grep -qE '^[a-f0-9]+ feat[:(]'; then
    BUMP="minor"
    REASON="$(printf '%s\n' "$COMMITS" | grep -mE1 '^[a-f0-9]+ feat[:(]')"
  elif printf '%s\n' "$COMMITS" | grep -qE '^[a-f0-9]+ (fix|perf)[:(]'; then
    BUMP="patch"
    REASON="$(printf '%s\n' "$COMMITS" | grep -mE1 '^[a-f0-9]+ (fix|perf)[:(]')"
  fi
fi

COMMIT_COUNT=0
if [ -n "$COMMITS" ]; then
  COMMIT_COUNT="$(printf '%s\n' "$COMMITS" | wc -l | tr -d ' ')"
fi

echo ""
echo "- Current version:    $CURRENT"
echo "- Latest tag:         ${LAST_TAG:-(none)}"
echo "- Commits since tag:  $COMMIT_COUNT"

if [ -z "$BUMP" ]; then
  echo "- Recommended bump:   none"
  echo "- Reason:             no feat:/fix:/perf: commits since last tag"
  echo ""
  echo "No version bump needed. Use --bump major|minor|patch to override."
  exit 0
fi

echo "- Recommended bump:   $BUMP"
echo "- Reason:             ${REASON:-explicit --bump}"

IFS='.' read -r MAJOR MINOR PATCH <<EOF
$CURRENT
EOF

case "$BUMP" in
  major) MAJOR=$((MAJOR + 1)); MINOR=0; PATCH=0 ;;
  minor) MINOR=$((MINOR + 1)); PATCH=0 ;;
  patch) PATCH=$((PATCH + 1)) ;;
esac

NEW_VERSION="$MAJOR.$MINOR.$PATCH"
echo "- Proposed version:   $NEW_VERSION"
echo ""

if [ "$APPLY" -eq 0 ]; then
  echo "(Dry-run. Use --apply to write pom.xml.)"
  exit 0
fi

python3 - "$POM" "$CURRENT" "$NEW_VERSION" <<'PY'
import sys

pom_path, current, new_version = sys.argv[1:4]

with open(pom_path, encoding="utf-8") as f:
    lines = f.readlines()

target = f"<version>{current}</version>\n"
replacement = f"<version>{new_version}</version>\n"

in_parent = False
done = False
for i, line in enumerate(lines):
    if "<parent>" in line:
        in_parent = True
    if "</parent>" in line:
        in_parent = False
        continue
    if in_parent:
        continue
    if line.strip() == target.strip():
        lines[i] = line.replace(target.strip(), replacement.strip())
        done = True
        break

if not done:
    sys.stderr.write(f"Could not find a top-level <version>{current}</version> to replace in {pom_path}\n")
    sys.exit(1)

with open(pom_path, "w", encoding="utf-8") as f:
    f.writelines(lines)
PY

echo "Updated pom.xml: $CURRENT -> $NEW_VERSION"
echo ""
echo "Next steps:"
echo "  git add pom.xml"
echo "  git commit -m \"chore: release v$NEW_VERSION\""
echo "  Merging to main tags v$NEW_VERSION automatically (see .github/workflows/auto-release.yml)."

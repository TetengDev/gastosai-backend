#!/usr/bin/env bash
#
# Prints this project's version — the <version> of the <project> element in pom.xml.
#
# Parsed as XML rather than grepped: pom.xml also contains the spring-boot-starter-parent
# version and a version for every pinned dependency, so a line-oriented match picks the wrong
# one. Used by the release, image-build, and PR-validation workflows so they cannot disagree
# about what "the version" is.

set -euo pipefail

POM="${1:-pom.xml}"

python3 - "$POM" <<'PY'
import sys
import xml.etree.ElementTree as ET

ns = {"m": "http://maven.apache.org/POM/4.0.0"}
root = ET.parse(sys.argv[1]).getroot()
node = root.find("m:version", ns)
if node is None or not (node.text or "").strip():
    sys.stderr.write("Could not read <project><version> from %s\n" % sys.argv[1])
    sys.exit(1)
print(node.text.strip())
PY

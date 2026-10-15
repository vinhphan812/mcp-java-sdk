#!/usr/bin/env python3
"""Verify published maven-metadata.xml contains the expected version (namespace-agnostic).

Supports:
  - No namespace (most common)
  - http://maven.apache.org/metadata/v2
  - http://maven.apache.org/METADATA/1.1.0
  - Any other variant

Uses ElementTree iter() to find <version> elements regardless of namespace.
Exits 0 if VERSION found; exits 1 if absent.
Malformed XML exits 2 (fail-closed).
"""

import sys
import xml.etree.ElementTree as ET

VERSION = sys.argv[1]
METADATA_FILE = sys.argv[2]

try:
    tree = ET.parse(METADATA_FILE)
except ET.ParseError as e:
    print(f"FATAL: malformed XML in {METADATA_FILE}: {e}")
    sys.exit(2)

root = tree.getroot()
versions = []
for elem in root.iter():
    tag = elem.tag
    if tag.endswith("}version"):
        tag = tag.split("}", 1)[1]
    elif tag == "version":
        pass
    else:
        continue
    if elem.text and elem.text.strip():
        versions.append(elem.text.strip())

if VERSION not in versions:
    print(f"ERROR: published Maven version {VERSION} not found in metadata.")
    sys.exit(1)
else:
    print(f"OK: published Maven version {VERSION} confirmed in metadata.")
    sys.exit(0)

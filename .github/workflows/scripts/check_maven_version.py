#!/usr/bin/env python3
"""Check maven-metadata.xml for a given version (namespace-agnostic).

Supports:
  - No namespace (most common)
  - http://maven.apache.org/metadata/v2
  - http://maven.apache.org/METADATA/1.1.0
  - Any other variant

Uses ElementTree iter() to find <version> elements regardless of namespace.
Exits 1 if VERSION found; exits 0 if absent.
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
# Find ALL <version> elements regardless of namespace.
# ElementTree.nsless_mapper or strip {uri} prefix approach:
# iter() walks every element; strip the namespace URI from the tag.
versions = []
for elem in root.iter():
    tag = elem.tag
    if tag.endswith("}version"):
        tag = tag.split("}", 1)[1]   # strip {uri}
    elif tag == "version":
        pass  # no namespace
    else:
        continue
    if elem.text and elem.text.strip():
        versions.append(elem.text.strip())

if VERSION in versions:
    print(f"ERROR: Maven version {VERSION} already exists in metadata.")
    sys.exit(1)
else:
    print(f"OK: Maven version {VERSION} is not present in metadata.")
    sys.exit(0)

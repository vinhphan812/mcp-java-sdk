#!/usr/bin/env python3
"""Check Maven metadata.xml for a given version using xml.etree (stdlib only)."""

import sys
import xml.etree.ElementTree as ET

VERSION = sys.argv[1]
METADATA_FILE = sys.argv[2]

tree = ET.parse(METADATA_FILE)
root = tree.getroot()
ns = {"m": "http://maven.apache.org/metadata/v2"}
versions = [v.text for v in root.findall(".//m:version", ns)]

if VERSION in versions:
    print(f"ERROR: Maven version {VERSION} already exists in metadata.")
    sys.exit(1)
else:
    print(f"OK: Maven version {VERSION} is not present in metadata.")
    sys.exit(0)

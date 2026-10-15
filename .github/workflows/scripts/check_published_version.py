#!/usr/bin/env python3
"""Verify published Maven metadata contains the expected version via xml.etree (stdlib only)."""

import sys
import xml.etree.ElementTree as ET

VERSION = sys.argv[1]
METADATA_FILE = sys.argv[2]

tree = ET.parse(METADATA_FILE)
root = tree.getroot()
ns = {"m": "http://maven.apache.org/metadata/v2"}
versions = [v.text for v in root.findall(".//m:version", ns)]

if VERSION not in versions:
    print(f"ERROR: published Maven version {VERSION} not found in metadata.")
    sys.exit(1)
else:
    print(f"OK: published Maven version {VERSION} confirmed in metadata.")
    sys.exit(0)

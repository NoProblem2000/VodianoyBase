#!/usr/bin/env python3
"""Prints a per-class test summary from surefire reports as a GitHub notice annotation."""
import glob
import sys
import xml.etree.ElementTree as ET

label = sys.argv[1] if len(sys.argv) > 1 else "tests"
lines = []
for path in sorted(glob.glob("target/surefire-reports/TEST-*.xml")):
    suite = ET.parse(path).getroot()
    name = suite.get("name", path).rsplit(".", 1)[-1]
    lines.append(f"{name}: tests={suite.get('tests')} failures={suite.get('failures')} "
                 f"errors={suite.get('errors')} skipped={suite.get('skipped')}")
print("\n".join(lines) or "no surefire reports")
if lines:
    print(f"::notice title=Tests ({label})::" + "%0A".join(lines))

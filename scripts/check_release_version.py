#!/usr/bin/env python3
"""发布门禁：App、服务端、后台和 tag 必须使用同一个对外版本号。"""
import json
import os
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
version = re.search(r"^version: ([0-9]+\.[0-9]+\.[0-9]+)\+", (root / "apps/ios/pubspec.yaml").read_text(), re.M).group(1)
server = ET.parse(root / "apps/server/pom.xml").getroot().find("{http://maven.apache.org/POM/4.0.0}version").text
admin = json.loads((root / "apps/admin-web/package.json").read_text())["version"]
assert server == admin == version, "App、服务端和后台版本不一致"
ref = os.environ.get("GITHUB_REF", "")
if ref.startswith("refs/tags/"):
    assert ref == "refs/tags/v" + version, "Release tag 与应用版本不一致"
print("统一版本检查通过：" + version)

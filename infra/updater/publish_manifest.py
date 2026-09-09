#!/usr/bin/env python3
"""CI 最后生成正式清单；只有已发布 digest 和有效版本才能进入检查源。"""
import json
import os
from pathlib import Path
from updater import validate_release

policy = json.loads(Path('infra/updater/release-policy.json').read_text())
manifest = validate_release(policy | {
    'version': os.environ['RELEASE_VERSION'],
    'revision': os.environ['GITHUB_SHA'],
    'image': os.environ['RELEASE_IMAGE'],
})
Path('release-assets').mkdir(exist_ok=True)
Path('release-assets/server-update.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')

#!/usr/bin/env python3
import json,pathlib,re,tomllib
root=pathlib.Path(__file__).resolve().parents[1]
r=json.loads((root/'release.json').read_text());v=r['version']
assert re.fullmatch(r'\d+\.\d+\.\d+',v), 'Invalid release version'
assert tomllib.loads((root/'apps/server/Cargo.toml').read_text())['package']['version']==v
assert json.loads((root/'package.json').read_text())['version']==v
gradle=(root/'apps/mobile/build.gradle.kts').read_text()
assert f'versionName = "{v}"' in gradle and f'versionCode = {r["android_version_code"]}' in gradle
for file in [*(root/'scripts/build').glob('*')]:
 if not file.is_file():continue
 for found in re.findall(r'(?:field-notes-|AppVersion=)(\d+\.\d+\.\d+)',file.read_text()):assert found==v,f'Stale artifact version in {file.name}'
print('Server, client, artifact and release versions agree.')

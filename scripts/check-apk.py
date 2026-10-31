#!/usr/bin/env python3
"""Inspect packaged native binaries and the real merged APK manifest."""
import os,pathlib,struct,subprocess,sys,tempfile,zipfile
apk=pathlib.Path(sys.argv[1]); tools=pathlib.Path(os.environ.get('FIELD_NOTES_TOOLS','/workspace/.cache/field-notes'))
aapt=tools/'android-sdk/build-tools/35.0.0/aapt'
manifest=subprocess.check_output([str(aapt),'dump','xmltree',str(apk),'AndroidManifest.xml'],text=True)
for forbidden in ['android.permission.BIND_VPN_SERVICE','VpnService','android.permission.ACCESS_FINE_LOCATION','com.google.android.gms','com.google.firebase','android.permission.READ_CALENDAR','android.permission.WRITE_CALENDAR']:
 assert forbidden not in manifest, f'Unexpected manifest entry: {forbidden}'
assert all(name in manifest for name in ['org.fieldnotes.app.PlannerWidget','org.fieldnotes.app.UpcomingWidget','org.fieldnotes.app.PrivateUpdatesService','org.fieldnotes.app.AlertReceiver','org.fieldnotes.app.StartupReceiver','android.permission.FOREGROUND_SERVICE_SPECIAL_USE','android.permission.SCHEDULE_EXACT_ALARM','android.appwidget.provider'])
assert 'org.fieldnotes.app.MainActivity' in manifest and 'org.fieldnotes.app.FieldNotesApplication' in manifest
assert 'usesCleartextTraffic' in manifest and 'dataExtractionRules' in manifest
with zipfile.ZipFile(apk) as archive:
 names=archive.namelist()
 native=[name for name in names if name.startswith('lib/') and name.endswith('.so')]
 assert 'lib/arm64-v8a/libfieldnotes.so' in native and 'lib/arm64-v8a/libfieldnotes_jni.so' in native
 assert not any('flutter' in n.lower() for n in names), 'Flutter artifacts remain'
 for name in native:
  raw=archive.read(name)
  assert raw[:4]==b'\x7fELF' and raw[4]==2, f'Expected ELF64: {name}'
  phoff=struct.unpack_from('<Q',raw,32)[0]; entsize,count=struct.unpack_from('<HH',raw,54)
  for i in range(count):
   offset=phoff+i*entsize
   if struct.unpack_from('<I',raw,offset)[0]==1:
    align=struct.unpack_from('<Q',raw,offset+48)[0]
    assert align>=16384, f'{name}: ELF segment not 16 KiB aligned'
print('APK manifest, native libraries, 16 KiB alignment and Flutter removal verified.')

#!/usr/bin/env python3
"""Verify migration twice on isolated legacy documents in real CouchDB."""
import contextlib, io, json, uuid
from pathlib import Path
import couch_admin as admin
admin.DB='lab2_migration_'+uuid.uuid4().hex
admin.request(admin.URL,'/'+admin.DB,'PUT')
old={'_id':'event:00000000-0000-0000-0000-000000000001','id':'00000000-0000-0000-0000-000000000001','kind':'event','title':'Legacy','type':'MEETING','startsAt':'2026-01-01T00:00:00Z','capacity':10,'createdAt':'2026-01-01T00:00:00Z'}
admin.request(admin.URL,'/'+admin.DB+'/event:00000000-0000-0000-0000-000000000001','PUT',old)
admin.upsert(admin.URL,'/'+admin.DB+'/_design/library',{'validate_doc_update':(admin.ROOT/'couchdb/validate.js').read_text()})
outputs=[]
for iteration in range(2):
    capture=io.StringIO()
    with contextlib.redirect_stdout(capture):admin.migrate()
    outputs.append(json.loads(capture.getvalue()))
    if iteration==0:first=admin.request(admin.URL,'/'+admin.DB+'/event:00000000-0000-0000-0000-000000000001')
last=admin.request(admin.URL,'/'+admin.DB+'/event:00000000-0000-0000-0000-000000000001')
assert outputs[0]['updated']==1 and outputs[1]['updated']==0
assert first==last and last['schemaVersion']==2
result={'database':admin.DB,'runs':outputs,'revision_after_first':first['_rev'],'revision_after_second':last['_rev'],'document':last}
Path('output/evidence').mkdir(parents=True,exist_ok=True)
Path('output/evidence/migration.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2))

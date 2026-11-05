#!/usr/bin/env python3
import json,uuid
from pathlib import Path
from urllib.parse import urlencode
from integration import req,APP
kind='pages-'+uuid.uuid4().hex
ids=[]
for i in range(3):
    e=req(APP,'/api/events','POST',{'title':str(i),'type':kind,'startsAt':'2026-12-01T00:00:00Z','capacity':10},201)
    ids.append(e['id'])
bookmark=None;actual=[]
for i in range(4):
    params={'type':kind,'limit':1,'sort':'asc'}
    if bookmark:params['bookmark']=bookmark
    page=req(APP,'/api/documents/events?'+urlencode(params))
    actual.extend(d['id'] for d in page['docs']);bookmark=page['bookmark']
assert actual==ids and len(set(actual))==3
reverse=req(APP,'/api/documents/events?'+urlencode({'type':kind,'limit':10,'sort':'desc'}))
assert [d['id'] for d in reverse['docs']]==list(reversed(ids))
result={'pages':4,'created_ids':ids,'page_ids':actual,'descending_ids':[d['id'] for d in reverse['docs']]}
Path('output/evidence/pagination.json').write_text(json.dumps(result,indent=2)+'\n')
print('Real Mango pagination and ascending/descending sort passed')

#!/usr/bin/env python3
"""Isolated, repeatable real CouchDB index comparison; keeps its small fixture DB."""
import json, os, uuid
from pathlib import Path
from couch_admin import request, URL, canonical
from datetime import datetime, timedelta, timezone
DB='lab2_explain_'+uuid.uuid4().hex
request(URL,'/'+DB,'PUT')
docs=[]
for i in range(500):
    at=canonical((datetime(2026,1,1,tzinfo=timezone.utc)+timedelta(hours=i)).isoformat())
    docs.append({'_id':f'event:{i:04d}','kind':'event','type':'MEETING' if i%5==0 else 'EXHIBITION','updatedAt':at})
results=request(URL,'/'+DB+'/_bulk_docs','POST',{'docs':docs})
assert all('error' not in row for row in results)
query={'selector':{'kind':'event','type':'MEETING','updatedAt':{'$gte':'2026-01-10T00:00:00.000000000Z'}},'limit':500,'execution_stats':True}
before=request(URL,'/'+DB+'/_explain','POST',query)
before_run=request(URL,'/'+DB+'/_find','POST',query)
request(URL,'/'+DB+'/_index','POST',{'index':{'fields':['kind','type','updatedAt','_id']},'ddoc':'event-search','name':'by-type-updated','type':'json'})
after=request(URL,'/'+DB+'/_explain','POST',query)
after_run=request(URL,'/'+DB+'/_find','POST',query)
assert {d['_id'] for d in before_run['docs']}=={d['_id'] for d in after_run['docs']}
assert after['index']['name']=='by-type-updated'
evidence={'database':DB,'count':500,'query':query,'before_explain':before,'after_explain':after,'before_stats':before_run['execution_stats'],'after_stats':after_run['execution_stats']}
Path('output/evidence').mkdir(parents=True,exist_ok=True)
Path('output/evidence/explain.json').write_text(json.dumps(evidence,indent=2)+'\n')
print(json.dumps(evidence,indent=2))

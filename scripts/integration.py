#!/usr/bin/env python3
"""Run against a bootstrapped local Compose stack; writes measured evidence."""
import base64, json, os, time, uuid
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from pathlib import Path
APP=os.getenv('APP_URL','http://localhost:18080')
DB=os.getenv('COUCH_URL','http://localhost:5984')
REPLICA=os.getenv('REPLICA_URL','http://localhost:5985')
AUTH=base64.b64encode(b'library:library').decode()
def req(base,path,method='GET',body=None,status=200):
    headers={'Content-Type':'application/json'}
    if base != APP: headers['Authorization']='Basic '+AUTH
    r=Request(base+path, data=None if body is None else json.dumps(body).encode(),headers=headers,method=method)
    try:
        with urlopen(r,timeout=15) as f: code=f.status; data=f.read()
    except HTTPError as e: code=e.code; data=e.read()
    assert code==status,(method,path,code,data.decode())
    return json.loads(data) if data else None

def run():
    evidence={}; tag='test-'+str(uuid.uuid4())
    e=req(APP,'/api/events','POST',{'title':tag,'type':tag,'startsAt':'2026-12-01T10:00:00Z','capacity':20},201)
    eid=e['id']; path='/api/events/'+eid
    doc=req(APP,path+'/document'); rev=doc['_rev']
    changed=req(APP,path,'PUT',{'rev':rev,'actor':'alice','state':'CLOSED','title':tag,'type':tag,'startsAt':e['startsAt'],'capacity':20})
    assert len(changed['history'])==2 and changed['lastModified']['by']=='alice'
    evidence['stale_revision']=req(APP,path,'PUT',{'rev':rev,'actor':'bob','state':'OPEN','title':'Lost write','type':tag,'startsAt':e['startsAt'],'capacity':20},409)
    query='/api/documents/events?type='+tag+'&actor=alice&limit=1'
    found=req(APP,query); assert found['docs'][0]['id']==eid
    evidence['explain']=req(APP,'/api/documents/events/explain?type='+tag)
    evidence['search']=found
    req(APP,'/api/managers/'+tag+'/settings','PUT',{'defaultQuantity':3})
    assert req(APP,'/api/managers/'+tag+'/settings')['defaultQuantity']==3
    req(APP,'/api/managers/'+tag+'/settings','PUT',{'defaultQuantity':4})
    assert req(APP,'/api/managers/'+tag+'/settings')['defaultQuantity']==4
    draft=req(APP,'/api/drafts','POST',{'eventId':eid,'managerId':tag,'quantity':2,'comment':'test','ttlSeconds':60},201)
    order=req(APP,'/api/drafts/'+draft['id']+'/submit','POST',status=201)
    assert req(APP,'/api/drafts/'+draft['id']+'/submit','POST',status=201)==order
    assert req(APP,'/api/orders/'+order['id'])==order
    assert req(APP,path+'/orders')['docs'][0]['id']==order['id']
    req(APP,path+'/views','POST'); assert req(APP,path+'/views')['views']==1
    evidence['actions']=req(APP,'/api/analytics/user-actions')
    assert any(row['key']==['alice','UPDATE'] and row['value']>=1 for row in evidence['actions']['rows'])
    invalid=json.loads(json.dumps(changed)); invalid['capacity']=0
    entry=dict(invalid['lastModified'],action='UPDATE')
    invalid['history'].append(entry); invalid['lastModified']=entry
    evidence['validation']=req(DB,'/library/event:'+eid,'PUT',invalid,403)
    deadline=time.monotonic()+40
    while True:
        try:
            copy=req(REPLICA,'/library/event:'+eid)
            if copy['_rev']==changed['_rev']: break
        except AssertionError: pass
        if time.monotonic()>deadline: raise AssertionError('Replication timed out')
        time.sleep(.5)
    evidence['replication']={'primary':changed['_rev'],'replica':copy['_rev']}
    short=req(APP,'/api/drafts','POST',{'eventId':eid,'managerId':tag,'quantity':1,'ttlSeconds':5},201)
    deadline=time.monotonic()+15
    while True:
        try: req(APP,'/api/drafts/'+short['id'],status=404); break
        except AssertionError:
            if time.monotonic()>deadline: raise
            time.sleep(.5)
    evidence['draft_ttl']='expired'
    delete=req(APP,path+'?rev='+changed['_rev']+'&actor=alice','DELETE')
    req(APP,path,status=404)
    assert delete['state']=='DELETED'
    evidence['result']='PASS'
    Path('output/evidence').mkdir(parents=True,exist_ok=True)
    Path('output/evidence/integration.json').write_text(json.dumps(evidence,indent=2,ensure_ascii=False)+'\n')
    print('Integration checks passed')
if __name__=='__main__': run()

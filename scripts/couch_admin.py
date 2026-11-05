#!/usr/bin/env python3
"""Idempotent bootstrap, schema migration and optional lab-1 JSON import."""
import argparse, base64, json, os, time
from datetime import datetime, timezone
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError
from urllib.parse import quote
ROOT=Path(__file__).resolve().parents[1]
URL=os.getenv('COUCH_URL','http://localhost:5984')
REPLICA=os.getenv('REPLICA_URL','http://localhost:5985')
USER=os.getenv('COUCH_USER','library'); PASSWORD=os.getenv('COUCH_PASSWORD','library')
DB=os.getenv('COUCH_DATABASE','library')

def request(base,path,method='GET',body=None,allowed=()):
    auth=base64.b64encode((USER+':'+PASSWORD).encode()).decode()
    r=Request(base+path,data=None if body is None else json.dumps(body).encode(),method=method,
              headers={'Content-Type':'application/json','Authorization':'Basic '+auth})
    try:
        with urlopen(r,timeout=20) as f: return json.load(f)
    except HTTPError as e:
        if e.code in allowed: return None
        raise RuntimeError(f'{method} {path}: HTTP {e.code} {e.read().decode()}') from e

def upsert(base,path,doc):
    old=request(base,path,allowed=(404,))
    if old:
        content={k:v for k,v in old.items() if k not in ('_rev','_id')}
        desired={k:v for k,v in doc.items() if k not in ('_rev','_id')}
        if content==desired: return
        doc=dict(doc,_rev=old['_rev'])
    request(base,path,'PUT',doc)

def setup():
    for base in (URL,REPLICA):
        deadline = time.monotonic() + 90
        while True:
            try:
                request(base, '/_up')
                break
            except (URLError, RuntimeError, TimeoutError):
                if time.monotonic() >= deadline: raise
                time.sleep(1)
        for db in ('_users','_replicator',DB): request(base,'/'+db,'PUT',allowed=(412,))
    design={'language':'javascript', 'validate_doc_update':(ROOT/'couchdb/validate.js').read_text(),
            'views':{'user_actions':{'map':(ROOT/'couchdb/user_actions.js').read_text(),'reduce':'_sum'}}}
    # Design propagates from primary, avoiding divergent design revisions on replica.
    upsert(URL,'/'+DB+'/_design/library',design)
    for name,fields in [('by-type-updated',['kind','type','updatedAt','_id']),('orders-by-event',['kind','eventId']),('orders-by-draft',['kind','draftId']),('by-kind',['kind'])]:
        request(URL,'/'+DB+'/_index','POST',{'index':{'fields':fields},'ddoc':'event-search' if name=='by-type-updated' else name,'name':name,'type':'json'})
    auth={'basic':{'username':USER,'password':PASSWORD}}
    replication={'source':{'url':os.getenv('REPLICATION_SOURCE','http://couch1:5984')+'/'+DB,'auth':auth},
                 'target':{'url':os.getenv('REPLICATION_TARGET','http://couch2:5984')+'/'+DB,'auth':auth},'continuous':True,'create_target':True}
    upsert(URL,'/_replicator/library-to-replica',replication)
    print(json.dumps({'setup':'complete','replication':'configured; verify scheduler and replica separately'}))

def canonical(value):
    dt=datetime.fromisoformat(value.replace('Z','+00:00')).astimezone(timezone.utc)
    return dt.strftime('%Y-%m-%dT%H:%M:%S.')+f'{dt.microsecond:06d}000Z'

def upgrade(doc):
    if doc.get('schemaVersion',1)>=2: return False
    at=canonical(doc.get('updatedAt',doc.get('createdAt','1970-01-01T00:00:00Z')))
    change={'by':'migration','action':'MIGRATE','state':doc.get('state','OPEN'),'at':at}
    doc.update(schemaVersion=2,updatedAt=at,state=change['state'],lastModified=change)
    doc.setdefault('history',[]).append(change)
    return True

def migrate():
    result={'updated':0,'skipped':0,'conflicts':0}
    start=None
    while True:
        path='/'+DB+'/_all_docs?include_docs=true&limit=200'
        if start is not None: path+='&startkey='+quote(json.dumps(start))+'&skip=1'
        rows=request(URL,path)['rows']
        for row in rows:
            doc=row.get('doc',{})
            if doc.get('kind') not in ('event','order','settings'): continue
            if not upgrade(doc): result['skipped']+=1; continue
            response=request(URL,'/'+DB+'/'+quote(doc['_id'],safe=''),'PUT',doc,allowed=(409,))
            result['updated' if response else 'conflicts']+=1
        if len(rows)<200: break
        start=rows[-1]['id']
    print(json.dumps(result))
    if result['conflicts']: raise SystemExit('Concurrent changes detected; rerun migration')

def import_json(path):
    """Input: {events: [...], orders: [...], settings: [...]} using lab-1 API fields."""
    data=json.loads(Path(path).read_text()); result={'created':0,'existing':0}
    for collection,kind in [('events','event'),('orders','order'),('settings','settings')]:
        for value in data.get(collection,[]):
            doc=dict(value); doc['kind']=kind
            for field in ('createdAt','startsAt'):
                if field in doc: doc[field]=canonical(doc[field])
            doc['_id']=kind+':'+str(doc['managerId'] if kind=='settings' else doc['id'])
            upgrade(doc)
            if request(URL,'/'+DB+'/'+quote(doc['_id'],safe=''),'PUT',doc,allowed=(409,)):
                result['created']+=1
            else: result['existing']+=1
    print(json.dumps(result))

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('command',choices=['setup','migrate','import']);parser.add_argument('file',nargs='?')
    args=parser.parse_args()
    if args.command=='setup': setup()
    elif args.command=='migrate': migrate()
    elif args.file: import_json(args.file)
    else: parser.error('import requires a JSON file')

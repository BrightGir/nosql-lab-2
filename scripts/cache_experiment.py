#!/usr/bin/env python3
"""Observe real Etcd-backed settings cache, service invalidation and lease expiry."""
import json,time,uuid
from pathlib import Path
from datetime import datetime,timezone
from integration import req,APP,DB
from couch_admin import canonical
manager='cache-'+uuid.uuid4().hex;path='/api/managers/'+manager+'/settings'
req(APP,path,'PUT',{'defaultQuantity':3}); assert req(APP,path)['defaultQuantity']==3
req(APP,path,'PUT',{'defaultQuantity':4}); assert req(APP,path)['defaultQuantity']==4
# A direct database write intentionally bypasses application invalidation.
doc=req(DB,'/library/settings:'+manager)
at=canonical(datetime.now(timezone.utc).isoformat())
entry={'by':manager,'action':'UPDATE','state':'OPEN','at':at}
doc.update(defaultQuantity=7,updatedAt=at,lastModified=entry);doc['history'].append(entry)
req(DB,'/library/settings:'+manager,'PUT',doc,201)
assert req(APP,path)['defaultQuantity']==4
start=time.monotonic()
while req(APP,path)['defaultQuantity']!=7:
    if time.monotonic()-start>80:raise AssertionError('TTL did not expire in 80 seconds')
    time.sleep(.5)
result={'service_update_invalidated':True,'cached_after_direct_write':4,'loaded_after_ttl':7,'observed_wait_seconds':round(time.monotonic()-start,2)}
Path('output/evidence/cache.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result))

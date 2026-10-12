#!/usr/bin/env python3
"""List compiled member references newer than --min-api. Review guards/backports manually.
This is an inventory, not an Android lint/control-flow proof. Compile the exact candidate first.
"""
import argparse, os
parser=argparse.ArgumentParser()
parser.add_argument('--classes',default='build/classes')
parser.add_argument('--sdk',default=os.environ.get('ANDROID_HOME','/workspace/shared/device_bridge_toolchain/android-sdk'))
parser.add_argument('--min-api',type=int,default=19)
args=parser.parse_args()
import struct, pathlib, xml.etree.ElementTree as ET
api={c.get('name'):c for c in ET.parse(args.sdk+'/platforms/android-35/data/api-versions.xml').getroot().findall('class')}
parents={}
allrefs=[]
for f in pathlib.Path(args.classes).rglob('*.class'):
 d=f.read_bytes();o=8
 def u2():
  global o
  n=int.from_bytes(d[o:o+2],'big');o+=2;return n
 cp=[None];count=u2();i=1
 while i<count:
  t=d[o];o+=1
  if t==1:n=u2();cp.append(d[o:o+n].decode('utf-8','replace'));o+=n
  elif t in [7,8,16,19,20]:cp.append((t,u2()))
  elif t in [9,10,11,12,18,17]:cp.append((t,u2(),u2()))
  elif t in [3,4]:o+=4;cp.append(None)
  elif t in [5,6]:o+=8;cp.extend([None,None]);i+=1
  elif t==15:o+=3;cp.append(None)
  else:raise Exception(t)
  i+=1
 u2();cl=cp[cp[u2()][1]];su=u2();parents[cl]=[cp[cp[su][1]][:] ] if su else []
 for _ in range(u2()):parents[cl].append(cp[cp[u2()][1]])
 for ref in cp:
  if isinstance(ref,tuple) and ref[0] in [9,10,11]:
   own=cp[cp[ref[1]][1]];nt=cp[ref[2]];name=cp[nt[1]];desc=cp[nt[2]];allrefs.append((cl,own,name,desc,ref[0]))
def check(own,name,desc,kind,seen):
 if own in seen:return None
 seen.add(own);c=api.get(own)
 if c is not None:
  since=int(c.get('since','1'));key=name if kind==9 else name+desc
  for x in c.findall('field' if kind==9 else 'method'):
   if x.get('name')==key:return max(since,int(x.get('since','1')))
  candidates=[check(x.get('name'),name,desc,kind,seen.copy()) for x in list(c.findall('extends'))+list(c.findall('implements'))]
  candidates=[v for v in candidates if v is not None]
  return max(since,min(candidates)) if candidates else None
 candidates=[check(p,name,desc,kind,seen.copy()) for p in parents.get(own,[])]
 candidates=[v for v in candidates if v is not None]
 return min(candidates) if candidates else None
for ref in sorted(set(allrefs)):
 v=check(*ref[1:],set())
 if v is not None and v>args.min_api:print(v,ref[0].rsplit('/',1)[-1],ref[1]+'.'+ref[2]+ref[3])

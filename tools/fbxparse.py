import struct, zlib, sys
class Node:
    __slots__=('name','props','children')
    def __init__(s,n,p,c): s.name=n; s.props=p; s.children=c
    def find(s,name): return [c for c in s.children if c.name==name]
    def first(s,name):
        for c in s.children:
            if c.name==name: return c
        return None
def read_prop(d,o):
    t=chr(d[o]); o+=1
    if t=='Y': return struct.unpack_from('<h',d,o)[0],o+2
    if t=='C': return d[o]!=0,o+1
    if t=='I': return struct.unpack_from('<i',d,o)[0],o+4
    if t=='F': return struct.unpack_from('<f',d,o)[0],o+4
    if t=='D': return struct.unpack_from('<d',d,o)[0],o+8
    if t=='L': return struct.unpack_from('<q',d,o)[0],o+8
    if t in 'SR':
        n=struct.unpack_from('<I',d,o)[0]; o+=4; v=bytes(d[o:o+n]); o+=n
        return (v.decode('utf8','replace') if t=='S' else v),o
    if t in 'fdlib':
        n,enc,cl=struct.unpack_from('<III',d,o); o+=12
        raw=bytes(d[o:o+cl]); o+=cl
        if enc==1: raw=zlib.decompress(raw)
        import numpy as np
        dt={'f':'<f4','d':'<f8','l':'<i8','i':'<i4','b':'u1'}[t]
        return np.frombuffer(raw,dtype=dt).copy(),o
    raise ValueError('bad prop type %r at %d'%(t,o))
def read_node(d,o,v64):
    if v64:
        end,np_,pl=struct.unpack_from('<QQQ',d,o); o+=24
    else:
        end,np_,pl=struct.unpack_from('<III',d,o); o+=12
    nl=d[o]; o+=1
    if end==0: return None,o
    name=bytes(d[o:o+nl]).decode(); o+=nl
    props=[]
    for _ in range(np_):
        p,o=read_prop(d,o); props.append(p)
    ch=[]
    sentinel=25 if v64 else 13
    while o<end:
        if end-o==sentinel: o=end; break
        c,o=read_node(d,o,v64)
        if c is None: break
        ch.append(c)
    return Node(name,props,ch),end
def load(path):
    d=open(path,'rb').read()
    assert d[:20]==b'Kaydara FBX Binary  ', d[:20]
    ver=struct.unpack_from('<I',d,23)[0]
    v64=ver>=7500
    o=27; top=[]
    while o<len(d):
        n,o=read_node(d,o,v64)
        if n is None: break
        top.append(n)
    return ver,Node('root',[],top)

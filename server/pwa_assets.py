"""Small dependency-free PWA PNG icon generator."""
import struct
import zlib

def chunk(kind, data):
    return struct.pack(">I",len(data))+kind+data+struct.pack(">I",zlib.crc32(kind+data)&0xffffffff)

def icon_png(size):
    if size not in (180,192,512):
        raise ValueError("unsupported size")
    bg=(11,13,15)
    fg=(237,240,236)
    lime=(183,243,77)
    center=size/2
    outer=size*.28
    inner=size*.17
    rows=[]
    for y in range(size):
        row=bytearray([0])
        for x in range(size):
            dx=x-center
            dy=y-center
            d=dx*dx+dy*dy
            ring=inner*inner<=d<=outer*outer
            dot=(dx-outer*.72)**2+(dy+outer*.72)**2<=(size*.05)**2
            row.extend(lime if dot else fg if ring else bg)
        rows.append(bytes(row))
    ihdr=struct.pack(">IIBBBBB",size,size,8,2,0,0,0)
    return bytes([137,80,78,71,13,10,26,10])+chunk(b"IHDR",ihdr)+chunk(b"IDAT",zlib.compress(b"".join(rows),8))+chunk(b"IEND",b"")

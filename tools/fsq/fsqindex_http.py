"""Find which Foursquare OS Places files/row groups cover a bounding box, reading only parquet footers over HTTP."""
import sys, json, urllib.request, re, io
import pyarrow.parquet as pq
BASE = "https://data.source.coop/fused/"
  # e.g. fsq-os-places/2025-02-06/places/


class HttpFile(io.RawIOBase):
    def __init__(self, url):
        self.url = url; self.pos = 0
        r = urllib.request.urlopen(urllib.request.Request(url, method="HEAD")); self.size = int(r.headers["Content-Length"])
    def seekable(self): return True
    def readable(self): return True
    def tell(self): return self.pos
    def seek(self, off, whence=0):
        self.pos = off if whence == 0 else self.pos + off if whence == 1 else self.size + off; return self.pos
    def read(self, n=-1):
        if n < 0: n = self.size - self.pos
        if n == 0 or self.pos >= self.size: return b""
        end = min(self.size, self.pos + n) - 1
        d = urllib.request.urlopen(urllib.request.Request(self.url, headers={"Range": f"bytes={self.pos}-{end}"})).read()
        self.pos += len(d); return d
    def readinto(self, b):
        d = self.read(len(b)); b[:len(d)] = d; return len(d)


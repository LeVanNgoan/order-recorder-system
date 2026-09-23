#!/usr/bin/env python3
from pathlib import Path
import shutil
ROOT=Path(__file__).resolve().parents[1]
out=ROOT/'dist'; out.mkdir(exist_ok=True)
for rel,name in [('apps/hub','OrderRecorder-Hub-v2.2.5-MVC2-src'),('apps/shopeefood-agent','OrderRecorder-SPF-v2.0.10-MVC2-src'),('apps/grabfood-extension','GrabOrderRecorder-v2.0.1-MVC2-src')]:
    shutil.make_archive(str(out/name),'zip',ROOT/rel)
print(out)

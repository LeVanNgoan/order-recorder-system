#!/usr/bin/env python3
from pathlib import Path
import subprocess, sys, json, os, tempfile
ROOT=Path(__file__).resolve().parents[1]

def run(cmd,cwd=None,env=None):
    print('>', ' '.join(map(str,cmd)))
    subprocess.run(cmd,cwd=cwd,env=env,check=True)

# Hub regression tests, each in a fresh process so temp HOME config isolation works.
hub=ROOT/'apps/hub'
env=os.environ.copy(); env['PYTHONPATH']=str(hub)
for p in sorted((hub/'tests/regression').glob('test_*.py')):
    if p.name == 'test_discovery.py':
        print('SKIP test_discovery.py (requires live UDP Hub on LAN)')
        continue
    test_env=env.copy()
    test_root=tempfile.mkdtemp(prefix=f'orderrecorder-qa-{p.stem}-')
    test_env['HOME']=test_root
    test_env['LOCALAPPDATA']=test_root
    test_env['APPDATA']=test_root
    run([sys.executable,str(p)],cwd=hub,env=test_env)

adapter_env=env.copy()
adapter_root=tempfile.mkdtemp(prefix='orderrecorder-qa-mvc2-')
adapter_env['HOME']=adapter_root
adapter_env['LOCALAPPDATA']=adapter_root
adapter_env['APPDATA']=adapter_root
run([sys.executable,'tests/test_mvc2_adapters.py'],cwd=hub,env=adapter_env)

# SPF stable-source verification.
run([sys.executable,'verify_source_v2.0.10.py'],cwd=ROOT/'apps/shopeefood-agent')

# Grab paths + JS parser.
grab=ROOT/'apps/grabfood-extension'
m=json.loads((grab/'manifest.json').read_text(encoding='utf-8'))
refs=[m['background']['service_worker'],m['action']['default_popup'],*m['content_scripts'][0]['js'],*m['icons'].values(),*m['action']['default_icon'].values()]
for ref in refs:
    if not (grab/ref).exists(): raise SystemExit(f'Missing manifest ref: {ref}')
for p in [grab/'service-worker.js',grab/'src/controller/service-worker-controller.js',grab/'src/controller/grab-monitor.js',grab/'src/view/popup/popup.js',grab/'src/view/viewer/viewer.js',grab/'src/service/xlsx-builder.js']:
    run(['node','--check',str(p)])
print('ALL_MVC2_QA_PASS')

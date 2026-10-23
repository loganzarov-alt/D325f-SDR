#!/usr/bin/env python3
"""Build the offline notice page from the reviewed license inventory."""
from pathlib import Path
import html,json
r=Path(__file__).resolve().parents[1]
out=r/'app/src/main/assets/licenses';out.mkdir(parents=True,exist_ok=True)
modules=json.loads((r/'docs/runtime-dependencies.json').read_text())
parts=['<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>D325f SDR licenses</title><style>body{background:#101b2c;color:#edf5fa;font:15px sans-serif;padding:16px;line-height:1.5}pre{white-space:pre-wrap;overflow-wrap:anywhere;font:12px monospace}h1,h2{color:#43e5c4}li{overflow-wrap:anywhere}</style><h1>D325f SDR</h1><p>Project modifications: Logan Zarov, 2026. RF Analyzer portions: Dennis Mantz and contributors. Combined application: GPL-3.0-or-later. Upstream GPL-2.0-or-later notices remain in the source. No warranty.</p><h2>Resolved runtime dependencies</h2><ul>']
for m in modules:
 assert m['licenses'],m['coordinate']
 parts.append('<li>'+html.escape(m['coordinate']+' — '+', '.join(x['name'] for x in m['licenses']))+'</li>')
parts.append('</ul>')
for p in [r/'LICENSE',*sorted((r/'LICENSES').rglob('*'))]:
 if p.is_file():parts.append('<h2>'+html.escape(str(p.relative_to(r)))+'</h2><pre>'+html.escape(p.read_text())+'</pre>')
parts.append('</html>');(out/'NOTICE.html').write_text('\n'.join(parts)+'\n')
print('Generated offline notice page:',len(modules),'dependency records')

#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Logan Zarov
"""Generate resource catalogs and offline manuals; fail on missing translations/placeholders."""
from pathlib import Path
import re,json,html,xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[2]; base=Path(__file__).parent
rows=[line.split('\t') for name in ['messages.tsv','help.tsv'] for line in (base/name).read_text().splitlines()]
locales=['en','ru','es','pt','fr','de','it','zh-Hans','zh-Hant','ja','ko','ar','hi']
folders={'en':['values'],'zh-Hans':['values-zh-rCN','values-b+zh+Hans'],'zh-Hant':['values-zh-rTW','values-b+zh+Hant']}
all_values={}
for lang in locales:
 values=[r[1 if lang=='en' else 2] for r in rows] if lang in ('en','ru') else (base/(lang+'.txt')).read_text().splitlines()+(base/('help-'+lang+'.txt')).read_text().splitlines()
 assert len(values)==len(rows),(lang,len(values),len(rows))
 for row,v in zip(rows,values):assert sorted(re.findall(r'%\d+\$s',row[1]))==sorted(re.findall(r'%\d+\$s',v)),(lang,row[0])
 xml='<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
 for row,v in zip(rows,values):xml+='    <string name="l10n_'+row[0]+'">'+html.escape(v,quote=False).replace("'", "\\'").replace('"','\\"')+'</string>\n'
 xml+='</resources>\n'
 for folder in folders.get(lang,['values-'+lang]):
  dest=root/'app/src/main/res'/folder/'completion.xml';dest.parent.mkdir(exist_ok=True);dest.write_text(xml)
 all_values[lang]={row[0]:v for row,v in zip(rows,values)}
code='// SPDX-License-Identifier: GPL-3.0-or-later\n// Copyright (C) 2026 Logan Zarov\npackage com.mantz_it.rfanalyzer.automation\nimport com.mantz_it.rfanalyzer.R\nobject ExtraTranslations {\n    val ids = mapOf(\n'
for key,en,ru in rows:
 if '%' not in en:
  for s in dict.fromkeys([en,ru]):code+='        '+json.dumps(s,ensure_ascii=False)+' to R.string.l10n_'+key+',\n'
code+='    )\n}\n'
(root/'app/src/main/java/com/mantz_it/rfanalyzer/automation/ExtraTranslations.kt').write_text(code)
# Reuse translated UI and feature descriptions in the manual to prevent stale duplicate text.
pages={
'index.html':('app_name',['l10n_intro','l10n_about_desc']),
'sdr-source.html':('ui_7',['l10n_intro','l10n_formats','l10n_auto_help','l10n_bt_limit_help','l10n_bt_formula','v2_invert','v2_stream_info','net_help','l10n_rate_select','l10n_bands']),
'demodulation.html':('ui_1',['l10n_about_desc','l10n_squelch_help','l10n_center_help','l10n_frozen']),
'fft.html':('ui_3',['l10n_average_help','l10n_peak_help','l10n_relative_help','l10n_dc_help','l10n_bands']),
'recording.html':('ui_2',['l10n_bands','l10n_record_squelch_help','l10n_formats','v2_task_settings','v2_thermal_info','v2_background']),
'settings.html':('ui_4',['l10n_context_help','l10n_wheel_help','l10n_focus_clean','l10n_focus_freeze','l10n_focus_off','l10n_focus_restore','v2_bt_warning','l10n_bt_limit_help'])}
for lang in locales:
 strings={}
 for folder in ['values']+folders.get(lang,['values-'+lang]):
  for p in (root/'app/src/main/res'/folder).glob('*.xml'):
   for e in ET.parse(p).getroot().findall('string'):strings[e.attrib['name']]=''.join(e.itertext()).replace("\\'", "'").replace('\\"','"')
 def txt(k):return html.escape(strings[k])
 for name,(title,keys) in pages.items():
  old=(root/'app/src/main/assets/docs'/name).read_text()
  anchors=''.join('<span id="'+a+'"></span>' for a in re.findall(r'<span id="([^"]+)"',old))
  nav=' | '.join('<a href="'+n+'">'+txt(t)+'</a>' for n,(t,_) in pages.items())
  document='<!doctype html><html lang="'+lang+'" dir="'+('rtl' if lang=='ar' else 'ltr')+'"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>'+txt(title)+'</title><style>body{background:#101b2c;color:#edf5fa;font:17px sans-serif;line-height:1.6;padding:20px}a,h1{color:#43e5c4}</style><nav>'+nav+'</nav><h1>'+txt(title)+'</h1>'+anchors+''.join('<p>'+txt(k)+'</p>' for k in keys)+'<p><a href="../../licenses/NOTICE.html">'+txt('l10n_licenses_offline')+'</a></p></html>\n'
  dest=root/'app/src/main/assets/docs'/lang/name;dest.parent.mkdir(exist_ok=True);dest.write_text(document)
print(f'Generated {len(rows)} messages in {len(locales)} languages and {len(pages)*len(locales)} manual pages')

# Keep the default English asset entry points current for direct links.
for name in pages:
    folder=root/'app/src/main/assets/docs'
    (folder/name).write_text((folder/'en'/name).read_text().replace('../../licenses/', '../licenses/'))

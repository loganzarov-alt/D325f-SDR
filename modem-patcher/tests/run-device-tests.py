#!/usr/bin/env python3
"""Read-only real partition test; native writes are limited to a regular fixture."""
from pathlib import Path
import json,os,shlex,subprocess,time
ROOT=Path(__file__).resolve().parent.parent
SERIAL=os.environ.get('ANDROID_SERIAL')
ADB=['adb']+(['-s',SERIAL] if SERIAL else [])
PKG='io.github.loganzarov_alt.d325fmodem'
def shell(text):return subprocess.check_output(ADB+['shell','su -c '+shlex.quote(text)],text=True,timeout=10)
def temps():
    values=[int(x) for x in shell('cat /sys/class/thermal/thermal_zone0/temp /sys/class/thermal/thermal_zone1/temp').split()]
    if len(values)!=2:raise RuntimeError('CPU sensors unavailable')
    return [x//1000 if x>1000 else x for x in values]
start_temp=temps()
if max(start_temp)>=48:raise SystemExit('Cool LG below 48 C before testing')
maximum=max(start_temp);readings=[];start=time.monotonic();stopped=False
output=ROOT/'dist/device-regression.txt'
with output.open('w') as log:
    process=subprocess.Popen(ADB+['shell','am','instrument','-w','-r',PKG+'.tests/'+PKG+'.CacheRegression'],stdout=log,stderr=subprocess.STDOUT,text=True)
    try:
        while process.poll() is None:
            current=temps();maximum=max(maximum,*current);readings.append({'seconds':round(time.monotonic()-start,1),'cpu_c':current})
            if max(current)>=58 or time.monotonic()-start>300:
                stopped=True;shell('killall -KILL modem-toggle 2>/dev/null; am force-stop '+PKG);process.terminate();break
            time.sleep(1)
        process.wait(timeout=10)
    finally:
        if process.poll() is None:process.kill()
text=output.read_text();passed=not stopped and 'REAL_MODEM_WRITES 0' in text and 'INSTRUMENTATION_CODE: -1' in text and '\nFAIL' not in text
report={'passed':passed,'real_modem_writes':0,'max_cpu_c':maximum,'thermal_abort_c':58,'start_cpu_c':start_temp,'duration_seconds':round(time.monotonic()-start,1),'readings':readings}
(ROOT/'dist/device-test-report.json').write_text(json.dumps(report,indent=2)+'\n')
print(text,flush=True);print(json.dumps({k:v for k,v in report.items() if k!='readings'},indent=2),flush=True)
if not passed:raise SystemExit(1)

"""Released-JAR compatibility fixture. No plugin source build; stdlib only."""
import collections
import csv
import hashlib
import http.server
import json
import math
import platform
import re
import statistics
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

OUT = Path('evidence').resolve()

def prepare():
    OUT.mkdir(exist_ok=True)
    reports = OUT / 'reports'
    reports.mkdir(exist_ok=True)
    root = ET.Element('jmeterTestPlan', version='1.2', properties='5.0', jmeter='5.6.3')
    outer = ET.SubElement(root, 'hashTree')
    plan = ET.SubElement(outer, 'TestPlan', guiclass='TestPlanGui', testclass='TestPlan', testname='macOS official release validation', enabled='true')
    def prop(parent, name, value, tag='stringProp'):
        ET.SubElement(parent, tag, name=name).text = str(value)
    prop(plan, 'TestPlan.functional_mode', 'false', 'boolProp')
    prop(plan, 'TestPlan.serialize_threadgroups', 'false', 'boolProp')
    args = ET.SubElement(plan, 'elementProp', name='TestPlan.user_defined_variables', elementType='Arguments')
    ET.SubElement(args, 'collectionProp', name='Arguments.arguments')
    tree = ET.SubElement(outer, 'hashTree')
    listener = ET.SubElement(tree, 'io.phaseanalyzer.PhaseAnalyzerListener', guiclass='io.phaseanalyzer.PhaseAnalyzerGui', testclass='io.phaseanalyzer.PhaseAnalyzerListener', testname='Official released listener', enabled='true')
    for k, v in {'phase.id':'macos-released-100-validation', 'phase.group':'', 'phase.mode':'Requests', 'phase.output':str(reports), 'phase.autoExportFormat':'html'}.items(): prop(listener, k, v)
    prop(listener, 'phase.automatic', 'true', 'boolProp')
    prop(listener, 'phase.autoExport', 'true', 'boolProp')
    ET.SubElement(tree, 'hashTree')
    group = ET.SubElement(tree, 'ThreadGroup', guiclass='ThreadGroupGui', testclass='ThreadGroup', testname='macOS Standard HTTP', enabled='true')
    prop(group, 'ThreadGroup.on_sample_error', 'continue')
    loop = ET.SubElement(group, 'elementProp', name='ThreadGroup.main_controller', elementType='LoopController', guiclass='LoopControlPanel', testclass='LoopController', testname='Loop')
    prop(loop, 'LoopController.continue_forever', 'true', 'boolProp')
    prop(loop, 'LoopController.loops', '-1', 'intProp')
    for k, v in {'ThreadGroup.num_threads':'2', 'ThreadGroup.ramp_time':'5', 'ThreadGroup.duration':'40', 'ThreadGroup.delay':'0'}.items(): prop(group, k, v)
    prop(group, 'ThreadGroup.scheduler', 'true', 'boolProp')
    workloads = ET.SubElement(tree, 'hashTree')
    timer = ET.SubElement(workloads, 'ConstantTimer', guiclass='ConstantTimerGui', testclass='ConstantTimer', testname='Local pacing 100ms', enabled='true')
    prop(timer, 'ConstantTimer.delay', '100')
    ET.SubElement(workloads, 'hashTree')
    for label, path in [('Local HTTP Success', '/ok'), ('Local HTTP Intentional Failure', '/fail')]:
        sampler = ET.SubElement(workloads, 'HTTPSamplerProxy', guiclass='HttpTestSampleGui', testclass='HTTPSamplerProxy', testname=label, enabled='true')
        a = ET.SubElement(sampler, 'elementProp', name='HTTPsampler.Arguments', elementType='Arguments')
        ET.SubElement(a, 'collectionProp', name='Arguments.arguments')
        for k,v in {'HTTPSampler.domain':'127.0.0.1','HTTPSampler.port':'18911','HTTPSampler.protocol':'http','HTTPSampler.path':path,'HTTPSampler.method':'GET','HTTPSampler.connect_timeout':'3000','HTTPSampler.response_timeout':'3000'}.items(): prop(sampler,k,v)
        prop(sampler, 'HTTPSampler.use_keepalive', 'true', 'boolProp')
        ET.SubElement(workloads, 'hashTree')
    ET.ElementTree(root).write(OUT/'macOS_Release_Validation.jmx', encoding='utf-8', xml_declaration=True)

def serve():
    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'
        def do_GET(self):
            time.sleep(.02)
            failed = self.path == '/fail'
            body = json.dumps({'environment':'local mock plugin validation','intentionalFailure':failed}).encode()
            self.send_response(503 if failed else 200)
            self.send_header('Content-Type','application/json')
            self.send_header('Content-Length',str(len(body)))
            self.end_headers()
            self.wfile.write(body)
    http.server.ThreadingHTTPServer(('127.0.0.1',18911),Handler).serve_forever()

def validate():
    checks = []
    def check(scope, expected, actual):
        ok = math.isclose(expected, actual, rel_tol=1e-8, abs_tol=1e-8) if isinstance(expected,(int,float)) and isinstance(actual,(int,float)) else expected == actual
        checks.append({'scope':scope,'expected':expected,'actual':actual,'pass':ok})
    def metrics(rows, seconds):
        values=sorted(int(x['elapsed']) for x in rows);n=len(values);failed=sum(x['success']!='true' for x in rows)
        percentile=lambda p:values[math.ceil(p*n)-1] if n else None
        return dict(samples=n, successful=n-failed, failed=failed, averageMs=statistics.fmean(values) if n else None, medianMs=percentile(.5),p90Ms=percentile(.9),p95Ms=percentile(.95),p99Ms=percentile(.99),minMs=min(values) if n else None,maxMs=max(values) if n else None,stddevMs=statistics.pstdev(values) if n else None,errorPercent=100*failed/n if n else None,samplesPerSecond=n/seconds if seconds else None,receivedKBPerSecond=sum(int(x['bytes']) for x in rows)/1024/seconds if seconds else None,sentKBPerSecond=sum(int(x['sentBytes']) for x in rows)/1024/seconds if seconds else None)
    summary={'platform':platform.platform(),'processor':platform.machine(),'gui':'NOT TESTED — headless runner','installation':'FAIL','loading':'FAIL','functional':'FAIL','html':'NOT TESTED','excel':'NOT TESTED','checks':checks}
    try:
        check('macOS host','Darwin',platform.system())
        data=Path('downloads/jmeter-performance-phase-analyzer-1.0.0.jar').read_bytes()
        expected=Path('downloads/SHA256SUMS.txt').read_text().split()[0]
        check('Release checksum',expected,hashlib.sha256(data).hexdigest())
        summary['installation']='PASS' if checks[-1]['pass'] else 'FAIL'
        check('Listener loaded from release',True,'PhaseAnalyzerListener loaded from ' in (OUT/'plugin-loading.txt').read_text())
        summary['loading']='PASS' if checks[-1]['pass'] else 'FAIL'
        htmls=list((OUT/'reports').glob('*.html'));check('One HTML',1,len(htmls))
        text=htmls[0].read_text();snap=json.JSONDecoder().raw_decode(text.split('const R=',1)[1])[0]
        check('HTML sections',9,len(re.findall(r'<section ',text)))
        rows=list(csv.DictReader((OUT/'results.jtl').open(newline='',encoding='utf-8-sig')))
        check('Has actual samples',True,len(rows)>100)
        codes=dict(collections.Counter(r['responseCode'] for r in rows))
        check('HTTP codes',['200','503'],sorted(codes))
        check('Native success flags',True,all((r['responseCode']=='200')==(r['success']=='true') for r in rows))
        check('Two actual sampler labels',['Local HTTP Intentional Failure','Local HTTP Success'],sorted({r['label'] for r in rows}))
        check('Dynamic JMX name','macOS_Release_Validation.jmx',snap['metadata']['jmxFilename'])
        check('Standard ramp-up',5.,next(p for p in snap['phases'] if p['name']=='Ramp-up')['configuredSeconds'])
        check('Standard steady',35.,next(p for p in snap['phases'] if p['name']=='Steady load')['configuredSeconds'])
        check('Native ramp-down absent',None,next(p for p in snap['phases'] if p['name']=='Ramp-down')['configuredSeconds'])
        union=[];phase_refs={}
        for phase in snap['phases']:
            name=phase['name'];windows=phase['effective'];seconds=sum(w['end']-w['start'] for w in windows)/1000
            selected=[r for r in rows if any(w['start']<=int(r['timeStamp'])<w['end'] for w in windows)]
            if name!='Complete execution':union+=windows
            view=next((v for v in snap['analysis']['views'] if v['window']['phase']==name),None)
            if view is None:
                check(name+' absent population',0,len(selected));continue
            check(name+' count',len(selected),view['included'])
            check(name+' duration',seconds,view['durationSeconds'])
            phase_refs[name]={}
            for actual in view['rows']:
                population=selected if actual['label']=='TOTAL' else [r for r in selected if r['label']==actual['label']]
                reference=metrics(population,seconds);phase_refs[name][actual['label']]=reference
                for key,value in reference.items():check(name+'/'+actual['label']+'/'+key,value,actual.get(key))
        outside=sum(not any(w['start']<=int(r['timeStamp'])<w['end'] for w in union) for r in rows)
        check('Outside phases preserved',outside,snap['unclassified'])
        check('All native samples captured',len(rows),phase_refs['Complete execution']['TOTAL']['samples'])
        summary['samples']=len(rows);summary['responseCodes']=codes
        check('No class/runtime errors',False,bool(re.search(r'ClassNotFoundException|NoClassDefFoundError|NoSuchMethodError|Cannot initialize phase analyzer|Finalization/export failed|ERROR', (OUT/'jmeter.log').read_text())))
        summary['functional']='PASS' if all(c['pass'] for c in checks) else 'FAIL'
        summary['html']=summary['functional']
        xlsx=list((OUT/'reports').glob('*.xlsx'));check('One XLSX',1,len(xlsx))
        ns={'s':'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
        with zipfile.ZipFile(xlsx[0]) as z:
            check('XLSX ZIP integrity',None,z.testzip())
            names=[s.attrib['name'] for s in ET.fromstring(z.read('xl/workbook.xml')).findall('s:sheets/s:sheet',ns)]
            check('Seven worksheet names',['Execution Summary','Ramp-up','Steady Load','Ramp-down','Complete Execution','Response Codes','Execution Configuration'],names)
            strings=[]
            if 'xl/sharedStrings.xml' in z.namelist():strings=[''.join(s.itertext()) for s in ET.fromstring(z.read('xl/sharedStrings.xml'))]
            def cells(index):
                result=[]
                for row in ET.fromstring(z.read(f'xl/worksheets/sheet{index}.xml')).findall('s:sheetData/s:row',ns):
                    values={}
                    for c in row:
                        v=c.find('s:v',ns);value=v.text if v is not None else None
                        if c.attrib.get('t')=='inlineStr':value=''.join(c.find('s:is',ns).itertext())
                        elif c.attrib.get('t')=='s':value=strings[int(value)]
                        elif value is not None:
                            try:value=float(value)
                            except ValueError:pass
                        values[re.sub(r'\d','',c.attrib['r'])]=value
                    result.append(values)
                return result
            keys=['samples','averageMs','medianMs','p90Ms','p95Ms','p99Ms','minMs','maxMs','stddevMs','errorPercent','samplesPerSecond','receivedKBPerSecond','sentKBPerSecond','successful','failed']
            for phase,index in [('Ramp-up',2),('Steady load',3),('Ramp-down',4),('Complete execution',5)]:
                refs=phase_refs.get(phase,{})
                found=[]
                for row in cells(index):
                    label=str(row.get('B','')).removeprefix('Requests / ')
                    if label not in refs:continue
                    found.append(label)
                    for offset,key in enumerate(keys,2):
                        actual=row.get(chr(65+offset));actual=None if actual=='N/A' else actual
                        check('Excel '+phase+'/'+label+'/'+key,refs[label][key],actual)
                check('Excel '+phase+' labels',sorted(refs),sorted(found))
            code_rows=cells(6)
            excel_codes={str(int(row['A'])):int(row['C']) for row in code_rows if isinstance(row.get('A'),(int,float)) and str(int(row['A'])) in codes}
            check('Excel response-code counts',codes,excel_codes)
        summary['excel']='PASS' if all(c['pass'] for c in checks if c['scope'].startswith(('Excel','XLSX','Seven','One XLSX'))) else 'FAIL'
        summary['result']='PASS' if all(c['pass'] for c in checks) else 'FAIL'
    except Exception as error:
        summary['result']='FAIL';summary['error']=repr(error)
    finally:
        summary['checksPassed']=sum(c['pass'] for c in checks);summary['checksTotal']=len(checks)
        (OUT/'validation-summary.json').write_text(json.dumps(summary,indent=2))
        print(json.dumps({k:v for k,v in summary.items() if k!='checks'},indent=2))
    if summary['result']!='PASS':raise SystemExit(1)

if __name__=='__main__':
    {'prepare':prepare,'serve':serve,'validate':validate}[sys.argv[1]]()


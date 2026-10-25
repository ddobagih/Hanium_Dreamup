#!/usr/bin/env python3
"""Run deterministic WalkSafe sensor/callback scenarios against current production code.
No phone, live API, Goal, product mutation or publishing is performed by this runner.
"""
from __future__ import annotations
import argparse,fcntl,hashlib,json,os,re,shutil,subprocess,sys,time
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
DEFAULT_CASES=ROOT/'apps/android/app/src/test/resources/navigation/guidance_sensor_replay_v2_20261010.json'
SENSOR='kr.co.hanium.dreamup.walksafe.GuidanceSensorReplayTest'
READY='kr.co.hanium.dreamup.walksafe.navigation.start.GuidanceReadyStartReplayTest'
RAW='kr.co.hanium.dreamup.walksafe.navigation.RouteCompassRawSensorReplayTest'
FULL='kr.co.hanium.dreamup.walksafe.GuidanceFullJourneyReplayTest'
ALLOWED_ASSERTS={'clock_metres','route_done','no_route_stop','grace_active','grace_onset_fixed','recovered','automatic_route_resumed','no_false_arrival','persistent_response','owner_retired','blocked_before_grace_end','output_retries','guidance_continues','gps_not_future','duplicate_not_renewed','gps_expired_before_confirmation'}
ALLOWED_EVENTS={'heading','heading_unavailable','gps','gps_unavailable','owner','permission','tts'}
POLICY={'gps_period_ms':3000,'gps_confirmations':6,'direction_observation_ms':300,'direction_grace_ms':1500,'direction_confirmations':6,'camera_grace_ms':4500,'raw_location_ttl_ms':10000}

def digest(path:Path)->str:
 h=hashlib.sha256()
 with path.open('rb') as inp:
  for block in iter(lambda:inp.read(1048576),b''):h.update(block)
 return h.hexdigest()

def catalog(path:Path,pattern:str)->tuple[dict,list[dict]]:
 doc=json.loads(path.read_text(encoding='utf-8'))
 if doc.get('schema')!='walksafe.guidance_sensor_replay.v2' or doc.get('policy')!=POLICY:
  raise ValueError('Unsupported schema or policy. Change policy only after a user decision and actual production comparison.')
 ids=set(); selected=[]; matcher=re.compile(pattern)
 for case in doc['cases']:
  cid=case['id']
  if not re.fullmatch(r'[a-z][a-z0-9_]{0,79}',cid) or cid in ids:raise ValueError('Duplicate or malformed case id')
  ids.add(cid)
  if case['scope']!='ADMITTED_WALK_HEADING_OBSERVATION':raise ValueError('Unknown coverage scope')
  previous=-1
  for event in case['events']:
   at=event['at_ms']
   if type(at)!=int or at<0 or at<previous or event['type'] not in ALLOWED_EVENTS:raise ValueError(f'{cid}: malformed event order/type')
   previous=at
  if not case['expectations']:raise ValueError(f'{cid}: missing expectations')
  for check in case['expectations']:
   if type(check['at_ms'])!=int or check['at_ms']<0 or check['assert'] not in ALLOWED_ASSERTS:raise ValueError(f'{cid}: unknown expectation')
  if matcher.fullmatch(cid):selected.append(case)
 if not selected:raise ValueError('Case filter matched no scenario; no successful empty run is allowed')
 return doc,selected

def product_fingerprint()->dict:
 base=ROOT/'apps/android/app/src'
 paths=sorted(p for folder in [base/'main/java',base/'debug/java'] if folder.exists() for p in folder.rglob('*') if p.is_file() and p.suffix in {'.kt','.java'})
 paths += [ROOT/'apps/android/app/build.gradle.kts']
 sources={str(p.relative_to(ROOT)):digest(p) for p in sorted(paths)}
 return {'source_id':hashlib.sha256(json.dumps(sources,sort_keys=True,separators=(',',':')).encode()).hexdigest(),'sources':sources,'meaning':'Current Java/Kotlin production source plus app build configuration; not an APK hash or field proof.'}

def harness_fingerprint()->dict:
 paths=[ROOT/'scripts/replay_guidance_sensors.py',ROOT/'apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/GuidanceSensorReplayHarness.kt',ROOT/'apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/GuidanceSensorReplayTest.kt',ROOT/'apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/navigation/start/GuidanceReadyStartReplayTest.kt',ROOT/'apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteInitialConnectionRecoveryTest.kt',ROOT/'apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/navigation/session/PositioningSessionControllerTest.kt',ROOT/'apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteGuidanceControllerTest.kt',ROOT/'apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/runtime/GatewayWalkSessionControllerBehaviorTest.kt',ROOT/'apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/runtime/WalkSessionResumeControllerBehaviorTest.kt',ROOT/'apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/runtime/WalkSessionCleanupControllerBehaviorTest.kt',ROOT/'apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/voice/SpeechOutputControllerBehaviorTest.kt']
 paths += [ROOT/'apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/navigation/RouteCompassRawSensorReplayTest.kt']
 paths += [ROOT/'apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/GuidanceFullJourneyReplayTest.kt',ROOT/'apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/GuidanceFullJourneyReplayHarness.kt']
 return {str(p.relative_to(ROOT)):digest(p) for p in paths if p.is_file()}

def main()->int:
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--cases',type=Path,default=DEFAULT_CASES)
 parser.add_argument('--case',default='.*',help='full-match regular expression over case IDs')
 parser.add_argument('--phase',choices=['all','sensors','ready-start','raw-sensors','full-journey'],default='all')
 parser.add_argument('--list',action='store_true',help='validate and print the selected scenario catalog; no Gradle')
 parser.add_argument('--output',type=Path,help='fresh result directory; an existing nonempty directory is never overwritten')
 args=parser.parse_args()
 try:doc,selected=catalog(args.cases.resolve(),args.case)
 except (OSError,ValueError,KeyError,re.error) as error:print(f'INPUT_ERROR: {error}',file=sys.stderr);return 4
 if args.list:
  for case in selected:print(f"{case['id']}\t{case['title_ko']}\t{case['scope']}")
  print(f'{len(selected)} scenarios; FULL_HOME_TO_ARRIVAL={doc.get("full_home_to_arrival",False)}')
  return 0
 now=datetime.now(ZoneInfo('Asia/Seoul'))
 output=(args.output or Path('/home/ddobagi/.local/state/walksafe-development/guidance-sensor-replay-20261009')/now.strftime('run-%Y%m%dT%H%M%S-%f')).resolve()
 output.mkdir(parents=True,exist_ok=True)
 if any(output.iterdir()):print('OUTPUT_ERROR: use a fresh result directory',file=sys.stderr);return 4
 frozen=dict(doc,cases=selected)
 casefile=output/'cases.json';casefile.write_text(json.dumps(frozen,ensure_ascii=False,indent=2)+'\n')
 fingerprint=product_fingerprint(); harness_before=harness_fingerprint();(output/'product-source.json').write_text(json.dumps(fingerprint,indent=2)+'\n')
 env=dict(os.environ)
 env.update({'ANDROID_HOME':env.get('ANDROID_HOME','/home/ddobagi/Android/Sdk'),'WALKSAFE_SENSOR_REPLAY_CASE_FILE':str(casefile),'WALKSAFE_SENSOR_REPLAY_CASE_FILTER':'.*','WALKSAFE_SENSOR_REPLAY_OUTPUT_DIR':str(output/'traces'),'WALKSAFE_REPLAY_XML_DIR':str(output/'junit'),'WALKSAFE_REPLAY_HTML_DIR':str(output/'html')})
 init=output/'replay.init.gradle'
 init.write_text('''gradle.projectsEvaluated {
 allprojects {
    tasks.withType(org.gradle.api.tasks.testing.Test).configureEach {
        if (name == 'testDebugUnitTest') {
            maxParallelForks = 1
            outputs.upToDateWhen { false }
            outputs.cacheIf { false }
            ['WALKSAFE_SENSOR_REPLAY_CASE_FILE', 'WALKSAFE_SENSOR_REPLAY_CASE_FILTER', 'WALKSAFE_SENSOR_REPLAY_OUTPUT_DIR'].each { key -> environment(key, System.getenv(key)) }
            inputs.file(System.getenv('WALKSAFE_SENSOR_REPLAY_CASE_FILE'))
            systemProperty('walksafe.fullJourneyReplay.outputDir', new File(System.getenv('WALKSAFE_SENSOR_REPLAY_OUTPUT_DIR')).getParent() + '/traces-full')
            reports.junitXml.outputLocation.set(new File(System.getenv('WALKSAFE_REPLAY_XML_DIR')))
            reports.html.outputLocation.set(new File(System.getenv('WALKSAFE_REPLAY_HTML_DIR')))
        }
    }
}
}
''')
 command=[str(ROOT/'apps/android/gradlew'),'-p',str(ROOT/'apps/android'),'--no-daemon','--console=plain','--max-workers=1','--no-build-cache','--init-script',str(init),'-Dorg.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g','-Pkotlin.compiler.execution.strategy=in-process','-Pkotlin.incremental=false','-PWALKSAFE_GATEWAY_ORIGIN=https://online-nokia-webcams-crops.trycloudflare.com','-Pwalksafe.guidanceStartBypass=false','-PdevelopmentQuickStart=false','-Pwalksafe.guidanceDiagnosticBuildId='+fingerprint['source_id'],':app:testDebugUnitTest']
 if args.phase in ['all','sensors']:command+=['--tests',SENSOR]
 if args.phase in ['all','ready-start']:command+=['--tests',READY]
 if args.phase in ['all','raw-sensors']:command+=['--tests',RAW]
 if args.phase in ['all','full-journey']:command+=['--tests',FULL]
 lockpath=ROOT/'work/part-a-four-sessions-20261001/gradle.lock';lockpath.parent.mkdir(parents=True,exist_ok=True)
 start=time.monotonic(); started_wall=time.time()
 with lockpath.open('a') as lock:
  try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
  except BlockingIOError:print('BUILD_BUSY: an existing shared Gradle build owns the lock; do not run a second build',file=sys.stderr);return 3
  with (output/'gradle.log').open('w') as log:
   try:result=subprocess.run(command,cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
   except OSError as error:print(f'ENVIRONMENT_ERROR: {error}',file=sys.stderr);return 3
 totals={'classes':0,'tests':0,'failures':0,'errors':0,'skipped':0};failures=[];reports=[]
 report_path_fallback=False
 if not list((output/'junit').glob('TEST-*.xml')):
  target_names=([SENSOR] if args.phase in ['all','sensors'] else [])+([READY] if args.phase in ['all','ready-start'] else [])+([RAW] if args.phase in ['all','raw-sensors'] else [])+([FULL] if args.phase in ['all','full-journey'] else [])
  for name in target_names:
   standard=ROOT/'apps/android/app/build/test-results/testDebugUnitTest'/('TEST-'+name+'.xml')
   if standard.is_file() and standard.stat().st_mtime>=started_wall:
    (output/'junit').mkdir(exist_ok=True);shutil.copyfile(standard,output/'junit'/standard.name);report_path_fallback=True
 try:
  for p in sorted((output/'junit').glob('TEST-*.xml')):
   element=ET.parse(p).getroot();totals['classes']+=1;reports.append(p.name)
   for k in ['tests','failures','errors','skipped']:totals[k]+=int(element.attrib.get(k,0))
   for case in element.findall('testcase'):
    for failure in list(case.findall('failure'))+list(case.findall('error')):
     failures.append({'class':element.attrib.get('name'),'case':case.attrib.get('name'),'type':failure.attrib.get('type'),'message':failure.attrib.get('message','')[:1800],'stack_file':p.name,'category':'UNTRIAGED_ASSERTION' if failure.attrib.get('type','').endswith('AssertionError') else 'UNTRIAGED_EXECUTION'})
 except (ET.ParseError,OSError,ValueError) as error:print(f'REPORT_ERROR: {error}',file=sys.stderr);return 3
 source_after=product_fingerprint();drift=source_after['source_id']!=fingerprint['source_id']; harness_drift=harness_fingerprint()!=harness_before
 trace_files=sorted((output/'traces').glob('*.json')) if (output/'traces').exists() else []
 execution_verified=totals['tests']>0 and not drift and not harness_drift and totals['skipped']==0
 raw_source=ROOT/'apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/navigation/RouteCompassRawSensorReplayTest.kt'
 raw_test_count=len(re.findall(r'@Test\s+fun\s+',raw_source.read_text()))
 full_source=ROOT/'apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/GuidanceFullJourneyReplayTest.kt'
 full_test_count=len(re.findall(r'@Test\s+fun\s+',full_source.read_text()))
 full_case_ids=sorted(set(re.findall(r'caseId\s*=\s*"([a-z0-9_]+)"',full_source.read_text())+
                         re.findall(r'"(full_[a-z0-9_]+)"',full_source.read_text())))
 expected_min=(len(selected) if args.phase in ['all','sensors'] else 0)+(10 if args.phase in ['all','ready-start'] else 0)+(raw_test_count if args.phase in ['all','raw-sensors'] else 0)+(full_test_count if args.phase in ['all','full-journey'] else 0)
 execution_verified=execution_verified and totals['tests']==expected_min
 if args.phase in ['all','sensors']:
  execution_verified=execution_verified and {p.stem for p in trace_files}=={c['id'] for c in selected}
 if args.phase in ['all','full-journey']:
  execution_verified=execution_verified and len(full_case_ids)==full_test_count and len(set(full_case_ids))==full_test_count
  execution_verified=execution_verified and {p.stem for p in (output/'traces-full').glob('*.json')}==set(full_case_ids)
 green=result.returncode==0 and totals['failures']==totals['errors']==0 and execution_verified
 status='PASS_WITH_SCOPE_GAPS' if green else ('RED_REQUIRES_TRIAGE' if execution_verified and (totals['failures'] or totals['errors']) else 'EXECUTION_OR_ENVIRONMENT_ERROR')
 summary={'schema':'walksafe.sensor_replay.result.v1','at':now.isoformat(),'status':status,'gradle_exit':result.returncode,'elapsed_seconds':round(time.monotonic()-start,2),'phase':args.phase,'source_id':fingerprint['source_id'],'source_changed_during_run':drift,'harness_changed_during_run':harness_drift,'harness_sources':harness_before,'standard_report_path_fallback':report_path_fallback,'case_file_sha256':digest(casefile),'selected_case_ids':[c['id'] for c in selected],'policy':POLICY,'totals':totals,'failures':failures,'traces':{p.name:digest(p) for p in trace_files},'reports':reports,'command':command,'execution_verified':execution_verified,'coverage':{'ADMITTED_WALK_HEADING_OBSERVATION':args.phase in ['all','sensors'],'READY_START_CONTROLLER':args.phase in ['all','ready-start'],'FULL_HOME_TO_ARRIVAL':False,'PHYSICAL_SENSORS_ACOUSTIC_FIELD':False},'open_requirements':doc.get('open_requirements',[]),'limits':['The actual WalkReadinessCoordinator and WalkReadinessProducers compose controlled platform observations and production policies; Android sensor callbacks and physical qualification are not exercised. RouteCompassSession schedule lease and 300ms reevaluation use a controlled timer; Android View Handler delivery and device timing are not exercised.','The admitted-sensor phase assumes an admitted walk. The full-journey phase starts at READY HOME and uses actual calibration/admission/runtime activation; its heading inputs are observations after body-axis conversion. Raw conversion is a separate phase.','Permission, native output runtime, HTTP, clock and SDK collection leaves are controlled; no phone/network/service/account mutation.','Assertion failures require independent product-versus-fixture triage; a non-assertion may also be a product bug. No failure is silently skipped.']}
 summary['coverage']['RAW_SENSOR_CONVERSION']=args.phase in ['all','raw-sensors']
 summary['coverage']['FULL_CONTROLLER_JOURNEY']=args.phase in ['all','full-journey']
 summary['baseline_open_requirements']=summary['open_requirements']
 summary['open_requirements']=['Actual hardware callback timing, active camera/mount service delivery, acoustic output and outdoor walking are outside this controlled replay.']
 if args.phase not in ['all','full-journey']:summary['open_requirements'].append('Single HOME-to-END controller journey is not executed in this phase.')
 if args.phase not in ['all','raw-sensors']:summary['open_requirements'].append('Raw orientation conversion is not executed in this phase.')
 if args.phase in ['raw-sensors','full-journey']:summary['selected_case_ids']=[]
 summary['full_journey_traces']={p.name:digest(p) for p in sorted((output/'traces-full').glob('*.json'))}
 (output/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
 print(json.dumps({'status':status,'output':str(output),'cases':len(selected),'tests':totals['tests'],'failures':totals['failures'],'errors':totals['errors'],'skipped':totals['skipped'],'full_home_to_arrival':False},ensure_ascii=False))
 if green:return 0
 return 1 if execution_verified else 3
if __name__=='__main__':raise SystemExit(main())

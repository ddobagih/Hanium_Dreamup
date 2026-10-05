"""Mac-local medium/turbo comparison using the production STT engine, synthetic audio.
Run with .venv/bin/python scripts/benchmark_local_whisper.py --prepare, then without flags.
Does not change or call the running voice server. Results are STT, not app action accuracy.
"""
from pathlib import Path
import argparse, dataclasses, hashlib, json, os, random, re, resource, subprocess, sys, time, wave
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
OUT = ROOT / 'output/whisper-comparison-20260927'
CASES = [
 ('seoul', '서울역으로 안내해 줘', '서울역'),
 ('suwon', '수원역까지 길 안내해 주세요', '수원역'),
 ('gumi', '구미역으로 안내해 줘', '구미역'),
 ('wangsimni', '왕십리역으로 안내해 주세요', '왕십리역'),
 ('sinnonhyeon', '신논현역까지 안내해 줘', '신논현역'),
 ('cityhall', '서울시청으로 길 안내해 주세요', '서울시청'),
 ('help', '도움말', None), ('settings', '설정 열어 줘', None),
 ('pause', '일시정지', None), ('resume', '보행 재개', None),
 ('end', '안내 종료', None), ('repeat', '다시 들려 줘', None),
]
def normalize(s):
 return re.sub(r'[^가-힣a-z0-9]', '', s.lower())
def distance(a,b):
 row=list(range(len(b)+1))
 for i,x in enumerate(a,1):
  nxt=[i]
  for j,y in enumerate(b,1):nxt.append(min(nxt[-1]+1,row[j]+1,row[j-1]+(x!=y)))
  row=nxt
 return row[-1]
def prepare():
 import numpy as np
 OUT.mkdir(parents=True,exist_ok=True); audio=OUT/'audio';audio.mkdir(exist_ok=True)
 rng=np.random.default_rng(20260927); cases=[]
 def save(name,pcm):
  path=audio/(name+'.wav')
  with wave.open(str(path),'wb') as f:
   f.setnchannels(1);f.setsampwidth(2);f.setframerate(16000)
   f.writeframes((np.clip(pcm,-1,1)*32767).astype('<i2').tobytes())
  return path
 for i,(name,text,entity) in enumerate(CASES):
  voice='Yuna' if i%2==0 else 'Eddy (한국어(한국))'
  aiff=audio/(name+'.aiff');clean=audio/(name+'-clean.wav')
  subprocess.run(['say','-v',voice,'-r','145','-o',str(aiff),text],check=True)
  subprocess.run(['ffmpeg','-v','error','-y','-i',str(aiff),'-ar','16000','-ac','1','-c:a','pcm_s16le',str(clean)],check=True)
  aiff.unlink()
  with wave.open(str(clean),'rb') as f:pcm=np.frombuffer(f.readframes(f.getnframes()),dtype='<i2').astype(float)/32768
  assert len(pcm)>1600, f'Empty synthesis: {name}'
  rms=np.sqrt(np.mean(pcm**2));noise=rng.normal(size=len(pcm));noise*=rms/(10**(10/20)*np.sqrt(np.mean(noise**2)))
  mixed=pcm+noise; mixed/=max(1,np.max(np.abs(mixed))/.95)
  noisy=save(name+'-noise10db',mixed)
  for condition,path in [('clean',clean),('white_noise_10dB',noisy)]:
   cases.append(dict(id=path.stem,path=str(path.relative_to(ROOT)),reference=text,entity=entity,condition=condition,voice=voice,duration=len(pcm)/16000,sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
 for name,pcm in [('silence',np.zeros(32000)),('noise_only',rng.normal(0,.02,32000))]:
  path=save(name,pcm);cases.append(dict(id=name,path=str(path.relative_to(ROOT)),reference='',entity=None,condition=name,voice=None,duration=2,sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
 (OUT/'fixtures.json').write_text(json.dumps(cases,ensure_ascii=False,indent=2))
 print('Prepared',len(cases),'synthetic fixtures',flush=True)
def config(model):
 settings=dict(line.split('=',1) for line in (ROOT/'.env.voice-local').read_text().splitlines() if line.strip() and not line.lstrip().startswith('#'))
 if model=='medium':settings.update(json.loads((ROOT/'weights/voice/stt-settings-before-turbo.json').read_text()))
 assert ('turbo' in settings['VOICE_STT_MODEL']) == (model=='turbo')
 return settings
def worker(model,round_number):
 settings=config(model);os.environ.update(settings)
 from voice.stt import LocalSTTEngine
 engine=LocalSTTEngine(model_size=settings['VOICE_STT_MODEL'],device='cpu',compute_type='int8',language='ko',model_revision=settings['VOICE_STT_MODEL_REVISION'],model_manifest_path=settings['VOICE_STT_MODEL_MANIFEST_PATH'],model_manifest_sha256=settings['VOICE_STT_MODEL_MANIFEST_SHA256'])
 start=time.perf_counter();engine.load();load_seconds=time.perf_counter()-start
 cases=json.loads((OUT/'fixtures.json').read_text())
 warmstart=time.perf_counter();engine.transcribe_file(ROOT/cases[0]['path']);warm_seconds=time.perf_counter()-warmstart
 order=list(cases);random.Random(20260927+round_number).shuffle(order); rows=[]
 print(f'{model} round {round_number}: loaded {load_seconds:.2f}s; warmup {warm_seconds:.2f}s',flush=True)
 for case in order:
  start=time.perf_counter()
  try:
   result=engine.transcribe_file(ROOT/case['path']); elapsed=time.perf_counter()-start
   ref=normalize(case['reference']);hyp=normalize(result.transcript)
   rows.append(dict(**case,model=model,round=round_number,seconds=elapsed,rtf=elapsed/case['duration'],transcript=result.transcript,edits=distance(ref,hyp),reference_chars=len(ref),exact=ref==hyp,entity_preserved=(normalize(case['entity']) in hyp) if case['entity'] else None,acoustic=dataclasses.asdict(result.acoustic),error=None))
  except Exception as exc:
   rows.append(dict(**case,model=model,round=round_number,error=type(exc).__name__,seconds=time.perf_counter()-start))
  print(f'{model} r{round_number} {case["id"]}: {rows[-1]["seconds"]:.2f}s',flush=True)
 meta=dict(model_id=settings['VOICE_STT_MODEL'],revision=settings['VOICE_STT_MODEL_REVISION'],device='cpu',compute_type='int8',beam_size=5,vad_filter=True,language='ko',load_seconds=load_seconds,warmup_seconds=warm_seconds,peak_process_rss_bytes=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss)
 (OUT/f'{model}-round{round_number}.json').write_text(json.dumps(dict(metadata=meta,rows=rows),ensure_ascii=False,indent=2))
def summarize():
 import numpy as np
 summary={}
 for model in ['medium','turbo']:
  blocks=[json.loads((OUT/f'{model}-round{r}.json').read_text()) for r in [1,2]]
  rows=[x for b in blocks for x in b['rows']];data={}
  for condition in ['clean','white_noise_10dB']:
   subset=[r for r in rows if r['condition']==condition];valid=[r for r in subset if not r['error']];dest=[r for r in valid if r['entity']]
   data[condition]=dict(requests=len(subset),errors=sum(bool(r['error']) for r in subset),cer=sum(r['edits'] for r in valid)/sum(r['reference_chars'] for r in valid),exact_matches=sum(r['exact'] for r in valid),destination_preserved=sum(r['entity_preserved'] for r in dest),destination_requests=len(dest),acoustic_rejections=sum(not r['acoustic']['execution_allowed'] for r in valid),p50_seconds=float(np.median([r['seconds'] for r in valid])),p95_seconds=float(np.percentile([r['seconds'] for r in valid],95)),mean_rtf=float(np.mean([r['rtf'] for r in valid])))
  controls=[r for r in rows if r['condition'] in ['silence','noise_only']]
  data['controls']=dict(requests=len(controls),nonempty=sum(bool(r.get('transcript','').strip()) for r in controls),acoustic_allowed=sum(r.get('acoustic',{}).get('execution_allowed',False) for r in controls))
  data['loads']=[b['metadata'] for b in blocks];summary[model]=data
 (OUT/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2));print(json.dumps(summary,ensure_ascii=False,indent=2))
if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('--prepare',action='store_true');parser.add_argument('--worker',choices=['medium','turbo']);parser.add_argument('--round',type=int,default=1);parser.add_argument('--summarize',action='store_true');args=parser.parse_args()
 if args.prepare:prepare()
 elif args.worker:worker(args.worker,args.round)
 elif args.summarize:summarize()
 else:
  for model,r in [('medium',1),('turbo',1),('turbo',2),('medium',2)]:
   subprocess.run([sys.executable,__file__,'--worker',model,'--round',str(r)],check=True,cwd=ROOT)
  summarize()

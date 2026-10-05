"""Controlled hint ablation using pinned production turbo, without server edits."""
import dataclasses, hashlib, json, os, random, subprocess, time, wave
import benchmark_local_whisper as base
OUT=base.ROOT/'output/whisper-hints-20260927'
PROMPT='보행 안내 앱의 한국어 음성 명령입니다. 목적지 안내, 도움말, 설정, 일시정지, 보행 재개, 안내 종료, 다시 듣기를 요청합니다.'
HOTWORDS='서울역, 수원역, 구미역, 왕십리역, 신논현역, 서울시청, 도움말, 설정 열어 줘, 일시정지, 보행 재개, 안내 종료, 다시 들려 줘'
VARIANTS={'baseline':{},'prompt':{'initial_prompt':PROMPT},'hotwords':{'hotwords':HOTWORDS},'both':{'initial_prompt':PROMPT,'hotwords':HOTWORDS}}

def fixtures():
    OUT.mkdir(parents=True,exist_ok=True); audio=OUT/'audio'; audio.mkdir(exist_ok=True)
    previous=json.loads((base.ROOT/'output/whisper-followup-20260927/fixtures.json').read_text())
    names=['wangsimni','sinnonhyeon','help','settings','resume','end']
    cases=[dict(r,group='targeted') for r in previous if r['rate']==145 and not r['padding_ms_each'] and any(r['id'].endswith('-'+n) for n in names)]
    controls=[('busan','부산역으로 안내해 줘','부산역','heldout'),('gangnam','강남역으로 안내해 줘','강남역','heldout'),('weather','오늘 날씨가 좋아요',None,'ordinary'),('negation','안내 종료하지 마',None,'negation')]
    for voice,tag in [('Yuna','yuna'),('Eddy (한국어(한국))','eddy')]:
        for name,text,entity,group in controls:
            stem=tag+'-'+name; aiff=audio/(stem+'.aiff'); wav=audio/(stem+'.wav')
            subprocess.run(['say','-v',voice,'-r','145','-o',str(aiff),text],check=True)
            subprocess.run(['ffmpeg','-v','error','-y','-i',str(aiff),'-ar','16000','-ac','1','-c:a','pcm_s16le',str(wav)],check=True);aiff.unlink()
            with wave.open(str(wav),'rb') as f: duration=f.getnframes()/f.getframerate()
            cases.append(dict(id=stem,path=str(wav.relative_to(base.ROOT)),reference=text,entity=entity,group=group,voice=voice,duration=duration,sha256=hashlib.sha256(wav.read_bytes()).hexdigest()))
    old=json.loads((base.ROOT/'output/whisper-comparison-20260927/fixtures.json').read_text())
    cases += [dict(r,group='nonspeech') for r in old if r['condition'] in ['silence','noise_only']]
    assert len(cases)==22 and len({c['id'] for c in cases})==22
    for c in cases: assert hashlib.sha256((base.ROOT/c['path']).read_bytes()).hexdigest()==c['sha256']
    (OUT/'fixtures.json').write_text(json.dumps(cases,ensure_ascii=False,indent=2))
    return cases

def run(cases=None):
    cases=fixtures() if cases is None else cases
    total=len(cases)*len(VARIANTS)
    settings=base.config('turbo');os.environ.update(settings)
    from voice.stt import LocalSTTEngine,summarize_acoustic_evidence
    engine=LocalSTTEngine(model_size=settings['VOICE_STT_MODEL'],device='cpu',compute_type='int8',language='ko',model_revision=settings['VOICE_STT_MODEL_REVISION'],model_manifest_path=settings['VOICE_STT_MODEL_MANIFEST_PATH'],model_manifest_sha256=settings['VOICE_STT_MODEL_MANIFEST_SHA256'])
    engine.load(); engine.transcribe_file(base.ROOT/cases[0]['path'])
    metadata=dict(model=settings['VOICE_STT_MODEL'],revision=settings['VOICE_STT_MODEL_REVISION'],device='cpu',compute_type='int8',language='ko',beam_size=5,vad_filter=True,variants=VARIANTS,seed=20260927)
    (OUT/'metadata.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2))
    random.Random(20260927).shuffle(cases); rows=[]
    for i,case in enumerate(cases):
        variants=list(VARIANTS); variants=variants[i%4:]+variants[:i%4]
        for variant in variants:
            start=time.perf_counter()
            try:
                segments,_=engine._model.transcribe(str(base.ROOT/case['path']),language='ko',beam_size=5,vad_filter=True,**VARIANTS[variant])
                segments=list(segments);elapsed=time.perf_counter()-start
                transcript=' '.join(s.text.strip() for s in segments).strip()
                ref=base.normalize(case['reference']);hyp=base.normalize(transcript)
                acoustic=summarize_acoustic_evidence([dict(text=s.text,avg_logprob=s.avg_logprob,no_speech_prob=s.no_speech_prob) for s in segments])
                row=dict(**case,variant=variant,transcript=transcript,edits=base.distance(ref,hyp),reference_chars=len(ref),exact=ref==hyp,entity_preserved=base.normalize(case['entity']) in hyp if case['entity'] else None,seconds=elapsed,acoustic=dataclasses.asdict(acoustic),error=None)
            except Exception as exc: row=dict(**case,variant=variant,error=type(exc).__name__,seconds=time.perf_counter()-start)
            rows.append(row)
            (OUT/'results.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2))
            print(f'{len(rows)}/{total} {variant} {case["id"]}: {row.get("transcript",row["error"])}',flush=True)
    assert len(rows)==total

if __name__=='__main__':
    assert base.distance('서울역','서울력')==1
    run()

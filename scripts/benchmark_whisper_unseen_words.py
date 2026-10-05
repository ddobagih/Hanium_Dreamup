"""Fixed saved hotwords vs baseline on new synthetic phrases; do not tune to these."""
import json,subprocess,wave,hashlib
import benchmark_whisper_hints as trial
OUT=trial.base.ROOT/'output/whisper-unseen-20260927'
CASES=[('hongdae','홍대입구역으로 안내해 줘','홍대입구역','destination'),('dongdaegu','동대구역까지 안내해 주세요','동대구역','destination'),('daejeon','대전역으로 가고 싶어요','대전역','destination'),('palace','경복궁으로 안내해 주세요','경복궁','destination'),('pause','잠시 멈춰 줘',None,'command'),('repeat','설명을 다시 해 줘',None,'command'),('helpclose','도움말 닫아 줘',None,'command'),('change','목적지를 변경해 줘',None,'command'),('notend','길 안내를 종료하지 마',None,'negation'),('notsettings','설정을 열지 마',None,'negation'),('lunch','오늘 점심은 비빔밥이야',None,'ordinary'),('time','약속 시간은 오후 세 시야',None,'ordinary')]

def run():
    OUT.mkdir(parents=True,exist_ok=True);audio=OUT/'audio';audio.mkdir(exist_ok=True);cases=[]
    cfg=json.loads((trial.base.ROOT/'output/whisper-hotwords-repeat-20260927/hotwords.json').read_text())
    assert set(cfg)=={'hotwords'}
    for voice,tag in [('Yuna','yuna'),('Eddy (한국어(한국))','eddy')]:
        for name,text,entity,group in CASES:
            if entity:assert entity not in cfg['hotwords']
            stem=tag+'-'+name;aiff=audio/(stem+'.aiff');wav=audio/(stem+'.wav')
            subprocess.run(['say','-v',voice,'-r','145','-o',str(aiff),text],check=True)
            subprocess.run(['ffmpeg','-v','error','-y','-i',str(aiff),'-ar','16000','-ac','1','-c:a','pcm_s16le',str(wav)],check=True);aiff.unlink()
            with wave.open(str(wav),'rb') as f:duration=f.getnframes()/f.getframerate()
            assert duration>0
            cases.append(dict(id=stem,path=str(wav.relative_to(trial.base.ROOT)),reference=text,entity=entity,group=group,voice=voice,duration=duration,sha256=hashlib.sha256(wav.read_bytes()).hexdigest()))
    assert len(cases)==24
    (OUT/'fixtures.json').write_text(json.dumps(cases,ensure_ascii=False,indent=2))
    trial.OUT=OUT;trial.VARIANTS={'baseline':{},'hotwords':cfg};trial.run(cases)
if __name__=='__main__':run()

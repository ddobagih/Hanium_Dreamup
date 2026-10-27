"""Saved initial_prompt alone vs baseline; reuse fixed unseen-word waveforms."""
import hashlib,json
import benchmark_whisper_hints as trial
OUT=trial.base.ROOT/'output/whisper-prompt-unseen-20260927'

def run():
    OUT.mkdir(parents=True,exist_ok=True)
    config_path=OUT/'initial-prompt.json'
    if not config_path.exists():
        config_path.write_text(json.dumps({'initial_prompt':trial.PROMPT},ensure_ascii=False,indent=2)+'\n')
    config=json.loads(config_path.read_text())
    assert set(config)=={'initial_prompt'} and isinstance(config['initial_prompt'],str) and config['initial_prompt'].strip()
    cases=json.loads((trial.base.ROOT/'output/whisper-unseen-20260927/fixtures.json').read_text())
    controls=json.loads((trial.OUT/'fixtures.json').read_text())
    cases += [c for c in controls if c['group']=='nonspeech']
    assert len(cases)==26 and len({c['id'] for c in cases})==26
    for c in cases:assert hashlib.sha256((trial.base.ROOT/c['path']).read_bytes()).hexdigest()==c['sha256']
    (OUT/'fixtures.json').write_text(json.dumps(cases,ensure_ascii=False,indent=2))
    trial.OUT=OUT;trial.VARIANTS={'baseline':{},'prompt':config};trial.run(cases)
if __name__=='__main__':run()

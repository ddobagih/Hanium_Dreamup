"""Repeat the same fixtures twice using the saved hotwords-only test config."""
import hashlib,json
import benchmark_whisper_hints as trial
OUT=trial.base.ROOT/'output/whisper-hotwords-repeat-20260927'

def run():
    OUT.mkdir(parents=True,exist_ok=True)
    config_path=OUT/'hotwords.json'
    if not config_path.exists():
        config_path.write_text(json.dumps({'hotwords':trial.HOTWORDS},ensure_ascii=False,indent=2)+'\n')
    config=json.loads(config_path.read_text())
    assert set(config)=={'hotwords'} and isinstance(config['hotwords'],str) and config['hotwords'].strip()
    fixtures=json.loads((trial.OUT/'fixtures.json').read_text())
    assert len(fixtures)==22
    for c in fixtures:
        assert hashlib.sha256((trial.base.ROOT/c['path']).read_bytes()).hexdigest()==c['sha256']
    cases=[dict(c,repeat=n) for n in [1,2] for c in fixtures]
    (OUT/'fixtures.json').write_text(json.dumps(cases,ensure_ascii=False,indent=2))
    trial.OUT=OUT
    trial.VARIANTS={'hotwords':config}
    trial.run(cases)

if __name__=='__main__':run()

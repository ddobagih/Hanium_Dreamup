"""Balanced synthetic turbo diagnostic; no production configuration changes."""
import json, subprocess, wave, hashlib, argparse
import benchmark_local_whisper as base
OUT = base.ROOT / 'output/whisper-followup-20260927'
base.OUT = OUT

def prepare():
    OUT.mkdir(exist_ok=True, parents=True)
    audio = OUT / 'audio'
    audio.mkdir(exist_ok=True)
    cases = []
    for voice, tag in [('Yuna', 'yuna'), ('Eddy (한국어(한국))', 'eddy')]:
        for rate in [145, 190]:
            for name, text, entity in base.CASES:
                stem = f'{tag}-{rate}-{name}'
                aiff = audio / (stem + '.aiff')
                path = audio / (stem + '.wav')
                subprocess.run(['say', '-v', voice, '-r', str(rate), '-o', str(aiff), text], check=True)
                subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', str(aiff), '-ar', '16000', '-ac', '1', '-c:a', 'pcm_s16le', str(path)], check=True)
                aiff.unlink()
                with wave.open(str(path), 'rb') as f:
                    pcm = f.readframes(f.getnframes())
                assert len(pcm) > 3200
                versions = [(path, pcm, 0)]
                if tag == 'yuna' and rate == 145:
                    padded = audio / (stem + '-padded.wav')
                    padded_pcm = bytes(16000) + pcm + bytes(16000)
                    with wave.open(str(padded), 'wb') as f:
                        f.setnchannels(1); f.setsampwidth(2); f.setframerate(16000); f.writeframes(padded_pcm)
                    versions.append((padded, padded_pcm, 500))
                for wav, samples, padding in versions:
                    cases.append(dict(id=wav.stem, path=str(wav.relative_to(base.ROOT)), reference=text, entity=entity, condition='clean', voice=voice, rate=rate, padding_ms_each=padding, category='destination' if entity else 'command', duration=len(samples)/32000, sha256=hashlib.sha256(wav.read_bytes()).hexdigest()))
    assert len(cases) == 60 and len({c['id'] for c in cases}) == 60
    (OUT/'fixtures.json').write_text(json.dumps(cases, ensure_ascii=False, indent=2))
    print('Prepared 60 fixtures', flush=True)

if __name__ == '__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('--prepare', action='store_true'); args=parser.parse_args()
    assert base.distance('서울역', '서울력') == 1
    assert base.normalize('안내해 주세요.') == '안내해주세요'
    if args.prepare: prepare()
    else: base.worker('turbo', 1)

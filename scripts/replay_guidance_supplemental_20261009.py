#!/usr/bin/env python3
"""Additional behavioral inputs; reuses the production replay owners, not a sensor model.

--generate writes only the supplemental catalog. --list performs no Gradle execution.
Sensor runs delegate to replay_guidance_sensors.py. Readiness runs use one separate test
class, the same shared lock, dedicated reports and before/after source fingerprints.
"""
from __future__ import annotations

import argparse
import fcntl
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
from datetime import datetime
from zoneinfo import ZoneInfo
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
CASES = ROOT / 'apps/android/app/src/test/resources/navigation/guidance_replay_supplemental_20261009.json'
TEST = 'kr.co.hanium.dreamup.walksafe.GuidanceReplaySupplementalReadiness20261009Test'
TEST_SOURCE = ROOT / 'apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/GuidanceReplaySupplementalReadiness20261009Test.kt'
POLICY = dict(gps_period_ms=3000, gps_confirmations=3, direction_observation_ms=300,
              direction_grace_ms=1500, direction_confirmations=3, raw_location_ttl_ms=10000)
INITIAL = dict(gps_accuracy_m=5, heading_deg=0, heading_accuracy_deg=3,
               tts_start_delay_ms=80, tts_done_delay_ms=2200, engine_ready_delay_ms=0,
               intro_start_delay_ms=60, intro_done_delay_ms=200)
READINESS_IDS = [
    'late_camera_after_calibration', 'late_official_after_calibration',
    'initial_mount_assumption_without_manual_action', 'detector_unavailable_blocks_start',
    'camera_recovery_uses_initial_mount_assumption', 'late_camera_and_official', 'camera_and_detector_unavailable_blocks_start',
    'camera_arrives_one_ms_before_timer', 'camera_arrives_one_ms_after_timer',
    'start_callback_without_done', 'provider_archive_ack_delayed',
    'cold_engine_ready_after_home_request',
]


def gps(at, accuracy=5, north=None, east=0, age=100, **extra):
    return dict(at_ms=at, type='gps', north_m=at / 1000 if north is None else north,
                east_m=east, accuracy_m=accuracy, age_ms=age, mock=False, **extra)


def heading(at, degrees=0, age=0, accuracy=3):
    return dict(at_ms=at, type='heading', degrees=degrees, accuracy_deg=accuracy, age_ms=age)


def unavailable(at):
    return dict(at_ms=at, type='heading_unavailable', reason='REFERENCE_MISSING')


def check(at, assertion, **extra):
    return dict(at_ms=at, assert_=assertion, **extra)


def checks(*items):
    return [dict(at_ms=c['at_ms'], **{'assert': c['assert_']},
                 **{k: v for k, v in c.items() if k not in {'at_ms', 'assert_'}}) for c in items]


def healthy(end, *, exclude_heading=lambda t: False, gps_times=None):
    events = [heading(t) for t in range(300, end + 1, 300) if not exclude_heading(t)]
    for t in (gps_times if gps_times is not None else range(900, end + 1, 1000)):
        events.append(gps(t))
    return events


def catalog():
    cases = []

    def add(cid, title, family, events, expectations, initial=None, note=''):
        value = dict(id='ext_' + cid, title_ko=title, scope='ADMITTED_WALK_HEADING_OBSERVATION',
                     supplemental_family=family, initial=dict(INITIAL, **(initial or {})),
                     events=sorted(events, key=lambda e: e['at_ms']),
                     expectations=checks(*expectations), expectation_note_ko=note)
        cases.append(value)

    for name, bad in [('negative', gps(1000, -1)), ('missing_after_bad', gps(1300, accuracy_kind='missing')),
                      ('nan_after_bad', gps(1300, accuracy_kind='nan'))]:
        events = healthy(3300, gps_times=[900, 1800, 2100, 2900]) + [gps(1000, 40), bad]
        add('gps_' + name + '_retains_and_recovers', 'GPS 오류 종류 변경 중 최근 근거 유지·새 관측 회복',
            'gps_transient_mixed', events,
            [check(1400, 'grace_active', kind='gps'), check(1400, 'grace_onset_fixed', kind='gps'),
             check(2400, 'recovered', kind='gps'), check(2700, 'no_route_stop'), check(3000, 'no_false_arrival')])

    for restored in [9600, 9800, 9950]:
        events = healthy(10300, gps_times=[900])
        events += [gps(t, 40, north=1) for t in [1000, 1300, 3999, 4000, 6999, 7000, 9300]]
        events += [gps(restored, north=1.1), gps(restored + 20, north=1.2)]
        add(f'gps_recovers_before_deadline_{restored}', 'GPS 9초 유예 만료 직전 정상 복귀', 'gps_deadline', events,
            [check(9200, 'guidance_continues'), check(9200, 'grace_onset_fixed', kind='gps'),
             check(restored + 30, 'recovered', kind='gps'), check(10200, 'no_route_stop'),
             check(10300, 'no_false_arrival')], initial={'tts_done_delay_ms': 12000},
            note='최초 BAD=1000ms, 고정 deadline=10000ms. 복구 표본을 deadline 이전에 제공한다. 초기 음성 DONE을 조작하지 않는다.')

    events = healthy(11000, gps_times=[900])
    events += [gps(t, north=.9, age=t - 800) for t in [1300, 1900, 3900, 6900, 9900, 10300]]
    add('gps_duplicate_burst_near_ttl', '중복 GPS가 원 800ms 시각을 TTL 근처까지 갱신하지 않음',
        'timestamp_integrity', events,
        [check(t, 'duplicate_not_renewed', measured_at_ms=800) for t in [1400, 4000, 7000, 10000]] +
        [check(10500, 'gps_not_future'), check(11000, 'no_false_arrival')],
        note='수신시각을 현재 근거로 바꾸지 않는다. TTL 이후 raw가 폐기될 수 있어 동일 timestamp assert는 만료 전에만 적용한다.')
    for kind, at, age in [('older', 1300, 1100), ('future', 1300, -1), ('future_large', 1300, -1000)]:
        end = 3300 if kind == 'older' else 6600
        events = healthy(end) + [gps(at, north=400, accuracy=3, age=age)]
        assertions = ([check(1500, 'duplicate_not_renewed', measured_at_ms=800), check(2600, 'route_done')]
                      if kind == 'older' else [check(6400, 'recovered', kind='gps'), check(6400, 'route_done')])
        add('gps_' + kind + '_cannot_replace_original', '역순·미래 GPS가 진행 근거를 대체하지 않음',
            'timestamp_integrity', events,
            [check(1500, 'gps_not_future'), check(1700, 'no_false_arrival')] + assertions,
            note='역순 입력은 이전 원 시각을 유지한다. 미래 입력은 hard rejection으로 raw를 폐기할 수 있으므로 이전 raw 강제 보존을 요구하지 않는다. 대신 미래 근거 미게시·허위 도착 방지·새 정상 스트림의 회복 및 실제 DONE을 검사한다. 최초 과도한 raw-retention 기대 RED는 보존한다.')

    for onset in [300, 900, 1250]:
        recovered = onset + 4400
        end = recovered + 700
        events = healthy(end, exclude_heading=lambda t, a=onset, b=recovered: a <= t < b)
        events += [unavailable(t) for t in range(onset, recovered, 300)] + [heading(recovered)]
        add(f'direction_recovers_before_deadline_{onset}', '방향 유예의 서로 다른 시작 위상과 만료 직전 회복',
            'direction_deadline', events,
            [check(onset + 100, 'grace_active', kind='direction'),
             check(onset + 3000, 'grace_onset_fixed', kind='direction'),
             check(recovered + 200, 'recovered', kind='direction'),
             check(end, 'no_route_stop'), check(end, 'no_false_arrival')],
            initial={'tts_done_delay_ms': 10000},
            note='최초 방향 BAD 뒤 4400ms에 새 정상 표본을 제공해 4500ms 유예를 유지한다. 반복 BAD가 onset을 연장하면 실패한다.')

    for name, gps_at, heading_at, direction_first in [('gps_first', 1800, 2400, False),
            ('direction_first', 2400, 1800, True), ('same_gps_first', 1800, 1800, False),
            ('same_direction_first', 1800, 1800, True)]:
        events = healthy(3600, exclude_heading=lambda t, h=heading_at: 1000 <= t < h,
                         gps_times=[900, gps_at, max(gps_at + 300, 2800)])
        pair = [gps(1000, 40, north=1), unavailable(1000)]
        if direction_first: pair.reverse()
        events += pair + [unavailable(1300), heading(heading_at)]
        if gps_at == heading_at:
            # Explicit same-time restoration ordering, independent of regular samples.
            events = [e for e in events if e['at_ms'] != gps_at]
            restored = [gps(gps_at), heading(heading_at)]
            if direction_first: restored.reverse()
            events += restored
        add('dual_recovery_' + name, 'GPS·방향 문제의 회복 순서와 동일시각 이벤트 순서',
            'mixed_recovery_order', events,
            [check(1500, 'guidance_continues'), check(1500, 'no_route_stop'),
             check(3200, 'recovered', kind='gps'), check(3500, 'no_false_arrival')])

    for name, start, done in [('pending', 1200, 3000), ('playing', 80, 3000)]:
        events = healthy(3300) + [gps(1000, 40, north=1), gps(1300, 5)]
        add('gps_bad_during_tts_' + name, '음성 START 대기/재생 중 GPS 순간 오류',
            'tts_sensor_interaction', events,
            [check(1100, 'guidance_continues'), check(1800, 'recovered', kind='gps'),
             check(3200, 'route_done'), check(3200, 'no_route_stop')],
            initial={'tts_start_delay_ms': start, 'tts_done_delay_ms': done})
    events = healthy(5000, exclude_heading=lambda t: 600 <= t < 1800)
    events += [unavailable(t) for t in [600, 900, 1200, 1500]]
    add('direction_bad_during_delayed_tts_start', '늦은 START와 방향 유예가 겹쳐도 적격 음성을 반복 중단하지 않음',
        'tts_sensor_interaction', events,
        [check(1000, 'grace_active', kind='direction'), check(1000, 'no_route_stop'),
         check(2100, 'recovered', kind='direction'), check(4500, 'route_done')],
        initial={'tts_start_delay_ms': 1200, 'tts_done_delay_ms': 3000},
        note='원 facing cue가 만료되어 실제 1200ms 교체 ROUTE 요청이 생긴다. 그 요청의 DONE=1200+3000=4200ms이므로 4500ms에 검사한다. 최초3200ms 기대 RED를 보존한다. native START 이후 완료를 추정하지 않고 실제 callback ACK를 요구한다.')

    for callback in ['ERROR', 'STOP']:
        events = healthy(5000) + [dict(at_ms=500, type='tts', callback=callback, utterance='first_route'),
                                 dict(at_ms=800, type='tts', callback='START', utterance='first_route'),
                                 dict(at_ms=900, type='tts', callback='DONE', utterance='first_route')]
        add('tts_' + callback.lower() + '_late_callbacks_retry', '종료된 음성의 늦은 START/DONE 이후 실제 자동 재시도',
            'tts_terminal_callback', events,
            [check(2800, 'output_retries'), check(4900, 'no_false_arrival')],
            note='일반 ERROR/STOP 재시도와 오래된 완료를 구분한다. 새로운 적격 cue의 교체 요청을 같은 실패 cue backoff와 동일하게 금지하지 않는다.')

    for owner in ['foreground', 'epoch', 'session', 'actor', 'location_generation', 'input_generation', 'route_reset']:
        events = [heading(200), dict(at_ms=300, type='owner', change=owner),
                  dict(at_ms=700, type='tts', callback='START', utterance='first_route'),
                  dict(at_ms=900, type='tts', callback='DONE', utterance='first_route'),
                  gps(1000), heading(1100)]
        add('pending_tts_owner_' + owner, 'START 대기 중 owner 교체와 늦은 음성·센서 입력',
            'hard_owner_boundary', events,
            [check(500, 'owner_retired'), check(1200, 'owner_retired'), check(1400, 'no_false_arrival')],
            initial={'tts_start_delay_ms': 1200, 'tts_done_delay_ms': 5000})

    for name, fault in [('gps', gps(400, 40)), ('direction', unavailable(400))]:
        events = [heading(300), fault, dict(at_ms=600, type='permission', granted=False),
                  dict(at_ms=800, type='tts', callback='START', utterance='first_route'),
                  dict(at_ms=900, type='tts', callback='DONE', utterance='first_route'), gps(1000), heading(1100)]
        add('permission_revoked_during_' + name + '_grace', '센서 유예 중 권한 취소는 이전 음성과 owner를 즉시 폐기',
            'permission_boundary', events,
            [check(650, 'blocked_before_grace_end', kind=name),
             check(1200, 'owner_retired'), check(1300, 'no_false_arrival')],
            initial={'tts_done_delay_ms': 5000})
    add('permission_return_does_not_reuse_retired_callback', '권한 재허용 후에도 이전 수집·음성 콜백을 되살리지 않음',
        'permission_boundary', [dict(at_ms=300, type='permission', granted=False),
        dict(at_ms=700, type='permission', granted=True),
        dict(at_ms=900, type='tts', callback='DONE', utterance='first_route'), gps(1000), heading(1100)],
        [check(500, 'owner_retired'), check(1400, 'owner_retired'), check(1600, 'no_false_arrival')],
        initial={'tts_start_delay_ms': 1200, 'tts_done_delay_ms': 5000},
        note='재허용 시 신규 binding은 별도 기능일 수 있다. 이 사례는 하네스가 보유한 폐기된 기존 binding만 재사용하지 않는지 확인한다.')

    for start in [80, 1200]:
        events = [heading(300), gps(400, 40), heading(600, 180),
                  dict(at_ms=900, type='tts', callback='START', utterance='first_route'),
                  dict(at_ms=1000, type='tts', callback='DONE', utterance='first_route')]
        add(f'real_reversal_during_gps_grace_{start}', 'GPS 유예가 실제 180도 명령 반전의 무효화를 지연시키지 않음',
            'hard_reversal_boundary', events,
            [check(800, 'blocked_before_grace_end', kind='gps'), check(1500, 'no_false_arrival')],
            initial={'tts_start_delay_ms': start, 'tts_done_delay_ms': 5000})

    for name, east, north in [('right', 70, 1), ('left', -70, 1), ('toward_endpoint', 0, 500)]:
        events = healthy(3300, gps_times=[900, 1600, 1900, 2900]) + [gps(1000, north=north, east=east)]
        add('single_gps_jump_' + name, '단발 GPS 점프가 합류·진척·도착을 확정하지 않음',
            'spatial_outlier', events,
            [check(1200, 'no_false_arrival'), check(1800, 'no_false_arrival'),
             check(2600, 'route_done'), check(2800, 'no_route_stop')],
            note='ADMITTED 직선 route에서의 단발 점프만 검증한다. 모호한 분기·재탐색·실제 회전 통과의 증거는 아니다.')

    for phase in [0, 67, 149]:
        events = [heading(t + phase, degrees=[353, 3, 6, 356, 1, 359][i % 6])
                  for i, t in enumerate(range(300, 3001, 300))]
        events += [gps(t) for t in [820 + phase, 1950 + phase, 2800 + phase]]
        add(f'irregular_cadence_sway_{phase}', '불규칙 수신 위상과 359/0도·작은 좌우 sway',
            'cadence_and_sway', events,
            [check(1800, 'no_route_stop'), check(3300, 'route_done'), check(3300, 'no_false_arrival')])

    return dict(schema='walksafe.guidance_sensor_replay.v1', coordinate_frame='SYNTHETIC_LOCAL_ENU_METERS',
                sensor_trace_kind='GPS_AND_POST_ORIENTATION_HEADING',
                timeline_origin='FIRST_ROUTE_SPEECH_REQUEST_AFTER_CONTROLLED_INTRO_DONE', policy=POLICY,
                coverage_scope='ADMITTED_WALK_HEADING_OBSERVATION', full_home_to_arrival=False,
                open_requirements=['These supplemental sensor cases bootstrap an admitted walk.',
                                   'Readiness cases are a separate controlled production-controller phase.',
                                   'Physical RAW sensors/acoustic/field behavior and complete Android HOME remain unverified.'],
                cases=cases)


def base_runner():
    spec = importlib.util.spec_from_file_location('walksafe_base_replay', ROOT / 'scripts/replay_guidance_sensors.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def readiness_run(output):
    runner = base_runner()
    fingerprint = runner.product_fingerprint()
    def harness():
        data = runner.harness_fingerprint()
        for p in [TEST_SOURCE, Path(__file__).resolve()]:
            data[str(p.relative_to(ROOT))] = runner.digest(p)
        return data
    before = harness()
    output.mkdir(parents=True, exist_ok=False)
    init = output / 'readiness.init.gradle'
    init.write_text('''gradle.projectsEvaluated {
    gradle.rootProject.project(':app').tasks.withType(org.gradle.api.tasks.testing.Test).configureEach {
        if (name == 'testDebugUnitTest') {
            maxParallelForks = 1
            outputs.upToDateWhen { false }
            outputs.cacheIf { false }
            reports.junitXml.outputLocation.set(new File(System.getenv('WALKSAFE_SUPPLEMENTAL_OUTPUT'), 'junit'))
            reports.html.outputLocation.set(new File(System.getenv('WALKSAFE_SUPPLEMENTAL_OUTPUT'), 'html'))
            systemProperty('walksafe.fullJourneyReplay.outputDir', new File(System.getenv('WALKSAFE_SUPPLEMENTAL_OUTPUT'), 'traces').absolutePath)
        }
    }
}
''')
    env = dict(os.environ, ANDROID_HOME=os.environ.get('ANDROID_HOME', '/home/ddobagi/Android/Sdk'),
               WALKSAFE_SUPPLEMENTAL_OUTPUT=str(output))
    command = [str(ROOT / 'apps/android/gradlew'), '-p', str(ROOT / 'apps/android'), '--no-daemon',
               '--console=plain', '--max-workers=1', '--no-build-cache', '--init-script', str(init),
               '-Dorg.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g',
               '-Pkotlin.compiler.execution.strategy=in-process', '-Pkotlin.incremental=false',
               '-PWALKSAFE_GATEWAY_ORIGIN=https://online-nokia-webcams-crops.trycloudflare.com',
               '-Pwalksafe.guidanceStartBypass=false', '-PdevelopmentQuickStart=false',
               ':app:testDebugUnitTest', '--tests', TEST]
    lockpath = ROOT / 'work/part-a-four-sessions-20261001/gradle.lock'
    with lockpath.open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print('BUILD_BUSY: existing shared Gradle owner; no second build started', file=sys.stderr)
            return 3
        with (output / 'gradle.log').open('w') as log:
            result = subprocess.run(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
    reports = list((output / 'junit').glob('TEST-*.xml'))
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    failures = []
    for path in reports:
        suite = ET.parse(path).getroot()
        for k in totals:
            totals[k] += int(suite.attrib.get(k, 0))
        for case in suite.findall('testcase'):
            for failure in list(case.findall('failure')) + list(case.findall('error')):
                failures.append(dict(case=case.attrib.get('name'), type=failure.attrib.get('type'),
                                     message=failure.attrib.get('message', '')[:1800], xml=path.name))
    drift = fingerprint['source_id'] != runner.product_fingerprint()['source_id'] or before != harness()
    traces = list((output / 'traces').glob('supplemental_*.json'))
    verified = not drift and totals['tests'] == len(READINESS_IDS) and totals['skipped'] == 0
    verified = verified and {p.stem.removeprefix('supplemental_') for p in traces} == set(READINESS_IDS)
    green = verified and result.returncode == 0 and totals['failures'] == totals['errors'] == 0
    summary = dict(status='PASS_WITH_SCOPE_GAPS' if green else ('RED_REQUIRES_TRIAGE' if verified else 'EXECUTION_OR_ENVIRONMENT_ERROR'),
                   source_id=fingerprint['source_id'], source_or_harness_changed_during_run=drift,
                   harness_sources=before, totals=totals, failures=failures, command=command,
                   expected_cases=READINESS_IDS, execution_verified=verified,
                   coverage=dict(READY_CONTROLLER_TO_FIRST_SPEECH=True, ANDROID_HOME_VIEW=False,
                                 RAW_SENSOR_CONVERSION=False, FULL_HOME_TO_ARRIVAL=False, PHYSICAL_ACOUSTIC_FIELD=False))
    (output / 'product-source.json').write_text(json.dumps(fingerprint, indent=2) + '\n')
    (output / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(dict(status=summary['status'], output=str(output), **totals), ensure_ascii=False))
    return 0 if green else (1 if verified else 3)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--generate', action='store_true')
    parser.add_argument('--list', action='store_true')
    parser.add_argument('--phase', choices=['sensors', 'readiness', 'all'], default='all')
    parser.add_argument('--case', default='.*', help='Sensor IDs only; the readiness phase always runs its 12 scenarios.')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if args.generate:
        CASES.write_text(json.dumps(catalog(), ensure_ascii=False, indent=2) + '\n')
    if not CASES.exists():
        parser.error('Run --generate once to create the reviewed supplemental catalog.')
    runner = base_runner()
    _, selected = runner.catalog(CASES, args.case)
    if args.list:
        if args.phase in ['all', 'sensors']:
            for case in selected:
                print(case['id'], case['supplemental_family'], case['title_ko'], sep='\t')
        if args.phase in ['all', 'readiness']:
            for cid in READINESS_IDS:
                print(cid, 'READY_CONTROLLER_TO_FIRST_SPEECH', sep='\t')
        print(f'{len(selected)} sensor scenarios; {len(READINESS_IDS)} readiness scenarios; FULL_HOME_TO_ARRIVAL=false')
        return 0
    output = (args.output or Path('/home/ddobagi/.local/state/walksafe-development/guidance-supplemental-20261009') /
              datetime.now(ZoneInfo('Asia/Seoul')).strftime('run-%Y%m%dT%H%M%S-%f')).resolve()
    if output.exists():
        parser.error('Use a fresh output directory; original failures are preserved.')
    output.mkdir(parents=True)
    exits = []
    if args.phase in ['all', 'sensors']:
        exits.append(subprocess.run([sys.executable, str(ROOT / 'scripts/replay_guidance_sensors.py'),
            '--cases', str(CASES), '--case', args.case, '--phase', 'sensors', '--output', str(output / 'sensors')], cwd=ROOT).returncode)
        if exits[-1] not in [0, 1]:
            (output / 'readiness-not-run.json').write_text(json.dumps({
                'status': 'NOT_RUN', 'reason': 'Sensor phase could not verify execution; do not repeat the same shared compile/busy failure.',
            }, indent=2) + '\n')
            return exits[-1]
    if args.phase in ['all', 'readiness']:
        exits.append(readiness_run(output / 'readiness'))
    if args.phase == 'all':
        summaries = [json.loads((output / phase / 'summary.json').read_text()) for phase in ['sensors', 'readiness']]
        common = summaries[0]['harness_sources'].keys() & summaries[1]['harness_sources'].keys()
        coherent = (all(summary['execution_verified'] for summary in summaries) and
                    summaries[0]['source_id'] == summaries[1]['source_id'] and
                    all(summaries[0]['harness_sources'][path] == summaries[1]['harness_sources'][path]
                        for path in common))
        (output / 'combined-summary.json').write_text(json.dumps(dict(
            execution_verified=coherent, source_ids=[summary['source_id'] for summary in summaries],
            phases=[summary['status'] for summary in summaries],
            status=('PASS_WITH_SCOPE_GAPS' if coherent and max(exits) == 0 else
                    ('RED_REQUIRES_TRIAGE' if coherent else 'EXECUTION_OR_ENVIRONMENT_ERROR')),
            full_home_to_arrival=False), indent=2) + '\n')
        if not coherent:
            return 3
    return max(exits)


if __name__ == '__main__':
    raise SystemExit(main())

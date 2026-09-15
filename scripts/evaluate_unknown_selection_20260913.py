#!/usr/bin/env python3
"""Prepare fixed replay inputs and score real Kotlin policy outputs, entirely offline.

No selection policy is implemented here. Run UnknownSelectionFixedDataEvaluationTest
with walksafe.unknownSelection.input/output properties between prepare and score.
"""
from __future__ import annotations

import argparse
import base64
from collections import Counter
import hashlib
import json
from pathlib import Path


REPO = Path(__file__).resolve().parents[1]
DEFAULT_DATA = Path('/home/ddobagi/Documents/Codex/2026-09-08/new-chat-2/work/unknown-object-depth')
SYNTHETIC = REPO / 'apps/android/app/src/test/resources/unknown-selection/fixtures.json'
CONFIG = REPO / 'apps/android/app/src/main/assets/model-config/two_model_runtime.json'


def digest(path):
    with Path(path).open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def read(path):
    return json.loads(Path(path).read_text())


def write(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def records(path):
    with Path(path).open() as source:
        for line in source:
            if line.strip():
                yield json.loads(line)


def current_known_ids(annotations):
    config = read(CONFIG)
    primary = config['models'][config['primary_model']]
    allowed = set(primary['classes']) & set(primary['allowlist'])
    names = {name.replace('_', ' ') for name in allowed}
    if 'passenger_car' in allowed:
        names.add('car')
    ids = [category['id'] for category in annotations['categories'] if category['name'] in names]
    return sorted(ids), {
        'config': str(CONFIG), 'sha256': digest(CONFIG), 'primary_class_count': len(primary['classes']),
        'known_coco_category_ids': sorted(ids),
        'mapping_rule': 'Exact names after underscores become spaces; passenger_car maps to COCO car. No analogical mapping of non-COCO classes.',
    }


def packed_roi(mask):
    import numpy as np
    ys, xs = np.nonzero(mask)
    if not len(xs):
        return dict(left=0, top=0, width=0, height=0, base64='')
    x0, x1, y0, y1 = int(xs.min()), int(xs.max()) + 1, int(ys.min()), int(ys.max()) + 1
    bits = np.packbits(mask[y0:y1, x0:x1].reshape(-1), bitorder='little')
    return dict(left=x0, top=y0, width=x1-x0, height=y1-y0,
                base64=base64.b64encode(bits.tobytes()).decode('ascii'))


def prepare(args):
    from pycocotools import mask as mask_utils
    manifest = read(SYNTHETIC)
    defaults = manifest.pop('defaults')
    manifest['cases'] = [dict(defaults, **case) for case in manifest['cases']]
    sources = [SYNTHETIC, CONFIG]
    annotations_path = args.data / 'fixtures/coco_annotations.json'
    annotations = read(annotations_path)
    known, mapping = current_known_ids(annotations)
    sources.append(annotations_path)
    annotations_by_image = {}
    for annotation in annotations['annotations']:
        annotations_by_image.setdefault(annotation['image_id'], []).append(annotation)
    manifest['sourceClassMapping'] = mapping
    manifest['datasets'] = []
    for dataset in ('coco', 'rgbd'):
        model_name = f'{"rgbd_" if dataset == "rgbd" else ""}fastsam_{args.model_size}'
        cache = args.data / f'candidates/outputs/{model_name}/predictions.jsonl'
        sources.append(cache)
        image_count = proposal_count = 0
        for record in records(cache):
            image_count += 1
            image_id = record['image_id']
            image_path = (args.data / f'fixtures/images/{int(image_id):012d}.jpg' if dataset == 'coco'
                          else args.data / f'rgbd/frames/{image_id}/rgb.png')
            if digest(image_path) != record['image_sha256']:
                raise ValueError(f'Cached image hash does not match {image_path}')
            sources.append(image_path)
            if dataset == 'rgbd':
                sources.append(args.data / f'rgbd/frames/{image_id}/instance_gt.npy')
            for index, proposal in enumerate(record['proposals']):
                mask = mask_utils.decode(proposal['segmentation'])
                height, width = mask.shape
                # Readiness is a declared replay assumption, not a measured sequence.
                case = dict(defaults, id=f'{dataset}:{image_id}:{index}', label='annotated_object_recall_only',
                            dataset=dataset, imageId=image_id, proposalIndex=index, width=width, height=height,
                            source='UNKNOWN', distanceM=None, depthIqrM=None, validSampleCount=0, validSampleRatio=0,
                            fullCoverage=False, canRejectSmall=False, confidence=proposal['confidence'],
                            packedRoi=packed_roi(mask), imageSha256=record['image_sha256'])
                case.pop('rectangles', None)
                manifest['cases'].append(case)
                proposal_count += 1
        manifest['datasets'].append(dict(dataset=dataset, model=model_name, imageCount=image_count,
                                        proposalCount=proposal_count, predictionCache=str(cache)))
    noncrowd = [annotation for annotation in annotations['annotations'] if not annotation['iscrowd']]
    manifest['cocoGroundTruth'] = dict(
        noncrowd=len(noncrowd), known=sum(a['category_id'] in known for a in noncrowd),
        unknown=sum(a['category_id'] not in known for a in noncrowd),
        crowd=len(annotations['annotations']) - len(noncrowd))
    manifest['replayScope'] = (
        'Static cached original-resolution masks; quarterTurns=0 and repeated-observation readiness are fixed '
        'replay assumptions. Every real-image proposal has UNKNOWN depth and no metric extent. '
        'No synthetic metric units, intrinsics, trajectory or physical object completeness are assigned to photos.')
    manifest['dataRoot'] = str(args.data)
    manifest['sourceSha256'] = {str(path): digest(path) for path in dict.fromkeys(sources)}
    write(args.output, manifest)
    print(json.dumps(dict(input=str(args.output), sha256=digest(args.output), cases=len(manifest['cases']),
                          datasets=manifest['datasets'], groundTruth=manifest['cocoGroundTruth']), indent=2))


def synthetic_score(cases, by_id, policy):
    result = {}
    for label, field, expected in (
        ('keep_collision_relevant', 'falseNegatives', True),
        ('suppress_verified_nuisance', 'falsePositives', False),
        ('retain_uncertain', 'unexpectedDrops', True),
    ):
        group = [case for case in cases if case['label'] == label]
        failures = [case['id'] for case in group if by_id[case['id']][policy]['show'] != expected]
        result[label] = dict(count=len(group), **{field: len(failures)}, caseIds=failures)
    warning = [case for case in cases if 'expectedWarningCandidate' in case]
    result['warningContractFailures'] = [case['id'] for case in warning if
        by_id[case['id']][policy]['warningCandidate'] != case['expectedWarningCandidate']]
    controls = [case for case in cases if 'expectedShow' in case]
    result['controlFailures'] = [case['id'] for case in controls if
        by_id[case['id']][policy]['show'] != case['expectedShow']]
    return result


def match_counts(proposals, ground_truth, known_ids, retained):
    import numpy as np
    from pycocotools import mask as mask_utils
    candidates = [proposal for i, proposal in enumerate(proposals) if i in retained]
    targets = [annotation for annotation in ground_truth if not annotation['iscrowd']]
    candidates.sort(key=lambda item: -item['confidence'])
    matched = set()
    if candidates and targets:
        overlaps = mask_utils.iou([p['segmentation'] for p in candidates],
                                 [a['segmentation'] for a in targets], [0] * len(targets))
        for row in overlaps:
            remaining = [(float(value), index) for index, value in enumerate(row) if index not in matched and value >= .5]
            if remaining:
                matched.add(max(remaining, key=lambda item: (item[0], -item[1]))[1])
    crowd = [annotation for annotation in ground_truth if annotation['iscrowd']]
    counts = Counter(gt=len(targets), matched=len(matched), retained=len(candidates),
                     unmatchedProposalCount=len(candidates)-len(matched), crowdIgnoredTargets=len(crowd))
    for index, target in enumerate(targets):
        group = 'known' if target['category_id'] in known_ids else 'unknown'
        counts[group + 'Gt'] += 1
        counts[group + 'Matched'] += int(index in matched)
        if target.get('area', 1024) < 1024:
            counts[group + 'SmallGt'] += 1
            counts[group + 'SmallMatched'] += int(index in matched)
    return counts


def annotated_recall(manifest, by_id):
    import numpy as np
    from pycocotools import mask as mask_utils
    from pycocotools.coco import COCO
    data = Path(manifest['dataRoot'])
    coco = COCO(str(data / 'fixtures/coco_annotations.json'))
    known = manifest['sourceClassMapping']['known_coco_category_ids']
    report = {}
    for dataset in manifest['datasets']:
        kind = dataset['dataset']
        totals = {name: Counter() for name in ('unfiltered', 'baseline', 'current')}
        for record in records(dataset['predictionCache']):
            image_id = record['image_id']
            if kind == 'coco':
                truth = [dict(a, segmentation=coco.annToRLE(a)) for a in coco.imgToAnns[image_id]]
            else:
                labels = np.load(data / f'rgbd/frames/{image_id}/instance_gt.npy', allow_pickle=False)
                truth = [dict(id=int(label), category_id=-1, iscrowd=0, area=int((labels == label).sum()),
                              segmentation=mask_utils.encode(np.asfortranarray((labels == label).astype(np.uint8))))
                         for label in np.unique(labels) if label > 0]
            for policy in totals:
                selected = {i for i in range(len(record['proposals'])) if policy == 'unfiltered' or
                            by_id[f'{kind}:{image_id}:{i}'][policy]['show']}
                totals[policy].update(match_counts(record['proposals'], truth, known, selected))
        report[kind] = {policy: dict(total, recall50=total['matched']/total['gt'] if total['gt'] else None,
                                   unknownRecall50=total['unknownMatched']/total['unknownGt'] if total['unknownGt'] else None)
                        for policy, total in totals.items()}
        if kind == 'rgbd':
            for value in report[kind].values():
                for key in tuple(value):
                    if key.startswith(('known', 'unknown')):
                        del value[key]
        report[kind]['scope'] = (
            'Whole-image class-agnostic IoU >= 0.5, confidence-ranked greedy one-to-one matching. '
            'Crowd excluded from GT denominator. Unmatched proposals are NOT false positives. '
            'ROI filtering intentionally removes out-of-corridor annotations; losses are not walking-danger FNs. '
            'RGBD has no semantic class GT; no class novelty claim.')
    return report


def score(args):
    import numpy as np
    manifest = read(args.input)
    replay = read(args.replay)
    if replay['inputSha256'] != digest(args.input):
        raise ValueError('Replay input SHA-256 differs from scored manifest')
    cases = manifest['cases']
    by_id = {row['id']: row for row in replay['rows']}
    if len(by_id) != len(replay['rows']) or set(by_id) != {case['id'] for case in cases}:
        raise ValueError('Duplicate, missing or extra replay IDs')
    for path, expected in manifest.get('sourceSha256', {}).items():
        if digest(path) != expected:
            raise ValueError(f'Input changed after preparation: {path}')
    synthetic = {policy: synthetic_score(cases, by_id, policy) for policy in ('baseline', 'current')}
    changed = [dict(id=case['id'], label=case['label'], baseline=by_id[case['id']]['baseline'],
                    current=by_id[case['id']]['current']) for case in cases if
               any(by_id[case['id']]['baseline'][key] != by_id[case['id']]['current'][key]
                   for key in ('show', 'warningCandidate', 'reason'))]
    result = dict(schema='unknown-selection-paired-score-v1', inputSha256=digest(args.input),
                  replaySha256=digest(args.replay), synthetic=synthetic, changed=changed,
                  annotatedRecall=annotated_recall(manifest, by_id) if manifest.get('datasets') else {},
                  currentClassMapping=manifest.get('sourceClassMapping'),
                  deduplication='NOT_EVALUATED: this adapter invokes a single-candidate policy; pair fixtures only test individual retention.',
                  latencyScope=replay['timingScope'],
                  measuredCallsPerCase=replay['measuredCallsPerCase'])
    result['latencyNs'] = {policy: {
        'medianCaseP50': float(np.median([row[policy]['selectionP50Ns'] for row in by_id.values()])),
        'p95OfCaseP50': float(np.percentile([row[policy]['selectionP50Ns'] for row in by_id.values()], 95)),
        'p95OfCaseP95': float(np.percentile([row[policy]['selectionP95Ns'] for row in by_id.values()], 95)),
    } for policy in ('baseline', 'current')}
    result['limits'] = [
        'Synthetic FN/FP labels test declared selection contracts, not observed physical hazards.',
        'Cached masks test post-filter retention; the detector was not rerun.',
        'No physical metric-extent ground truth, complete obstacle annotation, walking path or true collision-time labels.',
        'Static replay readiness is assumed; temporal stability and end-to-end warning recall are unmeasured.',
        'Unknown means outside the current primary class mapping, not unseen in FastSAM training.',
    ]
    write(args.output, result)
    print(json.dumps(dict(report=str(args.output), synthetic=synthetic,
                          changedCaseCount=len(changed), annotatedRecall=result['annotatedRecall']), indent=2))


def plot(args):
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    import numpy as np
    report = read(args.score)
    fig, axes = plt.subplots(1, 3, figsize=(16, 5.5))
    colors = {'baseline': '#8897aa', 'current': '#087e8b'}
    labels = ['Hazard FN', 'Nuisance FP', 'Uncertain drops']
    for index, policy in enumerate(('baseline', 'current')):
        counts = report['synthetic'][policy]
        values = [counts['keep_collision_relevant']['falseNegatives'],
                  counts['suppress_verified_nuisance']['falsePositives'],
                  counts['retain_uncertain']['unexpectedDrops']]
        bars = axes[0].bar(np.arange(3) + (index - .5) * .35, values, .35, color=colors[policy], label=policy)
        axes[0].bar_label(bars, padding=3)
    axes[0].set_xticks(range(3), labels)
    axes[0].set_title('Synthetic selection contracts')
    axes[0].set_ylabel('Cases (lower is better)')
    axes[0].set_ylim(0, max(3, axes[0].get_ylim()[1] + .6))
    axes[0].legend(frameon=False)
    if report['annotatedRecall']:
        for index, policy in enumerate(('unfiltered', 'baseline', 'current')):
            values = [report['annotatedRecall']['coco'][policy]['unknownRecall50'] * 100,
                      report['annotatedRecall']['rgbd'][policy]['recall50'] * 100]
            bars = axes[1].bar(np.arange(2) + (index - 1) * .25, values, .25,
                               label=policy, color='#d3a646' if policy == 'unfiltered' else colors[policy])
            axes[1].bar_label(bars, fmt='%.1f', padding=3, fontsize=8)
        axes[1].set_xticks(range(2), ['COCO outside primary', 'RGB-D all instances'])
        axes[1].set_ylim(0, 110)
        axes[1].set_ylabel('Whole-image annotated mask R50 (%)')
        axes[1].set_title('Cached masks; no metric extent')
        axes[1].legend(frameon=False, fontsize=8)
    timing = report['latencyNs']
    values = [timing[policy]['medianCaseP50'] / 1000 for policy in ('baseline', 'current')]
    bars = axes[2].bar(['baseline', 'current'], values, color=list(colors.values()), width=.5)
    axes[2].bar_label(bars, fmt='%.2f', padding=3)
    axes[2].set_ylabel('Microseconds per select call')
    axes[2].set_ylim(0, max(values) * 1.25)
    axes[2].set_title('Host JVM median of case medians')
    for ax in axes:
        ax.spines[['top', 'right']].set_visible(False)
        ax.grid(axis='y', alpha=.2)
        ax.set_axisbelow(True)
    fig.suptitle('Fixed-input selection audit: baseline 1509a19 and current production policy', fontsize=14)
    fig.text(.5, .02, 'Synthetic labels are not field hazard GT. ROI losses are not danger FNs. '
             'Timing includes reflection; excludes model, camera, depth and phone runtime.', ha='center', fontsize=9)
    fig.tight_layout(rect=(0, .09, 1, .94))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(args.output, dpi=180)
    plt.close(fig)
    print(args.output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest='command', required=True)
    prepare_parser = subparsers.add_parser('prepare')
    prepare_parser.add_argument('--data', type=Path, default=DEFAULT_DATA)
    prepare_parser.add_argument('--model-size', type=int, choices=(640, 768), default=768)
    prepare_parser.add_argument('--output', type=Path, required=True)
    score_parser = subparsers.add_parser('score')
    score_parser.add_argument('--input', type=Path, required=True)
    score_parser.add_argument('--replay', type=Path, required=True)
    score_parser.add_argument('--output', type=Path, required=True)
    plot_parser = subparsers.add_parser('plot')
    plot_parser.add_argument('--score', type=Path, required=True)
    plot_parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    {'prepare': prepare, 'score': score, 'plot': plot}[args.command](args)


if __name__ == '__main__':
    main()

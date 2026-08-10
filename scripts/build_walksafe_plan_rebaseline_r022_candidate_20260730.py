#!/usr/bin/env python3
"""Build the reviewed, non-effective WalkSafe r022 Gap/Backlog candidate pair.

The generated files are staged under the rebaseline plan directory.  They are
not canonical, do not update the checkpoint or Goal graph, and do not authorize
product implementation or a release claim.
"""

from __future__ import annotations

import argparse
import copy
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
GENERATOR_PATH = Path(__file__).resolve()
PLAN_ROOT = REPO_ROOT / "plans/features/2026-07-29_walksafe_plan_rebaseline_r001"
R001_OUTPUT_DIR = PLAN_ROOT / "r022-candidate"
R001_PAIR_MANIFEST_PATH = R001_OUTPUT_DIR / "gap-backlog-pair-manifest.json"
OUTPUT_DIR = PLAN_ROOT / "r022-candidate-r002"

R021_GAP_PATH = (
    REPO_ROOT
    / "docs/control/audits/walksafe-implementation-gap-analysis-20260726-r021.json"
)
R021_BACKLOG_PATH = (
    REPO_ROOT
    / "docs/control/audits/walksafe-implementation-remediation-backlog-20260726-r021.json"
)
PLAN_MANIFEST_PATH = PLAN_ROOT / "plan-manifest.json"
PLAN_CANDIDATES_PATH = PLAN_ROOT / "gap-reassessment-candidates.json"
PLAN_EVIDENCE_PATH = PLAN_ROOT / "gap-evidence-bindings.json"
STATIC_PLAN_PATH = (
    REPO_ROOT
    / "docs/control/goals/walksafe-completion-graph-v2-4/"
    "static-plan-manifest-v2.4.0.json"
)

LEDGER_PATH = OUTPUT_DIR / "exact68-reassessment-ledger.json"
GAP_CANDIDATE_PATH = OUTPUT_DIR / "implementation-gap-r022.candidate.json"
BACKLOG_CANDIDATE_PATH = OUTPUT_DIR / "implementation-backlog-r022.candidate.json"
PAIR_MANIFEST_PATH = OUTPUT_DIR / "gap-backlog-pair-manifest.json"

INTENDED_GAP_PATH = (
    "docs/control/audits/"
    "walksafe-implementation-gap-analysis-20260726-r022.json"
)
INTENDED_BACKLOG_PATH = (
    "docs/control/audits/"
    "walksafe-implementation-remediation-backlog-20260726-r022.json"
)

PREPARED_AT = "2026-07-30T04:05:00+09:00"
EXPECTED_HEAD = "a3ad7eead6b5d834d3e0675422475a9aad351e3d"
EXPECTED_R001_PAIR_MANIFEST = (
    "aaf3885fc98a28654f40e1093e2f353cf26279fa17e3e24ae8ab512c0b336a20",
    10606,
)
EXPECTED_MAPPING_SHA256 = (
    "3423815d07d130ab85c6729733b26c7061ae13f63b4f344dfecdc1c47c4f80b5"
)
EXPECTED_HARD_DEPENDENCY_SHA256 = (
    "f57daf2de778e697b1983a1271d2bc5902b0f9e581982b1cb4e3d2349ca86535"
)
EXPECTED_SOURCE_BINDINGS = {
    R021_GAP_PATH: (
        "f2e304679c5c3dfd3d7331340039e7222ab9e3915ede30de673f60f1b2aca97a",
        488160,
    ),
    R021_BACKLOG_PATH: (
        "bcc4561ead39e1d659222f54161c0e063bd41fbf8b60c0a2b144a4535143c6a0",
        59266,
    ),
    PLAN_MANIFEST_PATH: (
        "43ec2fb64590aac4421e3bafa9178b9e7316a103f944c0f06df30be8dc5493f4",
        14669,
    ),
    PLAN_CANDIDATES_PATH: (
        "695e8888900ef82ecb4c5eb5d7f92da8734aec55f4ea3b183668b490899d9f2d",
        34765,
    ),
    PLAN_EVIDENCE_PATH: (
        "2520473f10232bf41bc41ba5b9e7441953a9d64f24285e3ec55d444cd2ee400a",
        7362,
    ),
    STATIC_PLAN_PATH: (
        "7325de1f413423dff7c19390b85b489c981f46511464ca81969e226ca8908b07",
        39534,
    ),
}

STATUS_CHANGES = {
    "GAP-008": "PARTIAL",
    "GAP-017": "PARTIAL",
    "GAP-036": "PARTIAL",
    "GAP-038": "PARTIAL",
    "GAP-044": "PARTIAL",
    "GAP-052": "PARTIAL",
    "GAP-054": "PARTIAL",
    "GAP-055": "CONFLICTING",
}

REPLACEMENT_CURRENT_TEXT = {
    "GAP-002": (
        "Android에는 9개 삭제 항목과 상태기계가 있고 Gateway에도 삭제 권리 원장이 있다. "
        "그러나 Android와 Gateway는 secret header, POST·GET 필드, 상태 응답과 항목 "
        "형식이 달라 현재 직접 연동되지 않는다. Gateway 항목은 EXTERNAL_PENDING으로 "
        "시작해 외부 evidence transition만 받으며 구체 저장소 worker·backup 재적용 "
        "호출은 이 권리 orchestration에 연결되어 있지 않다."
    ),
    "GAP-004": (
        "Wi-Fi·이동통신망 선택 정책은 존재하지만 암호화 영속 대기열은 비활성이다. "
        "MainActivity의 persistent queue flag는 false이고 AndroidPendingReportStore는 "
        "대기자료 메타데이터 저장소가 아니라 과거 암호문과 열쇠를 열지 않고 제거하는 "
        "정리 전용 경계다."
    ),
    "GAP-005": (
        "항목별 동의 상태는 영속화되지만 활성 보행 중 MainActivity가 신고를 직접 "
        "전송한다. 보행 종료 뒤 암호화 영속 대기열에서 허용 망으로 전송해야 한다는 "
        "후보 정책과 반대 동작이 남아 있다."
    ),
    "GAP-006": (
        "로그인·동의·망 선택·권한 상태가 분리되어 있고 카메라 또는 정확 위치 철회는 "
        "전체 보행 안전정지로 연결된다. 다만 실제 기기의 철회·재시작·만료 흐름과 "
        "외부 권리 요청 운영 및 정식 시험은 실행되지 않았다."
    ),
    "GAP-008": (
        "사용자 앱과 별도 applicationId의 관리자 앱이 있으며 password+TOTP, replay "
        "거부, 기기 결합 세션, 원격 세션 폐기, 외부 복구 코드와 복구 중 고위험 동결 "
        "코어가 구현되어 있다. production 자격·복구재료 provisioning, 휴대전화 밖 "
        "보관, 운영 workflow와 실제 분실 복구훈련은 완료되지 않았다."
    ),
    "GAP-016": (
        "사용자 앱과 관리자 앱은 서로 다른 Android 모듈과 applicationId로 분리되어 "
        "있고 core 시작 capability의 fail-closed 검사도 존재한다. LIMITED tier는 시작 "
        "가능하므로 이 검사는 production 승인 전체를 뜻하지 않는다. production signing, "
        "승인 기기 목록, 분리 배포 채널과 실제 Gateway 배포 검증은 완료되지 않았다."
    ),
    "GAP-017": (
        "별도 관리자 Android 앱에서 password+TOTP 로그인, 세션 폐기, 복구와 고위험 "
        "재확인 binding을 제공하고 controller가 pending nonce를 판정 전에 선소비한다. "
        "신고 검수·반려·중복처리·기관 제출·수신증·사용자 피드백 업무는 잠겨 있거나 "
        "구현되지 않았고 실제 서명·배포도 실행되지 않았다."
    ),
    "GAP-021": (
        "Gateway field-walk ledger가 계정별 단일 active lease, fencing, idempotency와 "
        "음성 확인 takeover를 구현하고 Android 흐름에 연결되어 있다. 다만 현재 "
        "single-host 파일 원장 경계를 넘어서는 운영 topology와 실제 다중 기기 검증은 "
        "완료되지 않았다."
    ),
    "GAP-022": (
        "Android에는 네 항목의 process-local 동의·철회 상태가 있고 Gateway에는 "
        "receipt-chain event 원장이 구현되어 있다. 그러나 실제 고지는 영상·음성·경로·"
        "성능 등 전체 원자료 범주와 비식별 전 얼굴·번호판·음성, 제공·삭제 방법을 모두 "
        "설명하지 못하고 보호자·법률 승인도 남아 있다."
    ),
    "GAP-023": (
        "권한별 의존 기능 중지와 카메라·정확 위치 상실의 전체 안전정지, 전체 재검사 뒤 "
        "명시적 재개가 MainActivity에 연결되어 있다. 실제 Android 권한 만료·설정 변경 "
        "및 화면읽기·정식 검증은 실행되지 않았다."
    ),
    "GAP-025": (
        "release 활성·일시중지 보행 화면은 카메라 중심 읽기 전용 안전 overlay와 "
        "뒤로가기 선행 일시중지를 제공한다. 정적 검사 소스는 존재하지만 voice-only "
        "전체 조작이나 실제 TalkBack·사용자·실기기 검증은 확인되지 않았다."
    ),
    "GAP-028": (
        "TFLite detector가 tracking·depth pipeline과 MainActivity에 연결되고 terminal "
        "detector 실패는 안전정지된다. 그러나 배포 부적격 후보 모델과 낮은 class별 "
        "threshold가 안전·신고 경로에서 활성화되어 승인 정책 충돌이 남아 있다."
    ),
    "GAP-030": (
        "밝기·가림·흔들림·각도 품질정책과 승인 기기 exact-match 경계가 startup/runtime에 "
        "연결되어 있다. 그러나 production profile과 실제 관측값 일부는 비어 있는 반면, "
        "승인 모델 0건인 unified 후보와 명시된 class별 0.15~0.35 threshold가 runtime에서 "
        "활성화되어 승인 전 탐지 금지 정책과 반대다."
    ),
    "GAP-034": (
        "단말 내 음성인식 capability와 on-device recognizer는 존재하지만 일반 command는 "
        "'음성 명령' 버튼을 눌러 시작한다. 활성 보행 전용 '길라잡이' 호출어와 듣기 "
        "시작·종료 신호, 승인된 command lifecycle은 구현되지 않았다."
    ),
    "GAP-036": (
        "오프라인 한국어 TTS가 시작 필수조건이고 초기화·runtime 음성 실패는 "
        "MainActivity의 전체 보행 안전정지와 보이는 상태·TalkBack 안내로 연결된다. "
        "제한 재시도, 전용 actionable 실패 화면·진동과 WARNING·중단·STOP을 구분하는 "
        "촉각 의미 및 실기기 검증은 남아 있다."
    ),
    "GAP-037": (
        "일부 필수 화면에는 heading, contentDescription, 48dp 조작, 명시적 읽기·키보드 "
        "순서와 TalkBack 알림이 구현되어 있다. 로그인 오류·동의·계정 삭제를 포함한 "
        "전체 화면 완결성과 실제 목표 사용자 검증은 없다."
    ),
    "GAP-038": (
        "권한별 영향과 초점, 종료·설정 행동, 전체 재검사 뒤 명시적 재개, detector 반복 "
        "실패 안전정지 경로가 존재한다. 다만 동의·startup·detector 등 모든 지속 장애가 "
        "하나의 접근 가능한 안전정지 화면으로 통합되지는 않았다."
    ),
    "GAP-039": (
        "우선 사용자 화면에 48dp 조작, font scale·고대비 감지, 색 이외의 선택 설명과 "
        "명시적 초점 순서가 있다. 전체 필수 화면의 확대 reflow·대비·스위치 조작과 실제 "
        "사용자 검증은 완료되지 않았다."
    ),
    "GAP-040": (
        "손상 class·반복 관측·신뢰도·GPS·모델 후보 gate는 존재한다. 그러나 활성 보행 "
        "중 후보를 직접 전송하고 영속 대기열이 비활성이라, 보행 후 암호화 대기·병합·"
        "삭제 정책과 충돌한다."
    ),
    "GAP-041": (
        "MainActivity에 요청 1회의 trace와 retry/backoff 상태가 있고 backend에는 replay와 "
        "신고 image/DB commit 경계, 별도 임시파일 reconciliation이 있다. 그러나 활성 "
        "보행에서 upload call을 즉시 실행하고 AndroidPendingReportStore는 cleanup-only라 "
        "승인된 durable-queue 우선 정책과 반대다. payload 영속화·재부팅 drain·"
        "status-first 복구도 없다."
    ),
    "GAP-042": (
        "별도 adminapp과 backend 신고 조회·상태·history·agency export surface가 있다. "
        "그러나 adminapp 운영 workflow는 비활성이고 검수·기각·실제 기관 제출·수신증·"
        "사용자 피드백 상태기계가 완결되지 않았다."
    ),
    "GAP-044": (
        "활성 보행의 움직임·정지, Wi-Fi, 이동통신망 명시 동의, unknown fail-closed와 "
        "움직임 재개 취소가 MainActivity 전송 경계에 연결되어 있다. release 원본 "
        "collector와 암호화 영속 대기열·재부팅 복구·수신확인 lifecycle은 없다."
    ),
    "GAP-045": (
        "신고 전용 image journal, 임시파일 복구와 180일 retention 경로는 존재한다. "
        "그러나 활동원본의 exact ID·크기·SHA 수신확인 뒤 단말 삭제와 14일·3년·35일 "
        "저장구역, 철회·백업 삭제 연쇄는 구현되지 않았다."
    ),
    "GAP-049": (
        "Gateway는 제한 route/method를, backend 관리자 서비스는 app-kind·role·audience·"
        "device를 검사하고 report 경로에는 개별 replay·잠금이 있다. 현재 결속 근거만으로는 "
        "모든 서버 기능의 단일 출입구, 모든 write의 idempotency, 예외 승인과 workload "
        "isolation 완료를 입증하지 않는다."
    ),
    "GAP-052": (
        "배터리·저장공간·발열 resource probe와 음성 runtime 실패가 활성 보행의 중앙 "
        "안전정지에 연결된다. 위치·경로 freshness를 포함한 전체 failure matrix와 "
        "일관된 회복·사용자 확인 상태기계는 완결되지 않았다."
    ),
    "GAP-053": (
        "보행 상태·망 선택 통제 일부가 존재하지만 AndroidPendingReportStore는 "
        "metadata store가 아니라 purge-only다. 암호화 30일 queue·용량·재부팅 worker가 "
        "없고 자동 reroute도 승인된 사용자 선택 흐름과 충돌한다."
    ),
    "GAP-054": (
        "Android resource probe가 저배터리·저장공간·critical thermal을 관찰하고 활성 "
        "보행 안전정지에 연결한다. 연속 수치 계측, 승인된 기기별 battery·storage·thermal "
        "한계와 고정 열화 순서, 장시간 실기기 근거는 없다."
    ),
    "GAP-055": (
        "Android 삭제 client와 Gateway 삭제 ledger가 각각 존재하지만 secret header, "
        "POST·GET 입력, 응답 필드와 item 상태 형식이 서로 달라 현재 요청은 직접 "
        "호환되지 않는다. Gateway item도 EXTERNAL_PENDING에서 evidence transition을 "
        "받는 경계이므로 concrete 저장소 삭제·backup tombstone 재적용 완료를 뜻하지 않는다."
    ),
    "GAP-061": (
        "관리자 인증·복구와 Gateway 실패의 versioned telemetry producer가 각각 "
        "Logcat·file sink event를 만든다. 운영 정본은 dashboard와 alert를 "
        "PLANNED/NOT_RUN으로 명시하므로, 이 producer 경로만으로 영속 collector, 전체 "
        "기능 dashboard·trace, 서로 분리된 안전·운영 경보 경로와 on-call 연결의 "
        "실행 완료를 주장할 수 없다."
    ),
    "GAP-062": (
        "암호화·서명 backup/restore 도구와 기본 35일·최소 3개 유지·dry-run prune "
        "명령이 존재한다. 자동 schedule은 없고 실제 backup·restore 실행은 0건이며 "
        "RPO/RTO는 실측 전 미정·NOT_APPROVED다."
    ),
    "GAP-063": (
        "maintenance 배타 잠금 중 report write fail-close와 구조화된 종료·자료·계정·키 "
        "lifecycle 문서가 있다. 현재 effective policy candidate는 변경 실패 때 rollback "
        "없이 신규 세션 차단과 안전정지를 유지하는 forward-fix를 요구하지만, 전 계층 "
        "drain·safe pause·reconcile·provider 종료의 통합 실행과 승인 receipt는 없다."
    ),
}

REPLACEMENT_REMEDIATION = {
    "GAP-002": "Android와 Gateway 삭제 wire contract를 하나로 통합하고 저장소별 보존·삭제 worker, 재시도, 영수증과 backup tombstone 재적용을 연결한다.",
    "GAP-004": "암호화 durable payload queue, 실제 byte 상한, 우선순위 eviction·hold, 재부팅 worker와 서버 ACK 뒤 삭제를 구현한다.",
    "GAP-005": "활성 보행 direct upload를 제거하고 종료 후 암호화 durable queue, 고정 신고 ID, 상태조회·수신확인·삭제 lifecycle로 전환한다.",
    "GAP-006": "권한·세션 구현을 운영 privacy-rights 경로와 현재 evidence binding에 연결하고 승인 기기 lifecycle 검증을 준비한다.",
    "GAP-008": "production 관리자·복구재료를 provision하고 휴대전화 밖 암호화 custody와 action-bound 운영 recovery workflow를 연결한다.",
    "GAP-016": "사용자·관리자 앱의 production signing, release identity, 승인 기기 목록과 분리 배포 채널을 완성한다.",
    "GAP-017": "관리자 앱의 신고 검수·반려·중복처리, 기관 제출·수신증·상태·감사 workflow와 등록 기기 enforcement를 구현한다.",
    "GAP-021": "field-walk ledger의 운영 lease profile과 배포 topology를 승인하고 multi-host일 때 공유 transactional coordinator로 전환한다.",
    "GAP-022": "전체 원자료 범주·목적·시점·보존·제공·삭제 고지와 guardian/user receipt를 일치시키고 승인 gate에 연결한다.",
    "GAP-023": "현재 권한별 중단·재개 gate를 production lifecycle과 승인 permission profile에 결속하고 실제 기기 검증 입력을 준비한다.",
    "GAP-025": "release 보행 화면의 필수 조작을 voice-only와 접근 가능한 종료 확인으로 완결하고 실제 TalkBack 검증 경계를 고정한다.",
    "GAP-028": "배포 적격 모델·class allowlist·threshold만 안전·신고 경로에 허용하고 관측 연결 불확실성과 terminal failure 계약을 봉인한다.",
    "GAP-030": "production camera 품질·기기 profile과 실제 측정값을 승인 모델·종류별 거리 gate에 결속한다.",
    "GAP-034": "활성 보행 전용 '길라잡이' wake-word, 듣기 시작·종료 cue와 승인 command lifecycle을 on-device recognition에 연결한다.",
    "GAP-036": "오프라인 TTS 제한 재시도, 실패 화면·진동과 WARNING·중단·STOP별 촉각 의미를 완결한다.",
    "GAP-037": "로그인·오류·동의·권한·보행·복구·종료·삭제 화면의 heading, 오류 초점, 읽기·키보드 순서를 완결한다.",
    "GAP-038": "동의·startup·detector 지속 장애를 공통 접근 가능 안전정지 화면과 문제별 종료·설정·재검사·명시 재개 행동으로 통합한다.",
    "GAP-039": "전체 사용자 화면의 글자 확대 reflow, 대비, 터치영역, 외부 키보드·스위치 순서와 상태 설명을 완결한다.",
    "GAP-040": "반복 관측 후보를 보행 후 암호화 queue로 옮기고 공간 병합·관찰 추가·중지·철회·삭제 정책을 구현한다.",
    "GAP-041": "암호화 payload persistence, 재시도·재부팅 간 stable ID, status-first 복구, 30일 만료와 우선순위 drain을 구현한다.",
    "GAP-042": "adminapp 신고 목록·상세·기각, 기관 제출·수신증·결과와 사용자 정정 피드백 상태기계를 완결한다.",
    "GAP-044": "release 원본 collector와 암호화 durable queue, 재부팅 복구, 30일 만료와 chunk hash 수신확인을 현재 admission 경계에 연결한다.",
    "GAP-045": "활동원본 exact ID·크기·SHA receipt 뒤 단말 삭제와 14일·180일·3년·35일 저장구역, 철회·backup 삭제 연쇄를 구현한다.",
    "GAP-049": "공통 관리자 진입점, 예외 승인, 전 write trace·idempotency와 프로세스 간 안전·대용량 자원 격리를 완성한다.",
    "GAP-052": "위치·경로 freshness를 포함한 기능별 failure matrix와 공통 backoff, 안정 회복·사용자 확인·감사 상태기계를 완결한다.",
    "GAP-053": "암호화 30일 queue, 실제 capacity·idempotency·reboot worker를 구현하고 사용자 선택 없는 자동 reroute를 제거한다.",
    "GAP-054": "battery consumption·free storage·queue bytes·frame age·detect-to-speech를 계측하고 기기별 경고·정지 임계값과 열화 순서를 승인한다.",
    "GAP-055": "Android·Gateway의 header·query·request·status schema를 단일화하고 저장소별 삭제 worker, retry·receipt, 보존·backup tombstone 재적용을 연결한다.",
    "GAP-061": "영속 telemetry collector·retention, 전 기능 dashboard·metric·trace, 안전·운영 이중 alert·safe-stop과 on-call workflow를 구현한다.",
    "GAP-062": "완전한 암호화 backup bundle과 deletion ledger·key version을 묶고 자동 backup·35일 prune schedule, capacity·cost 계측과 RPO/RTO를 승인한다.",
    "GAP-063": "전 계층 drain, 사용자 고지·safe pause, 신규 세션 차단과 pending data 정합, 변경 실패 시 rollback 없는 forward-fix·기능 안전정지, provider·data·key 종료를 통합한다.",
}

DECISION_REASON = {
    "GAP-001": "release logger와 frame uploader가 noop이고 승인 원자료 수집 경로가 없다.",
    "GAP-002": "삭제 상태 구현은 있으나 Android와 Gateway wire contract가 불일치하고 Gateway 원장은 concrete deletion effect 대신 EXTERNAL_PENDING 외부 transition 경계에 머문다.",
    "GAP-003": "리포트 저장과 readiness에 원본 용량·비용·70/85/95/100 상태 경로가 없다.",
    "GAP-004": "망 분기는 존재하지만 durable byte queue는 비활성이고 store는 cleanup-only다.",
    "GAP-005": "영속 동의 진전과 별개로 활성 보행 direct upload가 후보 정책과 반대다.",
    "GAP-006": "상태 분리와 권한별 중단은 구현됐으나 실제 lifecycle·운영·정식 증거는 없다.",
    "GAP-007": "자동 도착과 사용자 선택 없는 reroute가 승인 정책과 반대다.",
    "GAP-008": "별도 admin app과 인증·복구 코어가 생겼으나 production provisioning과 실제 drill은 없다.",
    "GAP-009": "capacity version·observed_at·TTL API와 Android 동기화 경로가 없다.",
    "GAP-010": "제품 목적·안전 경계와 로컬 안내 코드는 있으나 실기기·사용자·정식 검증이 없다.",
    "GAP-011": "stage·immutable bundle 규칙은 있으나 승인 RC가 없고 formal 0/279, gate 5개가 NOT_RUN이다.",
    "GAP-012": "관리자 인증·폐기·복구 코어는 있으나 운영 workflow·custody·배포·drill이 남아 있다.",
    "GAP-013": "우선 사용자 정책 reducer는 있으나 guardian provider와 사용자·실기기 검증이 없다.",
    "GAP-014": "환경 안전 상태기계는 있으나 실제 측정 입력과 승인 production profile이 없다.",
    "GAP-015": "장착 정책 골격은 있으나 실측 기반 생산 기준과 실제 관측값이 없다.",
    "GAP-016": "모듈·제품 ID 분리는 반영해야 하지만 signing·승인기기·배포 증거가 없다.",
    "GAP-017": "별도 admin 제품은 있으나 실제 신고 운영 workflow와 배포가 없어 PARTIAL이 상한이다.",
    "GAP-018": "기기 분류와 legacy 내부 폐쇄는 있으나 승인 기기·외부 폐기 증거가 없다.",
    "GAP-019": "onboarding 상태기계는 있으나 production evidence verifier와 공급자 연결이 없다.",
    "GAP-020": "장기 세션 코어는 있으나 release 비활성이고 운영 만료·재인증 경계가 미완성이다.",
    "GAP-021": "단일 active-walk ledger는 구현됐으나 운영 topology와 실제 다중기기 증거가 없다.",
    "GAP-022": "Gateway receipt ledger와 Android process-local session은 있으나 실제 disclosure 범위가 정책보다 좁고 외부 승인이 없다.",
    "GAP-023": "권한별 중지·재개 gate는 구현됐으나 실기기·플랫폼 검증이 없다.",
    "GAP-024": "삭제 정책·상태 원장은 있으나 실제 저장소 삭제 실행기가 연결되지 않았다.",
    "GAP-025": "release 화면 핵심은 있으나 voice-only 전체 흐름과 실제 TalkBack 근거가 없다.",
    "GAP-026": "lifecycle 충돌은 제거됐지만 시작 readiness 전체 계약과 실기기 증거가 미완성이다.",
    "GAP-027": "상태·복구 제어는 있으나 정책 전체와 정식 lifecycle 증거가 미완성이다.",
    "GAP-028": "detector-depth 연결은 있으나 미승인 모델·threshold의 실제 사용이 정책과 충돌한다.",
    "GAP-029": "WARNING 진동 부재와 STOP 행동문 불일치가 직접적인 정책 반대 동작이다.",
    "GAP-030": "품질 gate 일부와 별개로 승인 모델 0건인 후보·낮은 class별 threshold가 runtime에 활성화되어 승인 전 탐지 금지 정책과 충돌한다.",
    "GAP-031": "사용자 확인 전에 자동 도착·길안내 종료가 일어나 정책과 충돌한다.",
    "GAP-032": "이탈 뒤 사용자 선택 전에 자동 TMAP 재요청이 일어나 정책과 충돌한다.",
    "GAP-033": "후보 구현 방향은 맞지만 승인 모델·환경의 실행 증거가 없다.",
    "GAP-034": "on-device recognition은 있으나 wake-word와 승인 listening lifecycle이 없다.",
    "GAP-035": "parser는 있으나 pause·resume·end와 비가역 확인 계약이 없다.",
    "GAP-036": "음성 실패의 안전정지·상태·TalkBack 연결로 기존 충돌은 제거됐지만 전용 actionable 실패 UI·촉각 의미·실기기 근거가 남아 있다.",
    "GAP-037": "접근성 구현은 진전됐으나 전체 필수 화면과 실제 사용자 증거가 없다.",
    "GAP-038": "접근 가능한 거부·복구 핵심은 있어 MISSING이 아니지만 모든 장애 통합은 미완성이다.",
    "GAP-039": "우선 사용자 UI는 진전됐으나 전체 화면·스위치·실제 사용자 검증이 없다.",
    "GAP-040": "후보 gate와 별개로 활성 보행 direct upload가 승인 queue 정책과 충돌한다.",
    "GAP-041": "retry metadata, backend replay·commit 경계와 별도 파일 reconciliation은 있지만 활성 보행 즉시 upload 구조가 durable-queue 우선 정책과 충돌하고 payload·reboot 복구가 없다.",
    "GAP-042": "별도 adminapp과 backend surface는 있으나 기관 receipt까지의 workflow가 미완성이다.",
    "GAP-043": "문서 workflow는 있으나 release 원본 취득 경로는 no-op이다.",
    "GAP-044": "정지·망 admission 통합은 있으나 release collector·durable queue·receipt lifecycle이 없다.",
    "GAP-045": "신고 journal·retention은 있으나 활동원본 receipt→단말삭제→다중구역 lifecycle은 없다.",
    "GAP-046": "배포 부적격 모델을 runtime 안전·신고 경로에서 사용해 registry 정책과 충돌한다.",
    "GAP-047": "기본 integrity 도구는 있으나 승인 재학습·잠긴 독립평가 pipeline이 없다.",
    "GAP-048": "known-good generation 없이 자동 legacy fallback을 실행해 rollback 정책과 충돌한다.",
    "GAP-049": "현재 결속 근거만으로 모든 서버 기능 단일 출입구·모든 write idempotency·예외 승인·workload isolation 완료를 입증할 수 없다.",
    "GAP-050": "local crash reconciliation은 있으나 DB↔암호화 object store 계약은 아니다.",
    "GAP-051": "secret·admission 일부는 있으나 durable queue·전 write idempotency·비용 통제가 없다.",
    "GAP-052": "resource·speech 안전정지는 있으나 전체 failure/recovery matrix가 미완성이다.",
    "GAP-053": "망 통제 일부와 별개로 durable queue가 없고 자동 reroute가 정책과 충돌한다.",
    "GAP-054": "resource fail-close가 있어 MISSING은 낡았으나 수치·임계값·열화순서·장시간 증거가 없다.",
    "GAP-055": "Android와 Gateway 삭제 wire protocol이 직접 불일치해 현재 연결이 동작할 수 없다.",
    "GAP-056": "내부 auth core는 r021에 반영됐고 운영·외부·정식 검증 경계를 넘을 근거가 없다.",
    "GAP-057": "build·transport 통제 일부는 있으나 저장암호화·KMS·감사·사고대응 검증이 미완성이다.",
    "GAP-058": "동일 immutable RC에 결속된 정식 실행·인수 증거가 없다.",
    "GAP-059": "좁은 smoke workflow는 전체 성능·현장 사용자 시험 결과가 아니다.",
    "GAP-060": "build provenance 일부는 있으나 현행 immutable RC와 실제 update·rollback·store 배포가 없다.",
    "GAP-061": "telemetry producer는 늘었지만 종단간 모니터링·경보·on-call 운영체계가 없다.",
    "GAP-062": "35일 prune·backup 도구는 있으나 자동 운용과 실제 복구 증거가 없다.",
    "GAP-063": "maintenance·종료 schema와 no-rollback forward-fix 정책은 있으나 통합 배포·종료 실행과 승인이 없다.",
    "GAP-064": "암호화 bounded queue와 승인된 실제 byte cap이 없어 gate를 실행할 수 없다.",
    "GAP-065": "정책 숫자는 있으나 서버 용량상태 계약과 단말 전파·차단 동작이 없다.",
    "GAP-066": "내부 동의 로직은 독립 privacy/legal release review를 대체하지 못한다.",
    "GAP-067": "정책 예산만 있고 대표 workload·restore의 실제 cloud bill 측정이 없다.",
    "GAP-068": "내부 복구 구현과 절차 초안은 실제 휴대전화 분실 복구훈련을 대체하지 못한다.",
}

EVIDENCE = {
    "GAP-001": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/fieldlog/FieldSessionLog.kt", "FieldRuntimeSnapshot; NoopFieldSessionLog", "release 원자료를 제외하고 noop logging 경계를 둔다.")],
    "GAP-002": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/PrivacyDeletionPolicy.kt", "DeletionInventoryItem; AccountDeletionStateMachine", "Android의 9개 삭제 항목과 상태기계를 정의한다."), ("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/AndroidPrivacyDeletionClient.kt", "requestDeletionCall; validatedAccountDeletionStatusOrNull", "Android 삭제 wire shape를 정의한다."), ("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/AndroidIntegratedConsentClient.kt", "CONSENT_CONTROL_SECRET_HEADER; x-walksafe-consent-control-secret", "Android 삭제 client가 재사용하는 consent-control secret literal을 정의한다."), ("apps/android-gateway/src/privacy-rights.ts", "parseAccountDeletionRequest; EXTERNAL_PENDING; transitionAccountDeletionItem", "Gateway wire shape와 외부 evidence 기반 item transition 원장을 정의한다."), ("apps/android-gateway/src/routes.ts", "accountDeletionControl; ACCOUNT_DELETION_SECRET_HEADER", "Gateway의 별도 secret·query·request 경계를 강제한다.")],
    "GAP-003": [("backend/app/services/report_storage.py", "stage_report_image; reconcile_pending_report_writes", "로컬 신고 파일 journal이며 원본 object-store 용량정책은 아니다.")],
    "GAP-004": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/AndroidNetworkTransferPolicy.kt", "AndroidNetworkTransferPolicy.isAllowed", "Wi-Fi·이동통신망·offline 전송 분기를 판정한다."), ("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt", "PERSISTENT_REPORT_QUEUE_ENABLED = false", "제품 흐름에서 persistent report queue를 명시적으로 비활성화한다."), ("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/AndroidPendingReportStore.kt", "purgeAllWithoutLoading", "이미 비활성인 legacy queue의 ciphertext와 key를 load하지 않고 purge한다.")],
    "GAP-005": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt", "applyIntegratedConsentConfirmation; processReportCandidate; reportUploader.uploadCall; isReportUploadTerminalCurrent", "server consent receipt를 영속화하지만 활성 보행에서 신고 payload를 직접 전송한다."), ("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/AndroidPendingReportStore.kt", "purgeAllWithoutLoading", "후보 정책이 요구하는 암호화 영속 queue는 비활성이다.")],
    "GAP-006": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/PermissionSessionPolicy.kt", "PermissionSessionPolicy; PermissionDependencyPolicy.evaluate", "인증·동의·권한을 분리하고 기능별 중단을 판정한다."), ("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt", "applyObservedPermissionStateChange; enterPermissionRecoveryBarrier", "카메라·위치 권한 상실을 runtime 중단·안전정지 경계에 연결한다.")],
    "GAP-007": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/RouteNavigator.kt", "RouteNavigator.update", "위치 표본으로 자동 도착과 reroute를 판정한다.")],
    "GAP-008": [("apps/android/adminapp/build.gradle.kts", "applicationId = \"kr.co.hanium.dreamup.walksafe.admin\"", "사용자 앱과 다른 관리자 applicationId를 지정한다."), ("apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityController.java", "login; revokeSession; startRecovery; completeRecovery", "별도 관리자 인증·세션폐기·복구 코어가 있다."), ("apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminHighRiskActionGate.java", "evaluate", "복구 상태·만료·작업 binding 오류를 fail-closed한다."), ("backend/app/services/admin_security.py", "AdminSecurityService; _authorize_admin_bearer; authorize_high_risk_bearer", "password+TOTP, device-bound session과 재확인 replay 경계를 서버에서 강제한다."), ("configs/walksafe_product_boundary_20260722.json", "/release_control/release_eligibility; /release_control/remaining_gates; /products/admin_android_app/signing/configuration_state; /products/admin_android_app/session/implementation_state; /products/admin_android_app/recovery/material; /products/admin_android_app/recovery/formal_phone_loss_drill_status", "관리자 signing은 미구성, session은 provisioning·정식시험 대기, off-phone recovery material은 계약뿐이며 실제 drill은 NOT_RUN이다.")],
    "GAP-009": [("backend/app/api/health.py", "create_router", "generic readiness만 있고 capacity version·TTL 계약은 없다.")],
    "GAP-010": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/WalkSafeStartupCapability.kt", "WALKSAFE_PRODUCT_PURPOSE_STATEMENT_KO; WalkSafeStartupCapabilityResolver", "제품 목적과 startup 안전 capability를 정의한다.")],
    "GAP-011": [("configs/walksafe_product_boundary_20260722.json", "/release_control", "단계·배포 경계는 닫혀 있고 promotion은 승인되지 않았다.")],
    "GAP-012": [("apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminHighRiskActionGate.java", "evaluate", "관리자 고위험 작업을 재인증·복구 상태로 제한한다.")],
    "GAP-013": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/PriorityUserOnboardingPolicy.kt", "PriorityUserOnboardingPolicy.evaluate; PriorityUserPracticeLifecycle.perform", "우선 사용자 onboarding reducer와 실습 lifecycle이 있다.")],
    "GAP-014": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/OfficialEnvironmentPolicy.kt", "OfficialEnvironmentPolicy.assess; OfficialEnvironmentRuntimeGuard", "production profile은 없고 환경 상태를 fail-close한다.")],
    "GAP-015": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/PhoneMountingPolicy.kt", "PhoneMountingPolicy.assess", "장착 후보 정책은 있으나 production profile이 비어 있다.")],
    "GAP-016": [("apps/android/settings.gradle.kts", "include(\":app\"); include(\":adminapp\")", "사용자와 관리자 Android 모듈을 분리한다."), ("apps/android/app/build.gradle.kts", "applicationId = \"kr.co.hanium.dreamup.walksafe\"", "사용자 앱의 고유 applicationId를 지정한다."), ("apps/android/adminapp/build.gradle.kts", "applicationId = \"kr.co.hanium.dreamup.walksafe.admin\"", "관리자 앱의 별도 applicationId를 지정한다."), ("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/WalkSafeStartupCapability.kt", "WalkSafeStartupCapabilityResolver; mayConfirmAndStart; pendingCore; unavailableCore", "core capability는 fail-closed하되 LIMITED tier의 시작은 허용한다.")],
    "GAP-017": [("apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminBoundaryActivity.java", "onCreate; render", "보안 workflow는 제공하지만 운영 신고 workflow는 잠겨 있다."), ("apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityController.java", "login; revokeSession; startRecovery; completeRecovery; consumeHighRiskAuthorization; clearReconfirmation", "password+TOTP session·폐기·복구를 관리하고 pending 재확인 binding을 판정 전에 선소비한다."), ("apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminHighRiskActionGate.java", "evaluate", "복구 상태·만료·action·method·path binding mismatch를 fail-closed한다.")],
    "GAP-018": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/ApprovedDeviceProfile.kt", "WalkSafeApprovedDeviceProfiles.production", "운영 승인 기기 목록이 비어 있다.")],
    "GAP-019": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/FirstRunOnboardingPolicy.kt", "FirstRunOnboardingPolicy.productionEvidenceVerifier", "production evidence verifier가 fail-close한다.")],
    "GAP-020": [("apps/android-gateway/src/field-long-session.ts", "establishFieldLongSession; refreshFieldLongSession; revokeFieldLongSessionDevice", "장기 세션·회전·기기별 폐기 코어가 있다.")],
    "GAP-021": [("apps/android-gateway/src/field-walk-ledger.ts", "commandFieldWalk", "계정별 단일 active lease와 takeover를 처리한다."), ("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/GatewayWalkSession.kt", "GatewayWalkSessionClient; start; takeover; renew; end", "Android Gateway client가 lease lifecycle 명령을 전송한다."), ("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt", "requestGatewayWalkStart; executeGatewayWalkTakeover; scheduleGatewayWalkRenewal", "Android 보행 lifecycle에 start·음성확인 takeover·renew를 연결한다.")],
    "GAP-022": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/IntegratedConsentPolicy.kt", "IntegratedConsentSession; INTEGRATED_CONSENT_DISCLOSURE_KO", "Android process-local 동의·철회 상태와 고지를 구현한다."), ("apps/android-gateway/src/integrated-consent.ts", "IntegratedConsentState; recordConsent; receipt_sha256; writeState", "Gateway가 receipt chain을 포함한 동의 event 원장을 원자적으로 영속화한다.")],
    "GAP-023": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/PermissionSessionPolicy.kt", "PermissionRecoveryGate; PermissionDependencyPolicy", "권한별 중단과 전체 재검사·명시 재개를 판정한다."), ("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt", "enterPermissionRecoveryBarrier; resumePermissionRecoveryFromExplicitUserAction", "권한 상실을 복구 barrier와 명시적 사용자 재개에 연결한다.")],
    "GAP-024": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/PrivacyDeletionPolicy.kt", "DeletionInventoryItem; AccountDeletionStateMachine", "9개 삭제 항목·SLA 상태기계를 정의한다.")],
    "GAP-025": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt", "walkSafetyOverlay; handleWalkScreenBackPressed; showWalkExitConfirmationDialog", "release 보행 화면의 읽기 전용 overlay와 뒤로가기 선행 중단·확인을 구현한다."), ("apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityFp016StaticTest.kt", "releaseActiveScreenHidesControlsAndShowsReadOnlySafetyOverlay", "화면 계약을 검사하는 소스이며 실행 PASS 증거는 아니다.")],
    "GAP-026": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/WalkSessionLifecycle.kt", "WalkSessionLifecycle.handle", "background·재검사·명시 재개 lifecycle을 정의한다.")],
    "GAP-027": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/WalkSessionLifecycle.kt", "WalkSessionSnapshot; WalkSessionLifecycle.handle", "state·mode·epoch와 복구 전이를 분리한다.")],
    "GAP-028": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/inference/TfliteAndroidFrameDetector.kt", "TfliteAndroidFrameDetector.detect", "TFLite frame 탐지를 수행한다."), ("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt", "onDrawFrame; handleDetectorRuntimeFailure; resolveReportThreshold", "detector-depth 통합, terminal 안전정지와 runtime class threshold 사용을 연결한다."), ("apps/android/app/src/main/assets/model-config/two_model_runtime.json", "/primary_model; /models/unified_walksafe/enabled; /models/unified_walksafe/thresholds", "배포 후보와 class별 threshold가 runtime config에서 활성이다."), ("docs/deliverables/08-ai-ml-data/registers/model-register.json", "/primary_runtime_model_id; /approved_model_count", "runtime 후보는 있지만 승인 모델 수는 0이다.")],
    "GAP-029": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/depth/MessagePolicy.kt", "MessagePolicy.evaluate", "STOP 문구와 위험 우선순위를 산출한다.")],
    "GAP-030": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/CameraFrameQualityPolicy.kt", "CameraFrameQualityPolicy.assess", "밝기·가림·흔들림·각도 품질을 fail-close한다."), ("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt", "observeOfficialEnvironmentCameraFrame; resolveReportThreshold; onDrawFrame", "일부 품질값은 비어 있고 활성 detector/report 경로가 runtime class threshold를 사용한다."), ("apps/android/app/src/main/assets/model-config/two_model_runtime.json", "/primary_model; /models/unified_walksafe/enabled; /models/unified_walksafe/thresholds", "unified 후보가 enabled이고 class별 threshold는 0.15~0.35다."), ("docs/deliverables/08-ai-ml-data/registers/model-register.json", "/primary_runtime_model_id; /approved_model_count", "runtime 후보는 지정돼 있지만 승인 모델 수는 0이다.")],
    "GAP-031": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/RouteNavigator.kt", "RouteNavigator.update", "사용자 확인 전에 arrived 상태를 낼 수 있다.")],
    "GAP-032": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt", "updateRouteGuidance", "이탈 시 사용자 선택 없이 route 재요청을 시작한다.")],
    "GAP-033": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/TactileRoutePolicy.kt", "selectCandidate; evaluate", "불확실·손상 후보는 TMAP 경로로 되돌린다.")],
    "GAP-034": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/AndroidStartupCapabilityProbe.kt", "onDeviceSpeechRecognitionAvailable", "단말 내 음성인식 capability를 검사한다."), ("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt", "voiceReportButton; ensureVoicePermissionThenListen; startVoiceCommandRecognition", "일반 command는 버튼을 눌러 recognizer를 시작하며 호출어 lifecycle은 없다.")],
    "GAP-035": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/AndroidVoiceCommand.kt", "selectAndroidVoiceAction; parseAndroidVoiceCommand", "신뢰도·부정어·whitelist로 명령을 고른다.")],
    "GAP-036": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/feedback/AndroidFeedbackActuator.kt", "onInit; failRequiredSpeechRuntime", "offline 음성 실패를 runtime에 전달한다."), ("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt", "handleRuntimeSpeechCapabilityFailure; updateStatus; announceForAccessibility", "음성 실패를 전체 보행 안전정지와 보이는 상태·TalkBack 안내에 연결한다."), ("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/feedback/WalkSafeFeedbackPolicy.kt", "object VibrationPatterns; MessageLevel.STOP; MessageLevel.WARNING", "STOP만 진동 pattern이 있고 WARNING·CAUTION 등에는 구별되는 pattern이 없다.")],
    "GAP-037": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt", "buildContentView; linkPriorityUserAccessibilityTraversal; updatePriorityUserOnboardingUi", "heading·48dp 조작·명시적 traversal과 접근성 상태 설명을 구성한다."), ("apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityAccessibilityStaticTest.kt", "screenReaderModeKeepsRiskHapticAndFallsBackAfterNavigationTtsFailure", "접근성 형태를 검사하는 소스이며 실제 TalkBack PASS가 아니다.")],
    "GAP-038": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt", "showPermissionDenialPanel; enterPermissionRecoveryBarrier; disableDetectorAfterRuntimeFailure", "접근 가능한 거부·복구와 detector 안전정지를 연결한다.")],
    "GAP-039": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt", "buildContentView; linkPriorityUserAccessibilityTraversal; updatePriorityUserOnboardingUi", "font scale·고대비·48dp 조작과 명시적 초점 순서를 구성한다."), ("apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/PriorityUserOnboardingStaticTest.kt", "onboardingUsesFlexibleTextHighContrastAndExplicitFocusOrder", "우선 사용자 UI 형태를 검사하는 소스이며 실행 증거는 아니다.")],
    "GAP-040": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/AndroidReportCandidatePolicy.kt", "AndroidReportCandidatePolicy.prepare; isStableAutomaticDetection", "손상 class·반복 관측·신뢰도·GPS gate를 적용한다."), ("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt", "processReportCandidate; reportUploader.uploadCall", "활성 보행 후보를 영속 queue 전에 직접 upload한다."), ("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/AndroidPendingReportStore.kt", "purgeAllWithoutLoading", "persistent queue는 비활성이고 legacy 자료만 정리한다.")],
    "GAP-041": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt", "persistTransientReportAttemptFailure; newStableReportTraceId; processReportCandidate; reportUploader.uploadCall", "요청 trace와 retry/backoff를 관리하지만 활성 보행에서 payload를 즉시 전송한다."), ("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/AndroidPendingReportStore.kt", "purgeAllWithoutLoading", "pending metadata가 아니라 legacy queue cleanup-only다."), ("backend/app/api/reports.py", "_idempotent_report_replay; _commit_new_report_with_image", "서버 replay와 image/DB commit 경계를 제공한다."), ("backend/app/services/report_storage.py", "reconcile_pending_report_writes", "별도 startup 경로가 호출해야 하는 신고 임시파일 reconciliation을 제공한다.")],
    "GAP-042": [("apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminBoundaryActivity.java", "onCreate; render", "운영 신고 UI는 비활성이다."), ("backend/app/api/reports.py", "list_reports; update_report_status; status_history; export_reports", "backend 신고 조회·상태 이력·기관용 export surface가 존재한다.")],
    "GAP-043": [("apps/android/app/src/release/java/kr/co/hanium/dreamup/walksafe/debuglog/DebugFrameCaptureUploaderFactory.kt", "DebugFrameCaptureUploaderFactory.create", "release는 NoopFrameCaptureUploader를 반환한다.")],
    "GAP-044": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/AndroidNetworkTransferPolicy.kt", "AndroidActivityOriginalUploadPolicy.decide; ActivityOriginalUploadAdmissionController.admit", "정지·망 선택·움직임 재개 취소를 판정한다."), ("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt", "runIfActivityOriginalUploadAllowed; cancelActivityOriginalUploads", "정책을 전송 경계에 통합한다."), ("apps/android/app/src/release/java/kr/co/hanium/dreamup/walksafe/debuglog/DebugFrameCaptureUploaderFactory.kt", "DebugFrameCaptureUploaderFactory.create; NoopFrameCaptureUploader", "release 원본 frame collector는 no-op이다."), ("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/AndroidPendingReportStore.kt", "purgeAllWithoutLoading", "암호화 durable queue 대신 legacy cleanup 경계만 있다.")],
    "GAP-045": [("backend/app/api/reports.py", "_persist_v2_report; _commit_new_report_with_image", "신고 image journal과 DB commit 경계를 연결한다."), ("backend/app/services/report_storage.py", "stage_report_image; reconcile_pending_report_writes", "신고 임시파일 journal과 crash reconciliation을 구현한다."), ("scripts/check_report_retention_dry_run.py", "RETENTION_DAYS; retention_bucket; _apply_database_retention_locked", "신고 active/resolved 180일 retention 실행 경로를 정의한다.")],
    "GAP-046": [("docs/deliverables/08-ai-ml-data/registers/model-register.json", "/primary_runtime_model_id; /approved_model_count", "승인 모델은 0이고 runtime 후보는 배포 부적격이다.")],
    "GAP-047": [("scripts/walksafe_dataset_integrity.py", "verify_content_hashed_manifest", "파일 hash와 cross-split 중복을 검사한다.")],
    "GAP-048": [("docs/deliverables/08-ai-ml-data/registers/model-register.json", "/runtime_artifact_evidence_binding/promotion_rollback_contract", "known-good rollback anchor가 없고 promotion은 미승인이다.")],
    "GAP-049": [("apps/android-gateway/src/routes.ts", "ALLOWED_METHODS; PUBLIC_GATEWAY_ROUTES", "Gateway 운영 경로를 제한한다."), ("backend/app/services/admin_security.py", "ADMIN_APP_KIND; ADMIN_ROLE; ADMIN_AUDIENCE; authorize_admin_protected_work", "backend 관리자 작업을 app-kind·role·audience·device-bound session으로 검사한다."), ("backend/app/api/reports.py", "_idempotent_report_replay; _shared_report_write_lock", "report 경로의 개별 replay와 maintenance write lock을 제공한다.")],
    "GAP-050": [("backend/app/services/report_storage.py", "stage_report_image; reconcile_pending_report_writes", "로컬 journal 복구를 제공하지만 object store가 아니다.")],
    "GAP-051": [("apps/android-gateway/src/backend.ts", "acquireImageUploadAdmission", "in-process rate·동시성 admission을 제공한다.")],
    "GAP-052": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/AndroidWalkSessionResourceProbe.kt", "AndroidWalkSessionResourceProbe; WalkSessionDeviceResourceSnapshot", "배터리·저장공간·발열 readiness를 fail-close한다."), ("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/feedback/AndroidFeedbackActuator.kt", "failRequiredSpeechRuntime; notifyOfflineKoreanSpeechUnavailable", "필수 offline TTS runtime 실패를 MainActivity callback으로 전달한다."), ("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt", "applyRuntimeReadinessIfActive; handleRuntimeSpeechCapabilityFailure; enterWalkSessionSafetyStopAndCancelOutputs", "resource readiness와 필수 speech 실패를 활성 보행 안전정지에 연결한다.")],
    "GAP-053": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/AndroidNetworkTransferPolicy.kt", "AndroidNetworkTransferPolicy.isAllowed", "망 종류에 따른 전송 admission을 판정한다."), ("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/AndroidPendingReportStore.kt", "AndroidPendingReportStore", "persistent queue를 제공하지 않고 legacy 자료를 purge한다."), ("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt", "isGatewayNetworkAllowed; updateRouteGuidance; requestRoute", "망 통제를 연결하지만 off-route에서 사용자 선택 없이 route를 다시 요청한다.")],
    "GAP-054": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/AndroidWalkSessionResourceProbe.kt", "AndroidWalkSessionResourceProbe", "저배터리·저장공간·critical thermal을 관찰한다."), ("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt", "applyRuntimeReadinessIfActive", "활성 보행의 resource readiness 상실을 안전정지 경로에 연결한다.")],
    "GAP-055": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/AndroidPrivacyDeletionClient.kt", "requestDeletionCall; fetchDeletionStatusCall", "Android wire shape와 consent-control secret을 사용한다."), ("apps/android-gateway/src/privacy-rights.ts", "parseAccountDeletionRequest; statusView; EXTERNAL_PENDING; transitionAccountDeletionItem", "다른 Gateway request/status schema와 외부 evidence transition 원장을 사용한다."), ("apps/android-gateway/src/routes.ts", "accountDeletionControl; ACCOUNT_DELETION_SECRET_HEADER", "별도 account-deletion secret과 정확한 입력 shape를 요구한다.")],
    "GAP-056": [("docs/control/execution/goal-results/WS-GOAL-EPIC-03-FP-047-R001/completion-receipt.json", "/completion_boundary", "내부 완료와 formal·device·external NOT_RUN 경계를 고정한다.")],
    "GAP-057": [("docs/deliverables/07-security/security-verification-evidence.md", "현재 실행 상태", "보안 검증 다섯 항목이 Planned/NOT_RUN이다.")],
    "GAP-058": [("docs/deliverables/06-testing/test-quality-report.md", "TST-19; 종합판정", "279건 실행 0, PASS 0, release NOT_ELIGIBLE을 기록한다.")],
    "GAP-059": [(".github/workflows/android-device-acceptance.yml", "Write acceptance scope summary", "좁은 smoke 범위이며 제품 인수 인용을 금지한다.")],
    "GAP-060": [("apps/android/app/build.gradle.kts", "validateWalkSafeSourceCommit", "release source commit와 exact HTTPS Gateway origin을 강제한다.")],
    "GAP-061": [("apps/android-gateway/src/telemetry.ts", "recordGatewayFailure; writeGatewayTelemetry", "Gateway failure telemetry producer와 file sink 격리가 있다."), ("apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityTelemetry.java", "SCHEMA_VERSION; AUTH_FAILED; RECOVERY_START_FAILED; Recorder", "관리자 인증·복구 결과를 versioned 비밀값 없는 Logcat event로 만든다."), ("apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityController.java", "telemetry.record; AUTH_SUCCEEDED; RECOVERY_COMPLETE_FAILED", "관리자 인증·복구 controller가 telemetry producer를 실제 호출한다."), ("docs/deliverables/10-operations/operator-guide.md", "OPS-06 운영 대시보드; OPS-07 알림 기준; PLANNED / NOT_RUN", "운영 정본이 dashboard와 alert를 계획·미실행 상태로 고정한다.")],
    "GAP-062": [("scripts/backup_walksafe_data_20260711.sh", "gpg --no-options; manifest.json.sig", "database와 upload backup을 암호화하고 manifest에 서명한다."), ("scripts/restore_walksafe_backup_drill_20260711.sh", "--verify-inherited-restore; pg_restore --exit-on-error", "서명·manifest를 검증하고 격리된 target에 restore drill을 수행한다."), ("scripts/prune_walksafe_backups_20260711.py", "plan_backup_prune; apply_backup_prune", "35일·최소 3개·dry-run prune을 구현한다."), ("docs/deliverables/10-operations/registers/operations-registers.json", "/backup_policy/restore_execution_count; /backup_policy/backup_instances; /backup_policy/restore_executions", "실제 backup instance와 restore execution은 0건이다."), ("docs/deliverables/10-operations/recovery-plan.md", "OPS-12 RTO·RPO; 미정이다; DRAFT / NOT_APPROVED", "RPO/RTO는 실측·승인 전 미정이며 현재 계획은 미승인이다.")],
    "GAP-063": [("backend/app/api/reports.py", "_shared_report_write_lock; fcntl.LOCK_SH", "report write가 maintenance lock의 shared side를 non-blocking으로 획득하고 실패 시 닫힌다."), ("scripts/backup_walksafe_data_20260711.sh", "flock --exclusive; maintenance lock", "backup 경로가 같은 maintenance 경계의 exclusive lock을 획득한다."), ("docs/deliverables/12-closure/decommissioning-plan.md", "CLS-14 데이터 보존·이관·삭제; CLS-15 계정·키·인프라 정리; CLS-16 서비스 종료·폐기 계획", "종료 시 data·account·key·provider lifecycle과 NOT_RUN 실행 경계를 구조화한다."), ("docs/control/decision-interview/walksafe-feature-policy-effective-candidate.json", "Delta의 rollback 없이 forward fix가 원본 known-good rollback 답변을 대체한다.", "정책 후보가 실패 시 rollback 없이 forward-fix와 안전정지를 요구한다.")],
    "GAP-064": [("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/AndroidPendingReportStore.kt", "AndroidPendingReportStore", "요구 대상 queue는 비활성이고 byte cap이 없다.")],
    "GAP-065": [("backend/app/api/health.py", "create_router", "capacity version·observed_at·TTL 계약이 없다.")],
    "GAP-066": [("docs/control/execution/artifact-remediation/20260726/w5/privacy-consent-secrets-current-state.json", "/authority_boundary", "독립 법률·개인정보 검토가 pending이다.")],
    "GAP-067": [("docs/deliverables/10-operations/registers/operations-registers.json", "/server_capacity_policy; /remaining_gates", "30,000원은 정책값이고 실제 비용 gate는 NOT_RUN이다.")],
    "GAP-068": [("docs/control/execution/walksafe-single-admin-recovery-drill-protocol-20260722-r001.json", "/metadata/status; /release_boundary", "절차는 DRAFT_PROCEDURE_NOT_EXECUTED이고 gate는 NOT_RUN이다.")],
}


class R022CandidateError(RuntimeError):
    """Raised when a source or generated candidate violates the closed contract."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise R022CandidateError(message)


def _strict_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise R022CandidateError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def load_strict_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=_strict_pairs,
            parse_constant=lambda token: (_ for _ in ()).throw(
                R022CandidateError(f"non-standard JSON number: {token}")
            ),
        )
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise R022CandidateError(f"cannot read strict JSON: {path}") from exc
    _require(isinstance(value, dict), f"JSON root must be an object: {path}")
    return value


def _canonical_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")


def _pretty_bytes(value: Any) -> bytes:
    return (
        json.dumps(
            value,
            ensure_ascii=False,
            indent=2,
            allow_nan=False,
        )
        + "\n"
    ).encode("utf-8")


def _object_sha256(value: Any) -> str:
    return hashlib.sha256(_canonical_bytes(value)).hexdigest()


def _sealed_object(value: dict[str, Any], field: str) -> dict[str, Any]:
    result = copy.deepcopy(value)
    result.pop(field, None)
    result[field] = _object_sha256(result)
    return result


def _seal_is_valid(value: dict[str, Any], field: str) -> bool:
    expected = value.get(field)
    if not isinstance(expected, str):
        return False
    candidate = copy.deepcopy(value)
    candidate.pop(field, None)
    return expected == _object_sha256(candidate)


def _sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _sha256_file(path: Path) -> str:
    return _sha256_bytes(path.read_bytes())


def _relative(path: Path) -> str:
    return path.relative_to(REPO_ROOT).as_posix()


def _confined_file(relative_path: str) -> Path:
    _require(
        isinstance(relative_path, str)
        and relative_path
        and not relative_path.startswith("/"),
        f"invalid evidence path: {relative_path!r}",
    )
    candidate = REPO_ROOT / relative_path
    _require(candidate.exists(), f"evidence file is missing: {relative_path}")
    _require(candidate.is_file(), f"evidence path is not a file: {relative_path}")
    _require(not candidate.is_symlink(), f"evidence path is a symlink: {relative_path}")
    resolved = candidate.resolve()
    _require(
        resolved.is_relative_to(REPO_ROOT.resolve()),
        f"evidence path escapes repository: {relative_path}",
    )
    return candidate


def _git_output(*args: str) -> str:
    completed = subprocess.run(
        ["git", *args],
        cwd=REPO_ROOT,
        check=False,
        capture_output=True,
        text=True,
        env={**os.environ, "GIT_OPTIONAL_LOCKS": "0"},
    )
    _require(
        completed.returncode == 0,
        f"git {' '.join(args)} failed: {completed.stderr.strip()}",
    )
    return completed.stdout.strip()


def _current_head() -> str:
    return _git_output("rev-parse", "HEAD")


def _current_branch() -> str:
    return _git_output("branch", "--show-current")


def _git_status_for_paths(paths: list[str]) -> dict[str, str]:
    completed = subprocess.run(
        [
            "git",
            "status",
            "--porcelain=v1",
            "-z",
            "--untracked-files=all",
            "--",
            *paths,
        ],
        cwd=REPO_ROOT,
        check=False,
        capture_output=True,
        env={**os.environ, "GIT_OPTIONAL_LOCKS": "0"},
    )
    _require(
        completed.returncode == 0,
        f"git status failed: {completed.stderr.decode('utf-8', errors='replace')}",
    )
    statuses: dict[str, str] = {}
    tokens = completed.stdout.decode("utf-8", errors="strict").split("\0")
    index = 0
    while index < len(tokens):
        token = tokens[index]
        index += 1
        if not token:
            continue
        _require(len(token) >= 4 and token[2] == " ", "unexpected git status record")
        status = token[:2]
        path = token[3:]
        if status[0] in {"R", "C"}:
            _require(index < len(tokens), "truncated git rename/copy record")
            path = tokens[index]
            index += 1
        statuses[path] = status
    return {path: statuses.get(path, "  ") for path in paths}


def _source_binding(name: str, path: Path) -> dict[str, Any]:
    return {
        "name": name,
        "path": _relative(path),
        "bytes": path.stat().st_size,
        "sha256": _sha256_file(path),
        "immutability": "PINNED_INPUT_NOT_MODIFIED",
    }


def _validate_pinned_sources() -> tuple[dict[str, Any], ...]:
    loaded: list[dict[str, Any]] = []
    for path, (expected_hash, expected_bytes) in EXPECTED_SOURCE_BINDINGS.items():
        _require(path.is_file(), f"required source is missing: {_relative(path)}")
        _require(
            path.stat().st_size == expected_bytes,
            f"source byte length changed: {_relative(path)}",
        )
        _require(
            _sha256_file(path) == expected_hash,
            f"source hash changed: {_relative(path)}",
        )
        loaded.append(load_strict_json(path))
    gap, backlog, plan_manifest, plan_candidates, plan_evidence, static_plan = loaded
    _require(_seal_is_valid(gap, "report_content_sha256"), "r021 Gap seal differs")
    _require(_seal_is_valid(backlog, "backlog_content_sha256"), "r021 Backlog seal differs")
    _require(
        backlog.get("gap_report_content_sha256") == gap.get("report_content_sha256"),
        "r021 Gap/Backlog pair binding differs",
    )
    _require(plan_manifest.get("status") == "PLAN_REVIEWED_NOT_ACTIVATED", "plan state differs")
    _require(
        plan_candidates.get("status") == "PLAN_ONLY_NOT_CANONICAL_GAP",
        "19-row planning candidate boundary differs",
    )
    _require(
        plan_evidence.get("status") == "PLAN_ONLY_SOURCE_SNAPSHOT",
        "planning evidence boundary differs",
    )
    _require(
        static_plan.get("package_id")
        == "WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-4",
        "static v2.4 plan differs",
    )
    _require(_current_head() == EXPECTED_HEAD, "repository HEAD differs from R003 pin")
    expected_r001_hash, expected_r001_bytes = EXPECTED_R001_PAIR_MANIFEST
    _require(
        R001_PAIR_MANIFEST_PATH.is_file()
        and R001_PAIR_MANIFEST_PATH.stat().st_size == expected_r001_bytes
        and _sha256_file(R001_PAIR_MANIFEST_PATH) == expected_r001_hash,
        "superseded R001 candidate manifest differs",
    )
    return gap, backlog, plan_manifest, plan_candidates, plan_evidence, static_plan


def _mapping_projection(gap: dict[str, Any]) -> list[dict[str, str]]:
    mapping = {
        row["source_policy_id"]: row["gap_id"]
        for row in gap.get("assessments", [])
        if isinstance(row, dict)
    }
    projection = [
        {"source_policy_id": policy_id, "gap_id": mapping[policy_id]}
        for policy_id in sorted(mapping)
    ]
    _require(len(projection) == 68, "policy/Gap mapping is not exact68")
    _require(
        _object_sha256(projection) == EXPECTED_MAPPING_SHA256,
        "policy/Gap mapping fingerprint differs",
    )
    return projection


def _hard_dependency_projection(plan_manifest: dict[str, Any]) -> list[dict[str, str]]:
    edges = plan_manifest.get("hard_dependency_edges")
    _require(
        isinstance(edges, list)
        and len(edges) == 24
        and all(isinstance(edge, dict) for edge in edges),
        "hard dependency edge set differs",
    )
    projection = sorted(
        (
            {
                "from": edge.get("from"),
                "to": edge.get("to"),
                "edge_kind": edge.get("edge_kind"),
            }
            for edge in edges
        ),
        key=lambda edge: (str(edge["from"]), str(edge["to"]), str(edge["edge_kind"])),
    )
    _require(
        _object_sha256(projection) == EXPECTED_HARD_DEPENDENCY_SHA256,
        "hard dependency fingerprint differs",
    )
    return projection


def _decode_json_pointer_token(token: str) -> str:
    result: list[str] = []
    index = 0
    while index < len(token):
        character = token[index]
        if character != "~":
            result.append(character)
            index += 1
            continue
        _require(index + 1 < len(token), f"invalid JSON Pointer escape: {token!r}")
        escaped = token[index + 1]
        _require(escaped in {"0", "1"}, f"invalid JSON Pointer escape: {token!r}")
        result.append("~" if escaped == "0" else "/")
        index += 2
    return "".join(result)


def _resolve_json_pointer(document: Any, pointer: str) -> Any:
    _require(pointer.startswith("/"), f"invalid JSON Pointer: {pointer!r}")
    current = document
    for raw_token in pointer[1:].split("/"):
        token = _decode_json_pointer_token(raw_token)
        if isinstance(current, dict):
            _require(token in current, f"JSON Pointer does not exist: {pointer}")
            current = current[token]
            continue
        if isinstance(current, list):
            _require(
                token.isdigit() and (token == "0" or not token.startswith("0")),
                f"invalid JSON Pointer array index: {pointer}",
            )
            index = int(token)
            _require(index < len(current), f"JSON Pointer does not exist: {pointer}")
            current = current[index]
            continue
        raise R022CandidateError(f"JSON Pointer traverses a scalar: {pointer}")
    return current


def _validate_locator_text(path: Path, symbol_or_test: str) -> str:
    parts = [part.strip() for part in symbol_or_test.split(";") if part.strip()]
    if parts and all(part.startswith("/") for part in parts):
        document = load_strict_json(path)
        for pointer in parts:
            _resolve_json_pointer(document, pointer)
        return "JSON_POINTER_RESOLVED"
    text = path.read_text(encoding="utf-8")
    missing: list[str] = []
    for part in parts:
        if not part or part in text:
            continue
        dotted_components = [component for component in part.split(".") if component]
        if len(dotted_components) > 1 and all(
            component in text for component in dotted_components
        ):
            continue
        missing.append(part)
    _require(
        not missing,
        f"evidence locator text is missing in {_relative(path)}: {missing}",
    )
    return "EXACT_TEXT_PRESENT"


def _build_evidence_snapshot() -> tuple[dict[str, Any], dict[str, list[dict[str, Any]]]]:
    expected_gap_ids = {f"GAP-{index:03d}" for index in range(1, 69)}
    _require(set(EVIDENCE) == expected_gap_ids, "evidence decisions are not exact68")
    paths = sorted(
        {
            relative_path
            for locators in EVIDENCE.values()
            for relative_path, _, _ in locators
        }
    )
    statuses = _git_status_for_paths(paths)
    file_by_path: dict[str, dict[str, Any]] = {}
    locators_by_gap: dict[str, list[dict[str, Any]]] = {}
    for gap_id in sorted(EVIDENCE):
        locators: list[dict[str, Any]] = []
        for relative_path, symbol_or_test, observed_fact in EVIDENCE[gap_id]:
            path = _confined_file(relative_path)
            verification = _validate_locator_text(path, symbol_or_test)
            record = {
                "path": relative_path,
                "file_sha256": _sha256_file(path),
                "bytes": path.stat().st_size,
                "git_status": statuses[relative_path],
                "symbol_or_test": symbol_or_test,
                "locator_verification": verification,
                "observed_fact": observed_fact,
                "test_execution_credit": 0,
            }
            locators.append(record)
            file_by_path.setdefault(
                relative_path,
                {
                    "path": relative_path,
                    "sha256": record["file_sha256"],
                    "bytes": record["bytes"],
                    "git_status": record["git_status"],
                    "gap_ids": [],
                },
            )["gap_ids"].append(gap_id)
        locators_by_gap[gap_id] = locators
    files = [file_by_path[path] for path in sorted(file_by_path)]
    for record in files:
        record["gap_ids"].sort()
    path_set_sha256 = _object_sha256([record["path"] for record in files])
    content_set_sha256 = _object_sha256(
        [
            {
                "path": record["path"],
                "sha256": record["sha256"],
                "bytes": record["bytes"],
                "git_status": record["git_status"],
            }
            for record in files
        ]
    )
    snapshot = {
        "scope_kind": "EXACT68_LIVE_EVIDENCE_CURRENT_WORKTREE",
        "pinned_head": EXPECTED_HEAD,
        "current_head": _current_head(),
        "branch": _current_branch(),
        "dirty_worktree_expected": True,
        "whole_repository_frozen": False,
        "focused_scope_only": True,
        "file_count": len(files),
        "path_set_sha256": path_set_sha256,
        "content_set_sha256": content_set_sha256,
        "files": files,
    }
    snapshot["snapshot_sha256"] = _object_sha256(snapshot)
    return snapshot, locators_by_gap


def _candidate_assessments(
    gap: dict[str, Any],
) -> tuple[list[dict[str, Any]], dict[str, dict[str, Any]], set[str], set[str]]:
    source_rows = gap.get("assessments")
    _require(
        isinstance(source_rows, list) and len(source_rows) == 68,
        "r021 assessment set is not exact68",
    )
    rows_by_id = {
        row.get("gap_id"): row
        for row in source_rows
        if isinstance(row, dict) and isinstance(row.get("gap_id"), str)
    }
    expected_gap_ids = {f"GAP-{index:03d}" for index in range(1, 69)}
    _require(set(rows_by_id) == expected_gap_ids, "r021 gap IDs are not exact68")
    _require(set(DECISION_REASON) == expected_gap_ids, "decision reasons are not exact68")
    _require(
        set(REPLACEMENT_CURRENT_TEXT)
        == {
            "GAP-002", "GAP-004", "GAP-005", "GAP-006", "GAP-008",
            "GAP-016", "GAP-017", "GAP-021", "GAP-022", "GAP-023",
            "GAP-025", "GAP-028", "GAP-030", "GAP-034", "GAP-036",
            "GAP-037", "GAP-038", "GAP-039", "GAP-040", "GAP-041",
            "GAP-042", "GAP-044", "GAP-045", "GAP-049", "GAP-052",
            "GAP-053", "GAP-054", "GAP-055", "GAP-061", "GAP-062",
            "GAP-063",
        },
        "changed31 decision set differs",
    )
    _require(set(STATUS_CHANGES).issubset(REPLACEMENT_CURRENT_TEXT), "status change lacks replacement text")
    _require(
        set(REPLACEMENT_REMEDIATION) == set(REPLACEMENT_CURRENT_TEXT),
        "replacement remediation set differs from changed31",
    )

    candidates: list[dict[str, Any]] = []
    candidate_by_id: dict[str, dict[str, Any]] = {}
    changed_ids: set[str] = set()
    carried_ids: set[str] = set()
    for source in source_rows:
        gap_id = source["gap_id"]
        candidate = copy.deepcopy(source)
        if gap_id in REPLACEMENT_CURRENT_TEXT:
            candidate["status"] = STATUS_CHANGES.get(gap_id, source["status"])
            candidate["current_implementation_in_plain_language"] = (
                REPLACEMENT_CURRENT_TEXT[gap_id]
            )
            candidate["remediation"] = REPLACEMENT_REMEDIATION[gap_id]
            candidate["rationale"] = (
                f"2026-07-30 exact68 현재 저장소 재평가: {DECISION_REASON[gap_id]} "
                "테스트 소스의 존재는 실행 또는 PASS 증거로 계산하지 않았고, 정식 시험·"
                "실기기·외부 승인·출시 gate 상태는 변경하지 않았다."
            )
            evidence_ids = list(candidate.get("evidence_ids", []))
            evidence_ids.append(f"EVD-R022-{gap_id}")
            candidate["evidence_ids"] = list(dict.fromkeys(evidence_ids))
            candidate = _sealed_object(candidate, "assessment_sha256")
            changed_ids.add(gap_id)
        else:
            carried_ids.add(gap_id)
        candidates.append(candidate)
        candidate_by_id[gap_id] = candidate
    _require(len(changed_ids) == 31, "changed assessment count differs")
    _require(len(carried_ids) == 37, "carried assessment count differs")
    _require(changed_ids.isdisjoint(carried_ids), "changed/carry sets overlap")
    for gap_id in carried_ids:
        _require(
            candidate_by_id[gap_id] == rows_by_id[gap_id],
            f"carry-forward row changed: {gap_id}",
        )
    for row in candidates:
        _require(_seal_is_valid(row, "assessment_sha256"), f"assessment seal differs: {row['gap_id']}")
        _require(row.get("formal_test_status") == "NOT_RUN", f"formal status changed: {row['gap_id']}")
        _require(row.get("status") != "IMPLEMENTED", f"IMPLEMENTED is forbidden: {row['gap_id']}")
    return candidates, candidate_by_id, changed_ids, carried_ids


def _build_ledger(
    source_gap: dict[str, Any],
    candidate_by_id: dict[str, dict[str, Any]],
    changed_ids: set[str],
    carried_ids: set[str],
    snapshot: dict[str, Any],
    locators_by_gap: dict[str, list[dict[str, Any]]],
    mapping_projection: list[dict[str, str]],
    dependency_projection: list[dict[str, str]],
) -> dict[str, Any]:
    source_by_id = {row["gap_id"]: row for row in source_gap["assessments"]}
    records: list[dict[str, Any]] = []
    for gap_id in sorted(source_by_id):
        source = source_by_id[gap_id]
        candidate = candidate_by_id[gap_id]
        status_changed = source["status"] != candidate["status"]
        content_changed = gap_id in changed_ids
        if status_changed:
            decision_disposition = (
                "REASSESS_UP"
                if candidate["status"] == "PARTIAL"
                else "REASSESS_DOWN"
            )
        elif source["status"] == "EVIDENCE_MISSING":
            decision_disposition = "NEEDS_EVIDENCE"
        elif content_changed:
            decision_disposition = "TEXT_FIX"
        else:
            decision_disposition = "KEEP"
        records.append(
            {
                "gap_id": gap_id,
                "source_policy_id": source["source_policy_id"],
                "r021_status": source["status"],
                "candidate_status": candidate["status"],
                "decision_disposition": decision_disposition,
                "status_disposition": decision_disposition if status_changed else "KEEP",
                "content_disposition": "TEXT_FIX" if content_changed else "KEEP",
                "row_delta": (
                    "STATUS_AND_TEXT"
                    if status_changed
                    else "TEXT_ONLY"
                    if content_changed
                    else "UNCHANGED"
                ),
                "r021_assessment_sha256": source["assessment_sha256"],
                "candidate_assessment_sha256": candidate["assessment_sha256"],
                "decision_reason": DECISION_REASON[gap_id],
                "internal_residual": candidate["remediation"],
                "external_residual": (
                    f"연결 정식 시험 {', '.join(source.get('planned_test_ids', [])) or '없음'}은 "
                    "NOT_RUN이며, 실기기·참여자·외부 승인·출시 gate 완료를 주장하지 않는다."
                ),
                "evidence_locators": locators_by_gap[gap_id],
                "formal_test_credit": 0,
                "actual_device_credit": 0,
                "external_approval_credit": 0,
                "release_credit": 0,
            }
        )
    status_counts = dict(sorted(Counter(row["candidate_status"] for row in records).items()))
    disposition_counts = dict(
        sorted(Counter(row["decision_disposition"] for row in records).items())
    )
    _require(
        status_counts
        == {
            "BLOCKED": 5,
            "CONFLICTING": 14,
            "EVIDENCE_MISSING": 4,
            "MISSING": 6,
            "PARTIAL": 39,
        },
        f"candidate status counts differ: {status_counts}",
    )
    _require(
        disposition_counts
        == {
            "KEEP": 33,
            "NEEDS_EVIDENCE": 4,
            "REASSESS_DOWN": 1,
            "REASSESS_UP": 7,
            "TEXT_FIX": 23,
        },
        f"decision disposition counts differ: {disposition_counts}",
    )
    ledger = {
        "schema_version": "walksafe.plan-rebaseline.exact68-reassessment-ledger.v1",
        "metadata": {
            "ledger_id": "WS-WALKSAFE-EXACT68-REASSESSMENT-20260730-R002",
            "version": "1.1.0",
            "prepared_at": PREPARED_AT,
            "status": "REVIEWED_STAGED_NOT_CANONICAL",
        },
        "purpose": (
            "현재 dirty worktree의 68개 정책↔Gap 쌍을 빠짐없이 재검토하고, r022 "
            "후보 assessment hash와 live evidence를 결속한다."
        ),
        "source_gap": {
            "path": _relative(R021_GAP_PATH),
            "file_sha256": _sha256_file(R021_GAP_PATH),
            "bytes": R021_GAP_PATH.stat().st_size,
            "report_content_sha256": source_gap["report_content_sha256"],
        },
        "mapping_projection": mapping_projection,
        "mapping_sha256": _object_sha256(mapping_projection),
        "hard_dependency_projection": dependency_projection,
        "hard_dependency_sha256": _object_sha256(dependency_projection),
        "evidence_snapshot": snapshot,
        "review_contract": {
            "reviewed_gap_count": 68,
            "changed_gap_count": len(changed_ids),
            "carried_forward_gap_count": len(carried_ids),
            "status_changed_gap_count": len(STATUS_CHANGES),
            "test_source_is_not_test_execution": True,
            "negative_claims_require_current_evidence": True,
            "implemented_claim_allowed": False,
            "decision_dispositions": [
                "KEEP",
                "REASSESS_UP",
                "REASSESS_DOWN",
                "TEXT_FIX",
                "NEEDS_EVIDENCE",
            ],
        },
        "records": records,
        "summary": {
            "status_counts": status_counts,
            "decision_disposition_counts": disposition_counts,
            "changed_gap_ids": sorted(changed_ids),
            "carried_forward_gap_ids": sorted(carried_ids),
            "status_changed_gap_ids": sorted(STATUS_CHANGES),
            "implemented_count": 0,
            "formal279_pass_count": 0,
            "actual_device_execution_count": 0,
            "closed_release_gate_count": 0,
            "release_status": "NOT_ELIGIBLE",
        },
        "authorization_boundary": {
            "canonical_gap_or_backlog_modified": False,
            "checkpoint_modified": False,
            "goal_event_appended": False,
            "work_item_materialized": False,
            "product_code_modified_by_this_reassessment": False,
            "separate_candidate_specific_activation_approval_required": True,
        },
    }
    return _sealed_object(ledger, "ledger_content_sha256")


def _gap_snapshot_from_evidence(snapshot: dict[str, Any]) -> dict[str, Any]:
    files = [
        {
            "name": f"exact68_evidence_{index:03d}",
            "path": record["path"],
            "sha256": record["sha256"],
            "bytes": record["bytes"],
            "relation": "LIVE_EVIDENCE_CURRENT_WORKTREE",
        }
        for index, record in enumerate(snapshot["files"], start=1)
    ]
    content_projection = [
        {
            "path": record["path"],
            "sha256": record["sha256"],
            "bytes": record["bytes"],
        }
        for record in snapshot["files"]
    ]
    result = {
        "scope_kind": "EXACT68_LIVE_EVIDENCE_CURRENT_WORKTREE",
        "base_commit": EXPECTED_HEAD,
        "current_head": snapshot["current_head"],
        "branch": snapshot["branch"],
        "dirty_worktree_expected": True,
        "whole_repository_frozen": False,
        "focused_scope_only": True,
        "excluded_from_scope": [
            "unrelated dirty worktree paths",
            "formal test execution",
            "actual device or participant execution",
            "external approval and release gates",
        ],
        "file_count": len(files),
        "path_set_sha256": _object_sha256([record["path"] for record in files]),
        "content_set_sha256": _object_sha256(content_projection),
        "files": files,
    }
    result["snapshot_sha256"] = _object_sha256(result)
    return result


def _build_gap_candidate(
    source_gap: dict[str, Any],
    candidate_rows: list[dict[str, Any]],
    changed_ids: set[str],
    carried_ids: set[str],
    snapshot: dict[str, Any],
    locators_by_gap: dict[str, list[dict[str, Any]]],
    ledger: dict[str, Any],
    ledger_bytes: bytes,
) -> dict[str, Any]:
    result = copy.deepcopy(source_gap)
    result["metadata"] = {
        **result["metadata"],
        "report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260726-022",
        "version": "0.22.0",
        "status": source_gap["metadata"]["status"],
        "prepared_at": PREPARED_AT,
        "predecessor_report_id": source_gap["metadata"]["report_id"],
    }
    result["purpose"] = (
        "현재 dirty worktree의 정책↔Gap 68쌍을 exact68 ledger로 모두 재검토하고, "
        "31개 assessment delta와 37개 byte-exact carry-forward를 staged r022 후보로 "
        "고정한다. 이 문서는 아직 canonical이 아니며 실행·출시 승인이 아니다."
    )
    result["assessments"] = candidate_rows
    result["reassessment_scope"] = {
        "mode": "EXACT68_REVIEW_WITH_31_ROW_DELTA_AND_37_BYTE_EXACT_CARRY_FORWARD",
        "directly_reassessed_gap_ids": sorted(changed_ids),
        "impact_reviewed_gap_ids": [],
        "reviewed_gap_ids": sorted(changed_ids),
        "assessment_delta_gap_ids": sorted(changed_ids),
        "byte_exact_carry_forward_gap_ids": sorted(carried_ids),
        "carried_forward_gap_count": len(carried_ids),
        "carried_forward_gap_ids": sorted(carried_ids),
        "carry_forward_warning": (
            "37개 assessment 객체는 exact68 live evidence 검토 뒤 r021 bytes를 그대로 "
            "유지했다. 68개 전체 검토 증거는 detached exact68 ledger에 있다."
        ),
        "next_adjacent_gap": {
            "gap_id": "GAP-017",
            "source_policy_id": "FP-008",
            "reason": (
                "EPIC-03의 완료된 FP-047 뒤 첫 미materialized 내부 leaf이며, r021의 "
                "FP-048 pointer는 FP-008·FP-046·NPC-SINGLE-ADMIN을 잘못 건너뛰었다."
            ),
        },
        "predecessor": {
            "report_id": source_gap["metadata"]["report_id"],
            "path": _relative(R021_GAP_PATH),
            "file_sha256": _sha256_file(R021_GAP_PATH),
            "report_content_sha256": source_gap["report_content_sha256"],
            "evidence_count": len(source_gap.get("evidence_catalog", [])),
            "assessment_count": 68,
            "exact68_live_review_bound_by_detached_ledger": True,
        },
    }
    result["implementation_snapshot"] = _gap_snapshot_from_evidence(snapshot)

    source_bindings = list(result.get("source_bindings", []))
    source_bindings.extend(
        [
            {
                "name": "r022_exact68_reassessment_ledger",
                "path": _relative(LEDGER_PATH),
                "bytes": len(ledger_bytes),
                "sha256": _sha256_bytes(ledger_bytes),
                "content_sha256": ledger["ledger_content_sha256"],
                "binding_kind": "STAGED_REVIEWED_INPUT_NOT_CANONICAL",
            },
            _source_binding("r022_candidate_generator", GENERATOR_PATH),
        ]
    )
    result["source_bindings"] = source_bindings
    result["source_binding_sha256"] = _object_sha256(source_bindings)

    evidence_catalog = list(result.get("evidence_catalog", []))
    for gap_id in sorted(changed_ids):
        files = [
            {
                "path": locator["path"],
                "sha256": locator["file_sha256"],
            }
            for locator in locators_by_gap[gap_id]
        ]
        evidence_catalog.append(
            {
                "evidence_id": f"EVD-R022-{gap_id}",
                "kind": "EXACT68_LIVE_CURRENT_WORKTREE_PATH_SET",
                "claim": DECISION_REASON[gap_id],
                "files": files,
                "path_set_content_sha256": _object_sha256(files),
                "formal_test_evidence": False,
                "actual_device_evidence": False,
            }
        )
    result["evidence_catalog"] = evidence_catalog
    result["critical_findings"] = [
        finding
        for finding in result.get("critical_findings", [])
        if finding.get("id") not in {"CF-03", "CF-07", "CF-08"}
    ] + [
        {
            "id": "CF-03",
            "title": "가입·동의·장기 세션 내부 코어는 있으나 production 공급자·증거·활성화 미완료",
            "source_ids": ["FP-010", "FP-011", "FP-013"],
            "evidence_ids": ["EVD-R022-GAP-022"],
        },
        {
            "id": "CF-07",
            "title": "release 원본 수집 경로가 없고 삭제 상태 구현도 실제 저장소 worker와 연결되지 않음",
            "source_ids": [
                "FP-034",
                "FP-036",
                "FP-046",
                "NPC-RAW-ORIGINAL-COLLECTION",
                "NPC-DATA-LIFECYCLE",
            ],
            "evidence_ids": [
                "EVD-R022-GAP-002",
                "EVD-R022-GAP-045",
                "EVD-R022-GAP-055",
            ],
        },
        {
            "id": "CF-08",
            "title": "내부 계정·관리자 권한 코어는 있으나 단일 운영 진입·용량 계약·배포 미완료",
            "source_ids": [
                "FP-040",
                "FP-041",
                "FP-047",
                "NPC-SERVER-CAPACITY-STATE-SYNC",
            ],
            "evidence_ids": ["EVD-R022-GAP-049"],
        },
        {
            "id": "CF-11",
            "title": "Android와 Gateway의 계정 삭제 wire contract가 직접 불일치",
            "source_ids": ["FP-046", "NPC-DATA-LIFECYCLE"],
            "evidence_ids": ["EVD-R022-GAP-002", "EVD-R022-GAP-055"],
        },
    ]
    result["critical_findings"].sort(key=lambda finding: finding["id"])

    status_counts = Counter(row["status"] for row in candidate_rows)
    result["summary"] = {
        **result["summary"],
        "status_counts": {
            "BLOCKED": status_counts["BLOCKED"],
            "CONFLICTING": status_counts["CONFLICTING"],
            "EVIDENCE_MISSING": status_counts["EVIDENCE_MISSING"],
            "MISSING": status_counts["MISSING"],
            "PARTIAL": status_counts["PARTIAL"],
            "IMPLEMENTED": status_counts["IMPLEMENTED"],
        },
        "implemented_and_formally_verified_count": 0,
        "release_status": "NOT_ELIGIBLE",
        "headline": (
            "68개 전체를 현재 저장소에서 재검토했다. 31개 assessment가 변경되고 "
            "37개는 byte-exact 유지된다. 상태는 BLOCKED 5 / CONFLICTING 14 / "
            "EVIDENCE_MISSING 4 / MISSING 6 / PARTIAL 39 / IMPLEMENTED 0이며, "
            "formal 0/279·actual device 0·release gate 0/5를 유지한다."
        ),
    }
    result["authorization_boundary"] = copy.deepcopy(
        source_gap["authorization_boundary"]
    )
    result["limitations"] = [
        "The exact68 reassessment is static review of the current dirty worktree.",
        "Test source presence is not test execution or PASS evidence.",
        "All 279 formal tests remain NOT_RUN and no actual-device execution is claimed.",
        "Five release gates remain NOT_RUN and unwaived; release is NOT_ELIGIBLE.",
        "The staged r022 pair is non-effective until separate candidate-specific approval.",
        "GAP-055 is CONFLICTING because the Android and Gateway deletion wire contracts differ.",
    ]
    result.pop("report_content_sha256", None)
    return _sealed_object(result, "report_content_sha256")


def _refresh_embedded_statuses(
    value: Any,
    source_status_by_policy: dict[str, str],
    candidate_status_by_policy: dict[str, str],
) -> Any:
    if isinstance(value, list):
        return [
            _refresh_embedded_statuses(
                item,
                source_status_by_policy,
                candidate_status_by_policy,
            )
            for item in value
        ]
    if not isinstance(value, dict):
        return value
    result = {
        key: _refresh_embedded_statuses(
            item,
            source_status_by_policy,
            candidate_status_by_policy,
        )
        for key, item in value.items()
    }
    policy_id = result.get("source_policy_id")
    if isinstance(policy_id, str) and policy_id in candidate_status_by_policy:
        source_status = source_status_by_policy[policy_id]
        candidate_status = candidate_status_by_policy[policy_id]
        if result.get("status") == source_status:
            result["status"] = candidate_status
        if result.get("status_after") == source_status:
            result["status_after"] = candidate_status
    return result


def _build_backlog_candidate(
    source_backlog: dict[str, Any],
    source_gap: dict[str, Any],
    gap_candidate: dict[str, Any],
    candidate_by_id: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    result = copy.deepcopy(source_backlog)
    result["metadata"] = {
        **result["metadata"],
        "backlog_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260726-022",
        "version": "0.22.0",
        "status": source_backlog["metadata"]["status"],
        "prepared_at": PREPARED_AT,
        "predecessor_backlog_id": source_backlog["metadata"]["backlog_id"],
    }
    result["source_predecessor"] = {
        "path": _relative(R021_BACKLOG_PATH),
        "file_sha256": _sha256_file(R021_BACKLOG_PATH),
        "preserved_unchanged": True,
    }
    source_status_by_policy = {
        row["source_policy_id"]: row["status"]
        for row in source_gap["assessments"]
    }
    candidate_by_policy = {
        row["source_policy_id"]: row
        for row in candidate_by_id.values()
    }
    candidate_status_by_policy = {
        policy_id: row["status"]
        for policy_id, row in candidate_by_policy.items()
    }
    result["epics"] = _refresh_embedded_statuses(
        result["epics"],
        source_status_by_policy,
        candidate_status_by_policy,
    )
    for epic in result["epics"]:
        if epic.get("epic_id") == "EPIC-02":
            epic["current_status"] = "IN_PROGRESS"
            epic["current_status_reason"] = (
                "13/13 내부 leaf coverage는 있으나 Workstream container 완료 전이가 "
                "기록되지 않았다. r021의 EPIC-03 FP-047 교차참조는 제거 대상이며, "
                "별도 Goal event 전에는 상태를 임의로 완료하지 않는다."
            )
        elif epic.get("epic_id") == "EPIC-03":
            epic["current_status"] = "IN_PROGRESS"
            epic["current_status_reason"] = (
                "FP-047 내부 authority-separation leaf가 완료됐다. 다음 내부 leaf 후보는 "
                "순서상 FP-008이며 FP-046, NPC-SINGLE-ADMIN, FP-048이 뒤따른다."
            )

    sequence: list[dict[str, Any]] = []
    source_gap_by_policy = {
        row["source_policy_id"]: row
        for row in source_gap["assessments"]
    }
    for source_item in result["next_action_sequence"]:
        item = copy.deepcopy(source_item)
        policy_id = item.get("source_policy_id")
        if isinstance(policy_id, str) and policy_id in candidate_by_policy:
            candidate = candidate_by_policy[policy_id]
            item["status"] = candidate["status"]
            source_gap_row = source_gap_by_policy[policy_id]
            if source_item.get("action") == source_gap_row.get("remediation"):
                item["action"] = candidate["remediation"]
        sequence.append(item)
    result["next_action_sequence"] = sequence
    fp008 = candidate_by_id["GAP-017"]
    result["next_single_action"] = {
        "epic_id": "EPIC-03",
        "work_item_id": "EPIC-03-FP008-ADMIN-REVIEW-DELIVERY",
        "source_policy_id": "FP-008",
        "gap_id": "GAP-017",
        "status": "PLANNED_NEXT_NOT_MATERIALIZED",
        "action": fp008["remediation"],
    }
    result["gap_report_content_sha256"] = gap_candidate["report_content_sha256"]
    result["authorization_boundary"] = copy.deepcopy(
        source_backlog["authorization_boundary"]
    )
    result.pop("backlog_content_sha256", None)
    return _sealed_object(result, "backlog_content_sha256")


def _document_binding(
    role: str,
    candidate_path: Path,
    intended_path: str,
    payload: dict[str, Any],
    payload_bytes: bytes,
    content_field: str,
    document_id: str,
) -> dict[str, Any]:
    return {
        "role": role,
        "candidate_path": _relative(candidate_path),
        "intended_canonical_path": intended_path,
        "document_id": document_id,
        "content_sha256": payload[content_field],
        "file_sha256": _sha256_bytes(payload_bytes),
        "bytes": len(payload_bytes),
    }


def _build_pair_manifest(
    source_gap: dict[str, Any],
    source_backlog: dict[str, Any],
    ledger: dict[str, Any],
    ledger_bytes: bytes,
    gap_candidate: dict[str, Any],
    gap_bytes: bytes,
    backlog_candidate: dict[str, Any],
    backlog_bytes: bytes,
    changed_ids: set[str],
    carried_ids: set[str],
    mapping_projection: list[dict[str, str]],
    dependency_projection: list[dict[str, str]],
) -> dict[str, Any]:
    gap_row_by_id = {
        row["gap_id"]: row
        for row in gap_candidate["assessments"]
    }
    gap_impact_subject_ids = sorted(
        {
            "*",
            *changed_ids,
            *(
                gap_row_by_id[gap_id]["source_policy_id"]
                for gap_id in changed_ids
            ),
        }
    )
    source_backlog_sequence = {
        row["source_policy_id"]: row
        for row in source_backlog["next_action_sequence"]
    }
    candidate_backlog_sequence = {
        row["source_policy_id"]: row
        for row in backlog_candidate["next_action_sequence"]
    }
    backlog_changed_policy_ids = {
        policy_id
        for policy_id in source_backlog_sequence
        if source_backlog_sequence[policy_id]
        != candidate_backlog_sequence[policy_id]
    }
    backlog_impact_subject_ids = sorted({"*", *backlog_changed_policy_ids})
    documents = [
        _document_binding(
            "IMPLEMENTATION_GAP",
            GAP_CANDIDATE_PATH,
            INTENDED_GAP_PATH,
            gap_candidate,
            gap_bytes,
            "report_content_sha256",
            gap_candidate["metadata"]["report_id"],
        ),
        _document_binding(
            "IMPLEMENTATION_BACKLOG",
            BACKLOG_CANDIDATE_PATH,
            INTENDED_BACKLOG_PATH,
            backlog_candidate,
            backlog_bytes,
            "backlog_content_sha256",
            backlog_candidate["metadata"]["backlog_id"],
        ),
    ]
    pair_projection = [
        {
            "role": document["role"],
            "document_id": document["document_id"],
            "intended_path": document["intended_canonical_path"],
            "content_sha256": document["content_sha256"],
            "file_sha256": document["file_sha256"],
            "bytes": document["bytes"],
        }
        for document in documents
    ]
    manifest = {
        "schema_version": "walksafe.plan-rebaseline.r022-gap-backlog-pair-manifest.v1",
        "metadata": {
            "manifest_id": "WS-WALKSAFE-R022-GAP-BACKLOG-CANDIDATE-20260730-R002",
            "version": "1.1.0",
            "prepared_at": PREPARED_AT,
            "status": "STAGED_CANDIDATE_NOT_APPLIED",
        },
        "classification": "STAGED_R022_CANDIDATE_NOT_APPLIED",
        "content_classification": "V2_4_CONTENT_SCOPE_COMPATIBLE_CHANGED31_CARRY37",
        "application_route": "VERSIONED_SUCCESSOR_CONTROL_REQUIRED_FOR_OPERATIONAL_DELTA",
        "supersedes_candidate": {
            "manifest_path": _relative(R001_PAIR_MANIFEST_PATH),
            "file_sha256": _sha256_file(R001_PAIR_MANIFEST_PATH),
            "bytes": R001_PAIR_MANIFEST_PATH.stat().st_size,
            "manifest_content_sha256": load_strict_json(R001_PAIR_MANIFEST_PATH)[
                "manifest_content_sha256"
            ],
            "superseded_status": "SUPERSEDED_BY_CORRECTED_R002_CANDIDATE",
            "reasons": [
                "specialized post-internal-completion Backlog actions were overwritten",
                "structured evidence locators were not resolved as JSON Pointers",
                "exact68 review scope and decision dispositions were ambiguous",
            ],
        },
        "source_bindings": [
            _source_binding("implementation_gap_r021", R021_GAP_PATH),
            _source_binding("implementation_backlog_r021", R021_BACKLOG_PATH),
            _source_binding("rebaseline_plan_manifest", PLAN_MANIFEST_PATH),
            _source_binding("plan_19_row_candidate_input", PLAN_CANDIDATES_PATH),
            _source_binding("plan_31_path_evidence_input", PLAN_EVIDENCE_PATH),
            _source_binding("static_plan_v2_4", STATIC_PLAN_PATH),
            _source_binding("candidate_generator", GENERATOR_PATH),
            _source_binding("superseded_r001_pair_manifest", R001_PAIR_MANIFEST_PATH),
        ],
        "exact68_ledger": {
            "path": _relative(LEDGER_PATH),
            "content_sha256": ledger["ledger_content_sha256"],
            "file_sha256": _sha256_bytes(ledger_bytes),
            "bytes": len(ledger_bytes),
            "reviewed_gap_count": 68,
            "changed_gap_count": len(changed_ids),
            "carried_forward_gap_count": len(carried_ids),
        },
        "documents": documents,
        "pair_projection": pair_projection,
        "pair_fingerprint_sha256": _object_sha256(pair_projection),
        "static_fingerprints": {
            "policy_gap_mapping_sha256": _object_sha256(mapping_projection),
            "hard_dependency_sha256": _object_sha256(dependency_projection),
            "mapping_changed": False,
            "hard_dependency_changed": False,
            "static_schema_changed": False,
        },
        "candidate_result": {
            "assessment_count": 68,
            "changed_gap_ids": sorted(changed_ids),
            "carried_forward_gap_ids": sorted(carried_ids),
            "status_changed_gap_ids": sorted(STATUS_CHANGES),
            "status_counts": gap_candidate["summary"]["status_counts"],
            "next_single_action": backlog_candidate["next_single_action"],
            "implemented_count": 0,
            "formal279_pass_count": 0,
            "actual_device_execution_count": 0,
            "closed_release_gate_count": 0,
            "release_gate_count": 5,
            "release_status": "NOT_ELIGIBLE",
        },
        "activation_impact_boundary": {
            "projection_kind": "SUCCESSOR_EVENT_SUBJECT_NAMESPACE_CANDIDATE",
            "changed_subject_ids_by_role": {
                "IMPLEMENTATION_GAP": gap_impact_subject_ids,
                "IMPLEMENTATION_BACKLOG": backlog_impact_subject_ids,
            },
            "backlog_changed_policy_count": len(backlog_changed_policy_ids),
            "global_impact_roles": [
                "IMPLEMENTATION_BACKLOG",
                "IMPLEMENTATION_GAP",
            ],
            "reason": (
                "r021의 비정규 implementation_snapshot, source_predecessor와 "
                "next_single_action 형식을 정상화하므로 role-global impact가 발생한다. "
                "Gap namespace는 changed assessment의 policy+Gap ID, Backlog namespace는 "
                "실제로 바뀐 next_action_sequence의 policy ID만 사용한다."
            ),
            "v2_4_scope_compatibility": "CONTENT_SCOPE_COMPATIBLE_CHANGED31_CARRY37",
        },
        "activation_blockers": [
            "A separate candidate-specific approval is required before canonical application.",
            "Bulk r022 cannot be produced by the singular POLICY_GAP_WORK producer contract.",
            "GAP-055 becomes CONFLICTING, which is outside that producer terminal-status contract.",
            "The current v2.4 checker also forbids this Backlog operational delta in a producer-less transaction.",
            "Activation therefore needs an approved migration exception or successor control contract.",
            "Checkpoint, Goal events and the FP-008 leaf remain unchanged and unmaterialized.",
        ],
        "authorization_boundary": {
            "canonical_files_written": False,
            "checkpoint_modified": False,
            "goal_graph_modified": False,
            "goal_event_appended": False,
            "next_work_item_materialized": False,
            "product_code_modified": False,
            "formal_or_actual_execution_claimed": False,
            "gate_waived": False,
            "release_eligible": False,
            "separate_candidate_specific_activation_approval_required": True,
        },
        "predecessor_integrity": {
            "gap_r021_file_sha256": _sha256_file(R021_GAP_PATH),
            "gap_r021_content_sha256": source_gap["report_content_sha256"],
            "backlog_r021_file_sha256": _sha256_file(R021_BACKLOG_PATH),
            "backlog_r021_content_sha256": source_backlog["backlog_content_sha256"],
        },
    }
    return _sealed_object(manifest, "manifest_content_sha256")


def build_outputs() -> dict[Path, bytes]:
    (
        source_gap,
        source_backlog,
        plan_manifest,
        _plan_candidates,
        _plan_evidence,
        _static_plan,
    ) = _validate_pinned_sources()
    mapping_projection = _mapping_projection(source_gap)
    dependency_projection = _hard_dependency_projection(plan_manifest)
    snapshot, locators_by_gap = _build_evidence_snapshot()
    (
        candidate_rows,
        candidate_by_id,
        changed_ids,
        carried_ids,
    ) = _candidate_assessments(source_gap)
    ledger = _build_ledger(
        source_gap,
        candidate_by_id,
        changed_ids,
        carried_ids,
        snapshot,
        locators_by_gap,
        mapping_projection,
        dependency_projection,
    )
    ledger_bytes = _pretty_bytes(ledger)
    gap_candidate = _build_gap_candidate(
        source_gap,
        candidate_rows,
        changed_ids,
        carried_ids,
        snapshot,
        locators_by_gap,
        ledger,
        ledger_bytes,
    )
    gap_bytes = _pretty_bytes(gap_candidate)
    backlog_candidate = _build_backlog_candidate(
        source_backlog,
        source_gap,
        gap_candidate,
        candidate_by_id,
    )
    backlog_bytes = _pretty_bytes(backlog_candidate)
    pair_manifest = _build_pair_manifest(
        source_gap,
        source_backlog,
        ledger,
        ledger_bytes,
        gap_candidate,
        gap_bytes,
        backlog_candidate,
        backlog_bytes,
        changed_ids,
        carried_ids,
        mapping_projection,
        dependency_projection,
    )
    outputs = {
        LEDGER_PATH: ledger_bytes,
        GAP_CANDIDATE_PATH: gap_bytes,
        BACKLOG_CANDIDATE_PATH: backlog_bytes,
        PAIR_MANIFEST_PATH: _pretty_bytes(pair_manifest),
    }
    validate_outputs(outputs)
    return outputs


def _decode_output(outputs: dict[Path, bytes], path: Path) -> dict[str, Any]:
    value = json.loads(
        outputs[path].decode("utf-8"),
        object_pairs_hook=_strict_pairs,
        parse_constant=lambda token: (_ for _ in ()).throw(
            R022CandidateError(f"non-standard JSON number: {token}")
        ),
    )
    _require(isinstance(value, dict), f"generated root is not object: {_relative(path)}")
    return value


def validate_outputs(outputs: dict[Path, bytes]) -> None:
    _require(
        set(outputs)
        == {
            LEDGER_PATH,
            GAP_CANDIDATE_PATH,
            BACKLOG_CANDIDATE_PATH,
            PAIR_MANIFEST_PATH,
        },
        "generated output set differs",
    )
    _require(
        all(value.endswith(b"\n") for value in outputs.values()),
        "generated JSON lacks final LF",
    )
    ledger = _decode_output(outputs, LEDGER_PATH)
    gap = _decode_output(outputs, GAP_CANDIDATE_PATH)
    backlog = _decode_output(outputs, BACKLOG_CANDIDATE_PATH)
    manifest = _decode_output(outputs, PAIR_MANIFEST_PATH)
    _require(_seal_is_valid(ledger, "ledger_content_sha256"), "ledger seal differs")
    _require(_seal_is_valid(gap, "report_content_sha256"), "candidate Gap seal differs")
    _require(_seal_is_valid(backlog, "backlog_content_sha256"), "candidate Backlog seal differs")
    _require(_seal_is_valid(manifest, "manifest_content_sha256"), "pair manifest seal differs")
    _require(
        backlog["gap_report_content_sha256"] == gap["report_content_sha256"],
        "candidate Gap/Backlog logical binding differs",
    )
    records = ledger.get("records")
    _require(isinstance(records, list) and len(records) == 68, "ledger is not exact68")
    _require(
        len({row.get("gap_id") for row in records if isinstance(row, dict)}) == 68,
        "ledger has duplicate/missing gap IDs",
    )
    changed = set(ledger["summary"]["changed_gap_ids"])
    carried = set(ledger["summary"]["carried_forward_gap_ids"])
    _require(len(changed) == 31 and len(carried) == 37, "31/37 split differs")
    _require(changed.isdisjoint(carried), "31/37 split overlaps")
    _require(
        set(ledger["summary"]["status_changed_gap_ids"]) == set(STATUS_CHANGES),
        "status8 set differs",
    )
    gap_rows = {row["gap_id"]: row for row in gap["assessments"]}
    source_gap = load_strict_json(R021_GAP_PATH)
    source_rows = {row["gap_id"]: row for row in source_gap["assessments"]}
    for gap_id in carried:
        _require(gap_rows[gap_id] == source_rows[gap_id], f"carry row differs: {gap_id}")
    for gap_id in changed:
        _require(gap_rows[gap_id] != source_rows[gap_id], f"changed row did not change: {gap_id}")
    _require(
        gap_rows["GAP-055"]["status"] == "CONFLICTING",
        "GAP-055 must be CONFLICTING",
    )
    _require(
        gap["summary"]["status_counts"]
        == {
            "BLOCKED": 5,
            "CONFLICTING": 14,
            "EVIDENCE_MISSING": 4,
            "MISSING": 6,
            "PARTIAL": 39,
            "IMPLEMENTED": 0,
        },
        "candidate Gap status counts differ",
    )
    _require(
        all(
            row.get("formal_test_status") == "NOT_RUN"
            and row.get("status") != "IMPLEMENTED"
            and _seal_is_valid(row, "assessment_sha256")
            for row in gap["assessments"]
        ),
        "candidate assessment boundary or seal differs",
    )
    _require(
        backlog["source_predecessor"]["preserved_unchanged"] is True,
        "Backlog predecessor normalization differs",
    )
    _require(
        backlog["next_single_action"]
        == {
            "epic_id": "EPIC-03",
            "work_item_id": "EPIC-03-FP008-ADMIN-REVIEW-DELIVERY",
            "source_policy_id": "FP-008",
            "gap_id": "GAP-017",
            "status": "PLANNED_NEXT_NOT_MATERIALIZED",
            "action": gap_rows["GAP-017"]["remediation"],
        },
        "derived next single action differs",
    )
    _require(
        manifest["classification"] == "STAGED_R022_CANDIDATE_NOT_APPLIED",
        "candidate classification differs",
    )
    _require(
        manifest["content_classification"]
        == "V2_4_CONTENT_SCOPE_COMPATIBLE_CHANGED31_CARRY37",
        "candidate content classification differs",
    )
    _require(
        manifest["application_route"]
        == "VERSIONED_SUCCESSOR_CONTROL_REQUIRED_FOR_OPERATIONAL_DELTA",
        "candidate application route differs",
    )
    _require(
        manifest["static_fingerprints"]["policy_gap_mapping_sha256"]
        == EXPECTED_MAPPING_SHA256,
        "manifest mapping fingerprint differs",
    )
    _require(
        manifest["static_fingerprints"]["hard_dependency_sha256"]
        == EXPECTED_HARD_DEPENDENCY_SHA256,
        "manifest hard-dependency fingerprint differs",
    )
    documents = manifest["documents"]
    _require(len(documents) == 2, "pair manifest document count differs")
    expected_document_bytes = {
        "IMPLEMENTATION_GAP": outputs[GAP_CANDIDATE_PATH],
        "IMPLEMENTATION_BACKLOG": outputs[BACKLOG_CANDIDATE_PATH],
    }
    for document in documents:
        payload = expected_document_bytes[document["role"]]
        _require(document["file_sha256"] == _sha256_bytes(payload), "document file hash differs")
        _require(document["bytes"] == len(payload), "document byte length differs")
    expected_pair_projection = [
        {
            "role": document["role"],
            "document_id": document["document_id"],
            "intended_path": document["intended_canonical_path"],
            "content_sha256": document["content_sha256"],
            "file_sha256": document["file_sha256"],
            "bytes": document["bytes"],
        }
        for document in documents
    ]
    _require(
        manifest["pair_projection"] == expected_pair_projection
        and manifest["pair_fingerprint_sha256"] == _object_sha256(expected_pair_projection),
        "pair fingerprint differs",
    )
    _require(
        manifest["authorization_boundary"]["canonical_files_written"] is False
        and manifest["authorization_boundary"]["checkpoint_modified"] is False
        and manifest["authorization_boundary"]["goal_graph_modified"] is False
        and manifest["authorization_boundary"]["product_code_modified"] is False,
        "candidate authorization boundary differs",
    )
    for path, (expected_hash, expected_bytes) in EXPECTED_SOURCE_BINDINGS.items():
        _require(
            _sha256_file(path) == expected_hash and path.stat().st_size == expected_bytes,
            f"source mutated during build: {_relative(path)}",
        )


def _write_add_only(outputs: dict[Path, bytes]) -> None:
    _require(not OUTPUT_DIR.exists(), f"output directory already exists: {_relative(OUTPUT_DIR)}")
    OUTPUT_DIR.mkdir(parents=False, exist_ok=False)
    for path, payload in outputs.items():
        _require(path.parent == OUTPUT_DIR, f"output escaped stage directory: {path}")
        with path.open("xb") as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())


def _check_committed(outputs: dict[Path, bytes]) -> None:
    for path, expected in outputs.items():
        _require(path.is_file(), f"generated output is missing: {_relative(path)}")
        actual = path.read_bytes()
        _require(actual == expected, f"generated output is stale: {_relative(path)}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="validate sources and require the staged outputs to match without writing",
    )
    args = parser.parse_args(argv)
    try:
        outputs = build_outputs()
        if args.check:
            _check_committed(outputs)
            mode = "CHECK"
        else:
            _write_add_only(outputs)
            mode = "WRITE"
    except R022CandidateError as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1
    manifest = _decode_output(outputs, PAIR_MANIFEST_PATH)
    print(
        "PASS "
        f"mode={mode} "
        "reviewed=68 changed=31 carry=37 status_changed=8 "
        "counts=B5/C14/E4/M6/P39/I0 "
        f"next={manifest['candidate_result']['next_single_action']['work_item_id']} "
        "canonical=NOT_APPLIED release=NOT_ELIGIBLE"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

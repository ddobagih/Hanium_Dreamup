#!/usr/bin/env python3
"""Build deterministic diagrams used by the 2026 WalkSafe submission forms."""

from __future__ import annotations

import sys

if __name__ == "__main__":
    _verified_submission_commit = getattr(
        sys.modules.get("__main__"), "_walksafe_submission_source_commit", None
    )
    _startup_flags = (
        sys.flags.isolated,
        sys.flags.no_site,
        sys.flags.ignore_environment,
        int(getattr(sys.flags, "safe_path", False)),
        sys.flags.dont_write_bytecode,
    )
    if (
        any(value != 1 for value in _startup_flags)
        or "site" in sys.modules
        or not isinstance(_verified_submission_commit, str)
        or len(_verified_submission_commit) != 40
        or any(character not in "0123456789abcdef" for character in _verified_submission_commit)
    ):
        raise SystemExit(
            "WalkSafe submission CLI requires Python -I -S -B through the submission runner"
        )

import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, PngImagePlugin

from submission_build_io import (
    atomic_output_path,
    canonical_android_device_evidence,
    submission_build_lock,
    verification_snapshot_policy_boundary,
)
from submission_manifest_policy import (
    ALL_SUBMISSION_GENERATED_PATHS,
    ASSET_DIRECTORY_FILES,
    ASSET_REPOSITORY_INPUT_PATHS,
    GENERATED_ASSET_ARTIFACT_NAMES,
    build_tool_provenance,
    file_record,
    record_bundle_sha256,
    require_exact_file_set,
    require_no_unexpected_files,
    sha256,
    require_clean_source_revision,
    submission_toolchain_attestation,
)


REPO_ROOT = Path(__file__).resolve().parents[1]
OUTPUT_DIR = REPO_ROOT / "docs/submission/form_materials/assets"
FACTS_PATH = REPO_ROOT / "docs/submission/form_materials/09_제출_사실_기준.json"
MANIFEST_PATH = OUTPUT_DIR / "BUILD_MANIFEST.json"
EVIDENCE_RESULTS_PATH = OUTPUT_DIR / "evidence_results.png"
EVIDENCE_RESULTS_SEMANTIC_PATH = OUTPUT_DIR / "evidence_results.semantic.json"
EVIDENCE_RESULTS_PNG_SEMANTIC_KEY = "walksafe.evidence.rendered-fields.v1"
FONT_REGULAR = Path("/usr/share/fonts/truetype/nanum/NanumSquareR.ttf")
FONT_BOLD = Path("/usr/share/fonts/truetype/nanum/NanumSquareB.ttf")
BUILD_INPUT_PATHS = tuple(
    REPO_ROOT / relative for relative in sorted(ASSET_REPOSITORY_INPUT_PATHS)
)

CANVAS = (1600, 900)
BG = "#F7F5EE"
INK = "#10201C"
MUTED = "#4F5E57"
WHITE = "#FFFFFF"
GREEN = "#0B6B58"
BLUE = "#2364D2"
RED = "#B93422"
GOLD = "#B8860B"
BORDER = "#D8D3C8"


def load_facts() -> dict[str, object]:
    with FACTS_PATH.open(encoding="utf-8") as handle:
        facts = json.load(handle)
    if facts.get("schema_version") != "walksafe.submission_facts.v2":
        raise SystemExit(f"unexpected facts schema: {facts.get('schema_version')!r}")
    return facts


FACTS = load_facts()


def android_device_verification_spec(
    snapshot: dict[str, object],
) -> tuple[str, str, list[dict[str, object]]]:
    try:
        current, historical, _, _ = (
            canonical_android_device_evidence(snapshot)
        )
    except ValueError as exc:
        raise SystemExit(str(exc)) from exc
    current_text = "현재 source-freeze: NOT RUN"
    historical_text = (
        f"과거 evidence {historical['executed_at']}\n"
        f"{historical['device']} · instrumentation "
        f"{historical['passed']}/{historical['total']} PASS"
    )
    rendered_fields = [
        {
            "fact_path": f"verification_snapshot.android_device_verification.{section}.{key}",
            "card_id": "android_device",
            "value": record[key],
            "rendered_text": current_text if section == "current_source_freeze" else historical_text,
        }
        for section, record, keys in (
            (
                "current_source_freeze",
                current,
                ("status", "executed_at", "passed", "total"),
            ),
            (
                "historical_evidence",
                historical,
                ("status", "executed_at", "device", "scope", "passed", "total"),
            ),
        )
        for key in keys
    ]
    return current_text, historical_text, rendered_fields


def android_device_architecture_body(snapshot: dict[str, object]) -> str:
    try:
        _, historical, _, _ = canonical_android_device_evidence(snapshot)
    except ValueError as exc:
        raise SystemExit(str(exc)) from exc
    return (
        "ARCore metric/LiteRT\n"
        "미지원: CameraX+fresh IMU\n"
        "비계량 · reports=false\n"
        "현재 source-freeze\n"
        "NOT RUN\n"
        f"과거 {historical['executed_at']}\n"
        f"{historical['device']} {historical['passed']}/{historical['total']} PASS\n"
        "Field PASS 없음"
    )


def evidence_results_spec(
    facts: dict[str, object],
) -> tuple[dict[str, str], list[dict[str, object]]]:
    snapshot = facts.get("verification_snapshot")
    counts = snapshot.get("counts") if isinstance(snapshot, dict) else None
    snapshot_status = snapshot.get("status") if isinstance(snapshot, dict) else None
    snapshot_date = snapshot.get("executed_at") if isinstance(snapshot, dict) else None
    snapshot_policy = snapshot.get("policy") if isinstance(snapshot, dict) else None
    expected_keys = {
        "unit_python",
        "functional_python",
        "integration_python",
        "backend_full",
        "voice",
        "android_jvm",
        "web_trace_count",
        "web_trace_files",
    }
    if (
        not isinstance(counts, dict)
        or set(counts) != expected_keys
        or any(isinstance(value, bool) or not isinstance(value, int) or value < 1 for value in counts.values())
    ):
        raise SystemExit(
            "verification_snapshot.counts must contain the exact positive integer count contract"
        )
    if not isinstance(snapshot_status, str) or not snapshot_status or not isinstance(snapshot_date, str):
        raise SystemExit("verification_snapshot must declare status and executed_at")
    current_device_text, historical_device_text, device_rendered_fields = (
        android_device_verification_spec(snapshot)
    )
    try:
        snapshot_boundary = verification_snapshot_policy_boundary(
            snapshot_status,
            snapshot_date,
            snapshot_policy,
        )
    except ValueError as exc:
        raise SystemExit(str(exc)) from exc
    snapshot_scope = f"{snapshot_date} {snapshot_boundary}"

    policy_unit = (
        f"DB 없는 Python selected unit {counts['unit_python']:,}\n"
        "Web policy·voice callback dispatch\n"
        f"Android JVM {counts['android_jvm']:,}/{counts['android_jvm']:,}"
    )
    backend_db = (
        f"격리 PostGIS functional {counts['functional_python']:,}\n"
        f"integration {counts['integration_python']:,}\n"
        f"Backend full {counts['backend_full']:,}"
    )
    web_voice_build = (
        "Web test·typecheck·lint·production build\n"
        f"NFT {counts['web_trace_count']:,} traces/{counts['web_trace_files']:,} files\n"
        f"Voice {counts['voice']:,}"
    )
    android_device = (
        "debug·AndroidTest build\n"
        f"{current_device_text}\n"
        f"{historical_device_text}"
    )

    trace_text = f"NFT {counts['web_trace_count']:,} traces/{counts['web_trace_files']:,} files"
    field_rendering = {
        "unit_python": ("policy_unit", f"Python selected unit {counts['unit_python']:,}"),
        "android_jvm": (
            "policy_unit",
            f"Android JVM {counts['android_jvm']:,}/{counts['android_jvm']:,}",
        ),
        "functional_python": ("backend_db", f"PostGIS functional {counts['functional_python']:,}"),
        "integration_python": ("backend_db", f"integration {counts['integration_python']:,}"),
        "backend_full": ("backend_db", f"Backend full {counts['backend_full']:,}"),
        "web_trace_count": ("web_voice_build", trace_text),
        "web_trace_files": ("web_voice_build", trace_text),
        "voice": ("web_voice_build", f"Voice {counts['voice']:,}"),
    }
    rendered_fields = [
        {
            "fact_path": "verification_snapshot.status",
            "card_id": "snapshot_scope",
            "value": snapshot_status,
            "rendered_text": snapshot_scope,
        },
        {
            "fact_path": "verification_snapshot.executed_at",
            "card_id": "snapshot_scope",
            "value": snapshot_date,
            "rendered_text": snapshot_scope,
        },
        *[
            {
                "fact_path": f"verification_snapshot.counts.{key}",
                "card_id": card_id,
                "value": counts[key],
                "rendered_text": rendered_text,
            }
            for key, (card_id, rendered_text) in field_rendering.items()
        ],
        *device_rendered_fields,
    ]
    return (
        {
            "policy_unit": policy_unit,
            "backend_db": backend_db,
            "web_voice_build": web_voice_build,
            "android_device": android_device,
            "snapshot_scope": snapshot_scope,
        },
        rendered_fields,
    )


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(str(FONT_BOLD if bold else FONT_REGULAR), size)


def canvas(
    title: str,
    subtitle: str,
    *,
    size: tuple[int, int] = CANVAS,
) -> tuple[Image.Image, ImageDraw.ImageDraw]:
    image = Image.new("RGB", size, BG)
    draw = ImageDraw.Draw(image)
    draw.text((60, 42), title, fill=INK, font=font(42, True))
    draw.text((60, 98), subtitle, fill=MUTED, font=font(20))
    draw.line((60, 132, size[0] - 60, 132), fill=BORDER, width=2)
    return image, draw


def fitted_lines(draw: ImageDraw.ImageDraw, text: str, max_width: int, size: int) -> list[str]:
    result: list[str] = []
    for paragraph in text.split("\n"):
        words = paragraph.split()
        if not words:
            result.append("")
            continue
        current = words[0]
        for word in words[1:]:
            candidate = f"{current} {word}"
            if draw.textbbox((0, 0), candidate, font=font(size))[2] <= max_width:
                current = candidate
            else:
                result.append(current)
                current = word
        result.append(current)
    return result


def card(
    draw: ImageDraw.ImageDraw,
    xy: tuple[int, int, int, int],
    title: str,
    body: str,
    accent: str,
    *,
    dashed: bool = False,
    title_size: int = 24,
    body_size: int = 17,
) -> None:
    x1, y1, x2, y2 = xy
    if dashed:
        draw.rounded_rectangle(xy, radius=8, fill=WHITE, outline=BORDER, width=2)
        dash = 12
        for x in range(x1 + 8, x2 - 8, dash * 2):
            draw.line((x, y1, min(x + dash, x2 - 8), y1), fill=accent, width=3)
            draw.line((x, y2, min(x + dash, x2 - 8), y2), fill=accent, width=3)
        for y in range(y1 + 8, y2 - 8, dash * 2):
            draw.line((x1, y, x1, min(y + dash, y2 - 8)), fill=accent, width=3)
            draw.line((x2, y, x2, min(y + dash, y2 - 8)), fill=accent, width=3)
    else:
        draw.rounded_rectangle(xy, radius=8, fill=WHITE, outline=BORDER, width=2)
        draw.rounded_rectangle((x1, y1, x1 + 10, y2), radius=5, fill=accent)
    draw.text((x1 + 24, y1 + 18), title, fill=INK, font=font(title_size, True))
    y = y1 + 58
    for line in fitted_lines(draw, body, x2 - x1 - 48, body_size):
        draw.text((x1 + 24, y), line, fill=MUTED, font=font(body_size))
        y += body_size + 9


def arrow(
    draw: ImageDraw.ImageDraw,
    start: tuple[int, int],
    end: tuple[int, int],
    *,
    color: str = MUTED,
    label: str | None = None,
    label_offset: tuple[int, int] = (0, -24),
) -> None:
    draw.line((*start, *end), fill=color, width=4)
    sx, sy = start
    ex, ey = end
    dx, dy = ex - sx, ey - sy
    length = max((dx * dx + dy * dy) ** 0.5, 1)
    ux, uy = dx / length, dy / length
    px, py = -uy, ux
    tip = (ex, ey)
    left = (int(ex - ux * 16 + px * 8), int(ey - uy * 16 + py * 8))
    right = (int(ex - ux * 16 - px * 8), int(ey - uy * 16 - py * 8))
    draw.polygon((tip, left, right), fill=color)
    if label:
        label_font = font(15, True)
        label_width = draw.textbbox((0, 0), label, font=label_font)[2]
        mx = (sx + ex) // 2 + label_offset[0] - label_width // 2
        my = (sy + ey) // 2 + label_offset[1]
        box = draw.textbbox((mx, my), label, font=label_font)
        draw.rounded_rectangle((box[0] - 6, box[1] - 3, box[2] + 6, box[3] + 3), radius=4, fill=BG)
        draw.text((mx, my), label, fill=color, font=label_font)


def save(image: Image.Image, name: str, *, png_text: dict[str, str] | None = None) -> None:
    target = OUTPUT_DIR / name
    png_info = None
    if png_text:
        png_info = PngImagePlugin.PngInfo()
        for key, value in sorted(png_text.items()):
            png_info.add_text(key, value)
    with atomic_output_path(target) as temporary:
        image.save(temporary, format="PNG", optimize=True, pnginfo=png_info)


def build_architecture() -> None:
    image, draw = canvas(
        "WalkSafe Assist 시스템 구성",
        "실선 화살표는 연결 방향, 점선 테두리는 연구·수동 후속 구성요소입니다.",
    )
    card(draw, (50, 205, 270, 400), "보행 사용자", "스마트폰 브라우저\n카메라 · GPS · 방향\n마이크 · TTS · 진동", GREEN)
    card(draw, (340, 175, 660, 430), "Web/PWA + BFF", "주 앱 · 동일 출처 /api\n위험·길안내·신고 상태기\n비계량 advisory · IMU 선택\nframe/report 각각 기본 OFF\n기존 risk/report 유지\nbrowser TTS · WebXR 표시 전용", BLUE, body_size=12)
    card(draw, (760, 175, 1050, 430), "FastAPI Backend", "detect/report/admin · 인증 OpenAPI\nTMAP-only navigation\n추론 queue · 신고 journal\nPostgreSQL 공유 60초 limiter\nnonblocking readiness", RED, body_size=13)
    card(draw, (1140, 175, 1540, 430), "저장·외부 의존", "PostgreSQL/PostGIS 5개 application table\nreports + status/export/read audits\nactor_rate_limit_events(60초)\n원격 DB sslmode=verify-full\ngssencmode=disable\nDB 밖 image · TMAP-only", GOLD, body_size=13)
    card(draw, (340, 565, 660, 795), "Admin/Ops", "named actor 검수·상태·집계\n목록/상세/image 조회 감사\nactor·resource·time·purpose\n3 profile export · backup 도구\n실제 운영 시행 증거는 대기", GREEN, body_size=14)
    card(draw, (760, 565, 1050, 700), "Voice FastAPI", "faster-whisper · 12 intent+unknown\n전용 token · pre-copy auth/Content-Length\nglobal/actor/IP rate · copy byte\npost-copy duration/inference queue\ndisconnect · worker/replica=1+lock", BLUE, body_size=9)
    card(draw, (1140, 565, 1540, 795), "기관 외부 채널", "named admin human review\nreviewed·damage·GPS≤15m\n기관용 최소 필드 준비\n사람이 별도 채널로 제출\n실제 접수 receipt는 아직 없음", RED, dashed=True, body_size=14)
    card(
        draw,
        (50, 565, 270, 795),
        "Android 연구·제한 모드",
        android_device_architecture_body(FACTS["verification_snapshot"]),
        BLUE,
        dashed=True,
        body_size=12,
    )
    arrow(draw, (270, 302), (340, 302), color=GREEN, label="센서")
    arrow(draw, (660, 302), (760, 302), color=BLUE, label="HTTPS")
    arrow(draw, (1050, 302), (1140, 302), color=RED, label="DB/TMAP")
    arrow(draw, (660, 650), (900, 430), color=GREEN, label="조회·상태", label_offset=(0, -8))
    arrow(draw, (610, 430), (760, 620), color=BLUE, label="음성 API", label_offset=(-15, 6))
    arrow(draw, (660, 745), (1140, 745), color=RED, label="agency export")
    draw.line((270, 690, 300, 690, 300, 500), fill=BLUE, width=4)
    arrow(draw, (300, 500), (500, 430), color=BLUE, label="gateway session", label_offset=(-10, 8))
    draw.text((60, 842), "Release 대기: 승인 모델 · 실폰/실외 Field · 기관 receipt · backup→restore→retention 시행 근거", fill=INK, font=font(17, True))
    save(image, "system_architecture.png")


def build_risk_flow() -> None:
    image, draw = canvas(
        "탐지 결과의 위험 안내 처리",
        "탐지 존재만으로 경고하지 않으며, WebXR 중앙 거리 표시는 이 판단 경로와 분리됩니다.",
    )
    card(draw, (50, 195, 300, 350), "1. 입력", "camera frame\nGPS · absolute heading\n걸음 기반 속도 · route bearing", BLUE, body_size=16)
    card(draw, (370, 195, 650, 350), "2. server-v2", "최대변 960 · JPEG 0.78\n기본 450ms 직렬 요청\ninvalid/frozen/stale이면 중지", RED, body_size=16)
    card(draw, (720, 195, 1010, 350), "3. 객체별 안정화", "전체 후보를 IoU instance 추적\n서로 다른 3 frame AND 700ms\ntrack age·freshness 확인", GOLD, body_size=16)
    card(draw, (1080, 195, 1540, 350), "4. 공간·클래스 정책", "현재 ROI + 4초 보조 투영 ROI\n경로·접근 신호·bbox TTC 결합\n손상 점자블록은 신고로 분기", GREEN, body_size=16)
    arrow(draw, (300, 273), (370, 273), color=BLUE)
    arrow(draw, (650, 273), (720, 273), color=RED)
    arrow(draw, (1010, 273), (1080, 273), color=GOLD)

    card(draw, (90, 510, 500, 735), "A. 신고 분기", "damaged_tactile_block만 대상\nconfidence≥0.70 · GPS≤15m\n3 frame AND 700ms · jump≤20m\n10분 client cooldown + server 재검증", RED, body_size=16)
    card(draw, (595, 510, 1005, 735), "B. 위험·비계량 후보", "기존 alertable risk는 유지\n비경고 후보만 low 좌/중앙/우\n3 distinct frame AND 700ms\nWeb IMU 선택 · Android fresh IMU", GOLD, body_size=14)
    card(draw, (1100, 510, 1510, 735), "C. 보수적 안내", "비계량은 거리·N보·STOP/high 금지\nlocal steering·route 변경·report 금지\n위험 > 상호작용 > 길안내 > advisory\nField UNVERIFIED_OPEN", BLUE, body_size=14)
    arrow(draw, (1210, 350), (295, 510), color=RED, label="손상")
    arrow(draw, (1300, 350), (800, 510), color=GOLD, label="표면·킥보드")
    arrow(draw, (1390, 350), (1305, 510), color=BLUE, label="일반 객체")
    draw.rounded_rectangle((340, 790, 1260, 855), radius=8, fill="#10201C")
    draw.text((365, 807), "4초 투영은 GPS·걸음속도·방향 기반 보조 ROI이며 정밀 궤적·충돌 예측이 아닙니다.", fill=WHITE, font=font(18, True))
    save(image, "risk_processing_flow.png")


def build_report_flow() -> None:
    image, draw = canvas(
        "손상 점자블록 신고와 기관 수동 제출 흐름",
        "생성 응답은 primary 신고 ID를 유지하고, 다른 중복 후보 ID는 field에 노출하지 않습니다.",
    )
    positions = [50, 330, 610, 890, 1170]
    titles = ["1. 신고 후보", "2. Web 검사", "3. reports/v2", "4. 저장·중복 태그", "5. 관리자 운영"]
    bodies = [
        "자동 탐지 또는\n명시적 음성 요청\ndamaged_tactile_block",
        "자동: 0.70/15m/3프레임\n자동·음성 동일 bbox/JPEG/time\nstrict client parser",
        "image/schema/MIME 검증\nsecured auto 0.70/15m/time\nAndroid source commit gate\ningestion actor/time/hash",
        "fake/real 분리 중복 조회\nprimary ID + duplicate_count\ndurable journal→rename→DB\n시작 시 미완료 write 조정",
        "검수 · 필터 · 상태관리\n목록/상세/image read audit\nactor/resource/time/purpose\n상세·internal·agency exact",
    ]
    colors = [BLUE, GREEN, RED, GOLD, GREEN]
    for x, title, body, color in zip(positions, titles, bodies, colors, strict=True):
        card(draw, (x, 220, x + 235, 455), title, body, color, title_size=21, body_size=16)
    for i in range(len(positions) - 1):
        arrow(draw, (positions[i] + 235, 337), (positions[i + 1], 337), color=colors[i])

    card(draw, (380, 590, 800, 780), "6. 기관 제출 전 검수", "named admin reviewed\ndamage · high≤15m · non-fake\n정확 위치 최소 필드 · image 제외\nclient-asserted 성능 제외는 유지", BLUE)
    card(draw, (960, 590, 1380, 780), "7. 기관별 외부 채널", "사람이 portal/email/API로 제출\n접수번호·기관·actor 기록 도구 보유\n실제 receipt는 아직 없음", RED, dashed=True)
    arrow(draw, (1287, 455), (800, 650), color=BLUE, label="다운로드")
    arrow(draw, (800, 685), (960, 685), color=RED, label="사람이 제출")
    draw.text((60, 817), "grid는 0.001° 집계 · 상세/internal/agency는 exact · field는 primary ID와 count, 다른 후보 lookup은 admin 전용", fill=INK, font=font(16, True))
    draw.text((60, 846), "server hash는 ingestion 무결성이지 camera-origin 인증 아님 · 실제 기관 전송은 외부 receipt로 증명", fill=INK, font=font(17, True))
    save(image, "report_csv_flow.png")


def build_erd() -> None:
    image, draw = canvas(
        "신고·감사·공유 제한을 분리한 영속 모델",
        "application table은 정확히 5개이며 status/export/read 3종 감사만 append-only입니다.",
    )
    x1, y1, x2, y2 = 60, 175, 850, 820
    draw.rounded_rectangle((x1, y1, x2, y2), radius=8, fill=WHITE, outline=BORDER, width=2)
    draw.rectangle((x1, y1, x2, y1 + 64), fill=INK)
    draw.text((x1 + 24, y1 + 14), "reports", fill=WHITE, font=font(28, True))
    columns = [
        ("id", "UUID", "PK · NOT NULL"),
        ("status", "VARCHAR", "enum CHECK · index"),
        ("class_id / class_name", "INTEGER / VARCHAR", "class_id ≥0 CHECK · name index"),
        ("confidence", "FLOAT", "NOT NULL · 0..1 CHECK"),
        ("bbox_x/y/width/height", "FLOAT", "normalized CHECK"),
        ("captured_at / timestamps", "TIMESTAMPTZ", "captured·created·updated"),
        ("source", "VARCHAR", "enum CHECK"),
        ("latitude / longitude", "FLOAT", "range·location consistency CHECK"),
        ("accuracy_m / heading", "FLOAT", "≥0 / 0≤heading<360 CHECK"),
        ("location", "geometry(Point,4326)", "SRID/XY consistency · GiST"),
        ("image_path / image_content_type", "VARCHAR", "NOT NULL"),
        ("metadata", "JSONB", "NOT NULL"),
    ]
    row_y = y1 + 78
    for index, (name, data_type, constraint) in enumerate(columns):
        if index % 2 == 0:
            draw.rectangle((x1 + 1, row_y - 5, x2 - 1, row_y + 38), fill="#FBFAF6")
        draw.text((x1 + 20, row_y), name, fill=INK, font=font(17, True))
        draw.text((x1 + 315, row_y), data_type, fill=BLUE, font=font(15))
        draw.text((x1 + 555, row_y), constraint, fill=MUTED, font=font(15))
        row_y += 45

    card(draw, (930, 165, 1530, 300), "report_status_audits · APPEND-ONLY", "report_id · 이전/다음 상태 · actor · note/reason · created_at", GREEN, body_size=14)
    card(draw, (930, 325, 1530, 460), "report_export_audits · APPEND-ONLY", "audit/requested ID · actor · profile · rows hash · filters · created_at", BLUE, body_size=14)
    card(draw, (930, 485, 1530, 620), "report_read_audits · APPEND-ONLY", "list/detail/image · actor · resource ID · time · purpose · details", RED, body_size=14)
    card(draw, (930, 645, 1530, 780), "actor_rate_limit_events · 일반 event", "actor digest · rate group · observed_at · 공유 원자 60초 window", GOLD, body_size=14)
    arrow(draw, (850, 305), (930, 260), color=GREEN, label="report_id")
    arrow(draw, (850, 545), (930, 545), color=RED, label="read")
    draw.text((940, 805), "DB 밖 image: durable journal → 원자 rename → DB commit · startup reconciliation", fill=MUTED, font=font(13, True))
    draw.text((940, 832), "중복 후보는 별도 table/병합이 아니며 다른 ID는 admin 전용", fill=MUTED, font=font(13))
    save(image, "reports_erd.png")


def build_value_flow() -> None:
    image, draw = canvas(
        "한 번의 탐지에서 안내와 신고를 명시적으로 분기",
        "일반 위험은 보행 보조로, 손상 점자블록은 report-only 자료로 처리합니다.",
    )
    card(draw, (60, 285, 355, 565), "1. 현재 환경 포착", "스마트폰 camera\nGPS·방향·음성", BLUE, body_size=20)
    card(draw, (455, 285, 780, 565), "2. class·신뢰 gate", "객체별 3 frame·700ms\n경로·접근·freshness", GOLD, body_size=20)
    card(
        draw,
        (930, 180, 1520, 390),
        "3A. 일반 위험 — 보행 안내",
        "alertable 조건을 통과한 후보만 짧은 TTS·진동\n정보가 부족하면 침묵하고 계속 관찰",
        GREEN,
        title_size=23,
        body_size=18,
    )
    card(
        draw,
        (930, 470, 1520, 680),
        "3B. 손상 점자블록 — 신고 자료",
        "alertable=false · reportable=true\n서버 재검증·원자 저장·Admin human review",
        RED,
        title_size=23,
        body_size=18,
    )
    arrow(draw, (355, 425), (455, 425), color=BLUE)
    arrow(draw, (780, 365), (930, 285), color=GREEN, label="일반 위험", label_offset=(0, -32))
    arrow(draw, (780, 485), (930, 575), color=RED, label="damaged tactile", label_offset=(0, 12))
    draw.rounded_rectangle((250, 745, 1350, 825), radius=10, fill=INK)
    draw.text((330, 769), "두 분기는 직렬이 아님 · 외부 제출은 관리자 검수 뒤 사람의 책임", fill=WHITE, font=font(24, True))
    draw.text((420, 850), "MVP 구현 · 실폰/실외 사용자 과업·기관 접수·Release 검증 대기", fill=MUTED, font=font(18, True))
    save(image, "value_flow.png")


def build_model_quality_gate() -> None:
    metrics = FACTS["model_data"]["selected_class_metrics"]
    image, draw = canvas(
        "내부 validation은 학습 결과이지 배포 승인 근거가 아님",
        "전체 mAP50-95 0.41651 · 실제 평가 26,416장/69,467 objects · 독립 test 없음",
    )
    chart_x, chart_y, chart_w, chart_h = 80, 220, 900, 500
    draw.line((chart_x, chart_y + chart_h, chart_x + chart_w, chart_y + chart_h), fill=INK, width=3)
    label_map = {
        "damaged_tactile_block": "손상 점자블록",
        "e_scooter_obstruction": "전동킥보드",
        "normal_tactile_block": "정상 점자블록",
        "crosswalk": "횡단보도",
        "curb_step": "연석",
        "uneven_sidewalk": "불균일 보도",
    }
    bar_width = 90
    gap = 55
    for index, item in enumerate(metrics):
        recall = float(item["recall"])
        x = chart_x + 45 + index * (bar_width + gap)
        height = int(chart_h * recall)
        color = GREEN if recall >= 0.8 else GOLD if recall >= 0.5 else RED
        draw.rectangle((x, chart_y + chart_h - height, x + bar_width, chart_y + chart_h), fill=color)
        draw.text((x + 12, chart_y + chart_h - height - 34), f"R {recall:.3f}", fill=INK, font=font(16, True))
        label = label_map[item["class"]]
        parts = label.split()
        for offset, part in enumerate(parts):
            draw.text((x - 4, chart_y + chart_h + 18 + offset * 24), part, fill=MUTED, font=font(15, True))

    card(draw, (1060, 205, 1530, 390), "확인된 데이터 문제", "truncated val 2,331장 제외\nAIHub 572 촬영 sequence 599개 겹침\ncontent-hash 열 없음", RED, body_size=17)
    card(draw, (1060, 445, 1530, 630), "Release 차단 gate", "독립 test 없음\ncurb·uneven recall 미달\nAndroid Device Field 없음", GOLD, body_size=17)
    draw.rounded_rectangle((1040, 700, 1550, 810), radius=8, fill=INK)
    draw.text((1090, 724), "deployment_eligible = false", fill=WHITE, font=font(24, True))
    draw.text((1090, 765), "후보 성능과 현장 안전을 분리", fill=WHITE, font=font(18))
    save(image, "model_quality_gate.png")


def build_problem_solution_map() -> None:
    image, draw = canvas(
        "사용자 문제에서 두 가지 가치까지",
        "공식 통계는 배경 근거로만 사용하고, 실제 효과는 실폰·실외 실증 지표로 확인합니다.",
    )
    card(draw, (55, 185, 435, 365), "1. 보행 중 정보 공백", "고정 경로만으로는 파손·단절·방치 장애물처럼 지금 눈앞에서 달라지는 상태를 설명하기 어렵습니다.", RED, body_size=16)
    card(draw, (55, 410, 435, 590), "2. 화면 주시 부담", "보행 중 화면을 계속 확인하기보다 짧은 음성·진동과 음성 조작으로 핵심 상태를 전달할 필요가 있습니다.", GOLD, body_size=16)
    card(draw, (55, 635, 435, 815), "3. 시설 신고의 번거로움", "손상 시설은 위치·이미지·근거를 모아 검수하고, 오탐을 걸러 외부 제출 자료로 만드는 과정이 필요합니다.", BLUE, body_size=16)

    card(draw, (610, 225, 990, 445), "WalkSafe 보행 보조", "카메라 탐지 → 객체별 3프레임·700ms 안정화 → 경로·접근 신호 확인 → 짧은 TTS·진동", GREEN, title_size=25, body_size=17)
    card(draw, (610, 555, 990, 775), "WalkSafe 시설 자료화", "손상 점자블록 후보 → 서버 재검증·원자 저장 → 관리자 검수 → 기관용 최소자료", BLUE, title_size=25, body_size=17)

    card(draw, (1165, 225, 1535, 445), "사용자 가치 가설", "필요한 순간에만 안내해 화면 의존과 불필요한 알림을 줄입니다. 효과는 오경고/분·지연·과업완료율로 측정합니다.", GREEN, body_size=16)
    card(draw, (1165, 555, 1535, 775), "운영 가치 가설", "신고 후보의 위치·이미지·검수 이력을 남겨 시설관리 검토를 돕습니다. 효과는 승인율·처리시간·접수 건수로 측정합니다.", BLUE, body_size=16)
    arrow(draw, (435, 275), (610, 300), color=RED)
    arrow(draw, (435, 500), (610, 350), color=GOLD)
    arrow(draw, (435, 725), (610, 665), color=BLUE)
    arrow(draw, (990, 335), (1165, 335), color=GREEN)
    arrow(draw, (990, 665), (1165, 665), color=BLUE)
    draw.rounded_rectangle((520, 470, 1080, 535), radius=8, fill=INK)
    draw.text((548, 488), "한 번의 보행에서 ‘즉시 안내’와 ‘사후 시설 개선 자료’를 분리해 연결", fill=WHITE, font=font(17, True))
    save(image, "problem_solution_map.png")


def build_use_case_swimlane() -> None:
    image, draw = canvas(
        "핵심 유스케이스와 책임 분리",
        "보행 사용자·시스템·관리자가 같은 흐름을 공유하되, 판단과 외부 제출 책임은 분리합니다.",
    )
    lanes = [
        ("보행 사용자", GREEN, 180, 360),
        ("Web/PWA\nBackend", BLUE, 385, 600),
        ("관리자·외부 채널", RED, 625, 835),
    ]
    for name, color, y1, y2 in lanes:
        draw.rounded_rectangle((50, y1, 1550, y2), radius=10, fill=WHITE, outline=BORDER, width=2)
        draw.rounded_rectangle((50, y1, 245, y2), radius=10, fill=color)
        label_lines = fitted_lines(draw, name, 160, 20)
        y = y1 + (y2 - y1 - len(label_lines) * 28) // 2
        for line in label_lines:
            draw.text((72, y), line, fill=WHITE, font=font(20, True))
            y += 28

    user_cards = [
        (285, "1. 보행 시작", "용도별 기본 OFF 동의"),
        (545, "2. 위험 안내", "TTS·진동"),
        (805, "3. 음성 제어", "목적지·신고"),
        (1065, "4. 진행 확인", "이탈·도착"),
    ]
    for x, title, body in user_cards:
        card(draw, (x, 215, x + 210, 330), title, body, GREEN, title_size=18, body_size=15)

    system_cards = [
        (285, "UC-01·02", "새 프레임 탐지\n안정화·경로 선별"),
        (545, "UC-03·04", "신고 gate\n음성 intent 실행"),
        (805, "UC-06·07", "PWA 상태\nTMAP 전역 경로"),
        (1065, "UC-08·09", "모델·Android 연구\n운영 보호"),
    ]
    for x, title, body in system_cards:
        card(draw, (x, 430, x + 210, 555), title, body, BLUE, title_size=18, body_size=14)

    admin_cards = [
        (405, "UC-05 검수", "목록·상세·상태 이력"),
        (730, "기관용 자료", "reviewed·high GPS·non-fake"),
        (1055, "외부 제출", "사람이 별도 채널로 제출"),
    ]
    for x, title, body in admin_cards:
        card(draw, (x, 675, x + 255, 790), title, body, RED, title_size=18, body_size=14, dashed=title == "외부 제출")

    for x1, x2 in ((495, 545), (755, 805), (1015, 1065)):
        arrow(draw, (x1, 272), (x2, 272), color=GREEN)
    for x1, x2 in ((495, 545), (755, 805), (1015, 1065)):
        arrow(draw, (x1, 492), (x2, 492), color=BLUE)
    arrow(draw, (660, 732), (730, 732), color=RED)
    arrow(draw, (985, 732), (1055, 732), color=RED)
    arrow(draw, (650, 555), (530, 675), color=RED, label="신고 후보")
    draw.text((275, 850), "핵심 사용자 UC는 UC-01~07, 개발·운영 지원 UC는 UC-08~09로 분류하며 전체 ID는 유지합니다.", fill=INK, font=font(18, True))
    save(image, "use_case_swimlane.png")


def build_ui_state_map() -> None:
    image, draw = canvas(
        "보행 보조 화면의 상태와 피드백",
        "성공 화면만이 아니라 권한·네트워크·탐지·신고 실패를 같은 수준의 UI 상태로 설계합니다.",
    )
    states = [
        (60, 200, "대기", "Web frame/report\n각각 미동의 시 OFF", MUTED),
        (355, 200, "권한 확인", "camera·GPS·mic\n거부 시 원인 표시", GOLD),
        (650, 200, "보행 보조", "preview·detector source\n위험·경로 상태", GREEN),
        (945, 200, "위험 안내", "짧은 TTS·진동\n녹음·낮은 우선순위 취소", RED),
        (1240, 200, "신고 상태", "대기 이유·전송·성공/실패\nprimary ID 일부 표시", BLUE),
    ]
    for x, y, title, body, color in states:
        card(draw, (x, y, x + 245, y + 190), title, body, color, title_size=22, body_size=15)
    for i in range(len(states) - 1):
        arrow(draw, (states[i][0] + 245, 295), (states[i + 1][0], 295), color=states[i][4])

    card(draw, (120, 535, 470, 750), "길안내 상태", "목적지 후보 → 경로 시작 → 진행 → 이탈·재탐색 → 도착\n정상 점자블록 gate 통과 중만 단거리 steering", BLUE, body_size=16)
    card(draw, (625, 535, 975, 750), "복귀·중지 상태", "gate 불충족·손상·미검출이면 TMAP 안내로 복귀\n동의 철회·hidden/pagehide·로그아웃 시 in-flight 취소 후 센서 세션 종료", GREEN, body_size=15)
    card(draw, (1130, 535, 1480, 750), "오류 상태", "offline·backend/model unavailable·stale 응답을 ‘위험 없음’으로 바꾸지 않음\n재시도와 대체 행동 제시", RED, body_size=16)
    arrow(draw, (470, 642), (625, 642), color=BLUE, label="조건 변화")
    arrow(draw, (975, 642), (1130, 642), color=RED, label="실패")
    draw.rounded_rectangle((260, 805, 1340, 860), radius=8, fill=INK)
    draw.text((290, 821), "접근성 원칙: 색상+문구 병기 · aria-live/TalkBack 읽기 순서 · 48px 주요 조작 · 반복 알림 cooldown", fill=WHITE, font=font(17, True))
    save(image, "ui_state_map.png")


def build_ui_screen_storyboard() -> None:
    image, draw = canvas(
        "SC-00~SC-11 화면별 UI 스토리보드",
        "각 카드는 별도 route가 아니라 실제 두 route 안에서 바뀌는 정보 구조·상태·복구 행동을 보여 줍니다.",
        size=(1600, 1500),
    )
    screens = [
        ("SC-00 역할 인증", "field/admin 세션", "ID · token · 오류", "로그인 / 로그아웃", BLUE),
        ("SC-01 보행 보조", "탐지 중 · source", "preview · active 위험", "보행 보조 중지", GREEN),
        ("SC-02 권한·연결", "camera · GPS · mic", "허용 여부 · 오류 원인", "권한 다시 시도", GOLD),
        ("SC-03 위험 안내", "앞쪽 턱 · HIGH", "방향 · 행동 · 판단 근거", "짧은 TTS · 진동", RED),
        ("SC-04 신고 상태", "대기 → 전송 → 결과", "gate 부족 · 5초 결과", "음성 재요청", RED),
        ("SC-05 음성 제어", "듣는 중 · 처리 중", "transcript · 확인 질문", "취소 / 다시 말하기", BLUE),
        ("SC-06 목적지·길안내", "후보 → 안내 → 도착", "다음 안내 · 이탈 · 재탐색", "후보 선택 / 중지", GREEN),
        ("SC-07 Android 연구", "overlay · depth · TFLite", "연구 경로 · FIELD 대기", "invoke 결과 확인", MUTED),
        ("SC-08 Admin 목록", "filter · summary · grid", "최신순 목록 · 집계", "신고 상세 열기", BLUE),
        ("SC-09 Admin 상세", "image · metadata · 이력", "검수 flag · 충돌 409", "재조회 후 상태 변경", GOLD),
        ("SC-10 기관용 반출", "profile · manifest · audit", "최소 필드 · receipt 없음", "제출 묶음 준비", RED),
        ("SC-11 설정·PWA", "Web frame/report · Android report", "telemetry · 모두 기본 OFF", "철회 / in-flight 취소", GREEN),
    ]

    for index, (title, status, content, action, accent) in enumerate(screens):
        column = index % 3
        row = index // 3
        x1 = 50 + column * 515
        y1 = 155 + row * 310
        x2 = x1 + 470
        y2 = y1 + 270
        draw.rounded_rectangle((x1, y1, x2, y2), radius=10, fill=WHITE, outline=BORDER, width=2)
        draw.rounded_rectangle((x1, y1, x1 + 9, y2), radius=5, fill=accent)
        draw.text((x1 + 24, y1 + 14), title, fill=INK, font=font(28, True))

        frame = (x1 + 24, y1 + 58, x2 - 20, y2 - 18)
        draw.rounded_rectangle(frame, radius=7, fill="#FBFCFA", outline="#AEBAB5", width=2)
        draw.rounded_rectangle((frame[0] + 10, frame[1] + 10, frame[2] - 10, frame[1] + 60), radius=5, fill=accent)
        draw.text((frame[0] + 20, frame[1] + 20), status, fill=WHITE, font=font(24, True))
        draw.text((frame[0] + 18, frame[1] + 83), f"정보 | {content}", fill=MUTED, font=font(25))
        draw.line((frame[0] + 18, frame[1] + 126, frame[2] - 18, frame[1] + 126), fill=BORDER, width=2)
        draw.rounded_rectangle(
            (frame[0] + 18, frame[1] + 143, frame[2] - 18, frame[1] + 190),
            radius=5,
            fill="#E7F0EE",
        )
        draw.text((frame[0] + 30, frame[1] + 153), f"행동 | {action}", fill=INK, font=font(24, True))

    draw.rounded_rectangle((155, 1410, 1445, 1472), radius=8, fill=INK)
    draw.text(
        (190, 1430),
        "공통 계약: 색상+문구 · screen reader 상태 · 실패≠위험 없음 · 사용자가 재시도·중지할 수 있는 복구 행동",
        fill=WHITE,
        font=font(22, True),
    )
    save(image, "ui_screen_storyboard.png")


def build_navigation_flow() -> None:
    image, draw = canvas(
        "TMAP 전역 경로와 platform capability gate",
        "전역 provider는 TMAP-only입니다. Web은 IMU 선택, ARCore 미지원 Android는 fresh IMU 필수 비계량 advisory이며 Android metric local steering은 별도 strict gate입니다.",
    )
    card(draw, (60, 190, 330, 355), "1. 목적지 선택", "음성 검색·후보 확인\n거리 미상 단일 후보 자동 선택 금지", BLUE, body_size=15)
    card(draw, (410, 190, 680, 355), "2. TMAP 전역 경로", "계단 회피 보행 경로\nprogress·이탈·재탐색·도착 상태기", GREEN, body_size=15)
    card(draw, (760, 190, 1040, 355), "3. Web advisory", "CAMERA_NON_METRIC_ADVISORY\n후면 camera·detector·fresh frame\nIMU는 제공될 때만 gate", GOLD, body_size=14)
    card(draw, (1120, 190, 1540, 355), "4. Android 제한 모드", "CAMERA_IMU_NON_METRIC\nCameraX·detector·fresh IMU\n활성 TMAP route · reports=false", RED, body_size=14)
    arrow(draw, (330, 272), (410, 272), color=BLUE)
    arrow(draw, (680, 272), (760, 272), color=GREEN)
    arrow(draw, (1040, 272), (1120, 272), color=GOLD, label="platform 분리")

    card(draw, (220, 555, 600, 750), "비계량 공통 경계", "3 distinct frame+700ms · low 좌/중앙/우\n거리·N보·STOP/high·steering 금지\nroute 변경·자동 신고 금지", RED, body_size=14)
    card(draw, (690, 555, 1070, 750), "실패·전달 gate", "조건 실패는 TMAP_ONLY\n3A bounded sequential handoff\n전달 직전 tier·freshness·TTL recheck", GREEN, body_size=14)
    card(draw, (1160, 555, 1510, 750), "이탈·도착", "confirmed off-route면 이전 안내 억제·재탐색\n2-sample 도착 판정", BLUE, body_size=16)
    arrow(draw, (1260, 355), (410, 555), color=RED, label="한 조건이라도 실패")
    arrow(draw, (600, 652), (690, 652), color=GREEN)
    arrow(draw, (1070, 652), (1160, 652), color=BLUE)
    draw.rounded_rectangle((365, 805, 1235, 860), radius=8, fill=INK)
    draw.text((190, 821), "기존 Web alertable risk·명시 동의 damaged-report 유지 · Web 실폰/ARCore 미지원 Android Field UNVERIFIED_OPEN", fill=WHITE, font=font(16, True))
    save(image, "navigation_state_flow.png")


def build_risk_timeline() -> None:
    image, draw = canvas(
        "한 객체가 안내되기까지의 시간축",
        "프레임 수만 세지 않고 서로 다른 media frame·지속시간·경로 근거를 모두 만족해야 합니다.",
    )
    y = 335
    draw.line((120, y, 1480, y), fill=MUTED, width=6)
    points = [
        (150, "새 frame 1", "후보 생성\n안내 보류", BLUE),
        (430, "새 frame 2", "동일 객체 IoU 추적\n안내 보류", BLUE),
        (710, "새 frame 3", "3개 distinct frame\n조건 충족 여부", GOLD),
        (990, "700ms 이상", "freshness·ROI·경로·접근\n최종 선별", GREEN),
        (1270, "피드백", "짧은 TTS\n고위험만 별도 진동", RED),
    ]
    for x, title, body, color in points:
        draw.ellipse((x - 18, y - 18, x + 18, y + 18), fill=color)
        card(draw, (x - 110, 160, x + 110, 290), title, body, color, title_size=18, body_size=14)
        draw.line((x, 290, x, y - 20), fill=color, width=3)
    draw.text((155, 385), "기본 요청 간격 450ms · frozen/중복 frame 제외 · 1.8초 초과 stale 응답 제외", fill=INK, font=font(19, True))

    card(draw, (110, 525, 500, 755), "빈 detection 처리", "한 번 비었다고 즉시 해제하지 않고 직전 후보를 보수적으로 유지합니다. 2회 연속 비었거나 track age를 넘으면 해제합니다.", GOLD, body_size=16)
    card(draw, (605, 525, 995, 755), "깊이·TTC 경계", "trusted metric depth가 없으면 bbox 크기 변화 TTC만으로 high 또는 STOP을 만들지 않습니다.", RED, body_size=16)
    card(draw, (1100, 525, 1490, 755), "우선순위", "위험 > 상호작용 > 길안내. 위험 발생 시 진행 중인 녹음·STT 업로드와 낮은 우선순위 발화를 취소합니다.", GREEN, body_size=16)
    draw.text((225, 828), "현재 수치는 구현 기본값이며 사용자 실험으로 최적화됐다는 의미가 아닙니다. 실외 오경고/분·누락률·지연 p50/p95를 후속 측정합니다.", fill=INK, font=font(17, True))
    save(image, "risk_timeline.png")


def build_evidence_results() -> None:
    bodies, rendered_fields = evidence_results_spec(FACTS)
    image, draw = canvas(
        "계층별 자동검증 결과",
        f"{bodies['snapshot_scope']}. 서로 다른 suite의 수를 합산하지 않습니다.",
    )
    card(draw, (60, 195, 400, 415), "정책·단위", bodies["policy_unit"], GREEN, title_size=25, body_size=17)
    card(draw, (455, 195, 795, 415), "Backend·DB", bodies["backend_db"], BLUE, title_size=25, body_size=17)
    card(draw, (850, 195, 1190, 415), "Web·Voice·Build", bodies["web_voice_build"], GOLD, title_size=25, body_size=17)
    card(draw, (1245, 195, 1540, 415), "Android 기기", bodies["android_device"], RED, title_size=25, body_size=14)

    card(draw, (130, 560, 560, 765), "확인한 것", "정책 분기·API/DB 계약·빌드·headless PWA·실기기 TFLite load/invoke를 해당 snapshot 시점에 재현했습니다. 현재 수치는 source freeze 후 교체합니다.", GREEN, body_size=15)
    card(draw, (585, 560, 1015, 765), "안전하게 실패하는 것", "권한·offline·stale·model unavailable을 성공이나 ‘위험 없음’으로 바꾸지 않고 닫힙니다.", GOLD, body_size=17)
    card(draw, (1040, 560, 1470, 765), "아직 남은 것", "실폰 camera/mic 전체 흐름, 실외 오탐·미탐·지연·배터리, 독립 test, 운영 IAM·기관 접수입니다.", RED, body_size=17)
    draw.rounded_rectangle((315, 810, 1285, 865), radius=8, fill=INK)
    draw.text((346, 826), "자동검증 PASS ≠ 실사용 안전 PASS · 내부 validation ≠ 독립 test · load/invoke ≠ camera pipeline", fill=WHITE, font=font(18, True))
    save(
        image,
        "evidence_results.png",
        png_text={
            EVIDENCE_RESULTS_PNG_SEMANTIC_KEY: json.dumps(
                rendered_fields,
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            )
        },
    )
    sidecar = {
        "schema_version": "walksafe.evidence-results-semantics.v1",
        "facts": {
            "path": FACTS_PATH.relative_to(REPO_ROOT).as_posix(),
            "schema_version": FACTS["schema_version"],
            "sha256": sha256(FACTS_PATH),
        },
        "artifact": {
            "path": EVIDENCE_RESULTS_PATH.relative_to(REPO_ROOT).as_posix(),
            "sha256": sha256(EVIDENCE_RESULTS_PATH),
            "width": image.width,
            "height": image.height,
        },
        "rendered_fields": rendered_fields,
    }
    with atomic_output_path(EVIDENCE_RESULTS_SEMANTIC_PATH) as temporary:
        temporary.write_text(
            json.dumps(sidecar, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )


def build_scope_change_map() -> None:
    image, draw = canvas(
        "원안 목표를 구현 근거에 맞게 재정의한 과정",
        "목표를 조용히 삭제하지 않고, 제약을 확인한 뒤 남길 가치와 검증 기준을 명시했습니다.",
    )
    headers = [(60, "원안 목표"), (430, "확인한 제약"), (800, "현재 결정"), (1170, "남긴 가치·다음 증거")]
    for x, text_value in headers:
        draw.rounded_rectangle((x, 165, x + 320, 220), radius=8, fill=INK)
        draw.text((x + 16, 180), text_value, fill=WHITE, font=font(19, True))
    rows = [
        ("IMU+GPS 정밀 측위", "Kalman/dead reckoning 미구현", "GPS·걸음·방향 기반 4초 보조 ROI", "경로 후보 보조 · 실폰 정확도 측정"),
        ("자동 재학습·무중단 배포", "승격·운영 workflow 부재", "수동 train·eval·registry·rollback", "재현성 확보 · 승인 절차 필요"),
        ("공공기관 자동 API 신고", "기관 API·책임경계 미확정", "관리자 검수·agency 자료·수동 제출", "오탐 차단 · 실제 receipt 필요"),
        ("완전 offline 안전 기능", "탐지·길안내·STT가 서버 의존", "app shell만 cache, 안전 API network-only", "오프라인 경계 명확 · 안정 HTTPS 필요"),
    ]
    colors = [BLUE, GREEN, RED, GOLD]
    for index, row in enumerate(rows):
        y1 = 250 + index * 145
        y2 = y1 + 115
        for col, (x, _header) in enumerate(headers):
            draw.rounded_rectangle((x, y1, x + 320, y2), radius=7, fill=WHITE, outline=BORDER, width=2)
            if col == 0:
                draw.rounded_rectangle((x, y1, x + 8, y2), radius=4, fill=colors[index])
            lines = fitted_lines(draw, row[col], 280, 15)
            text_y = y1 + (115 - len(lines) * 23) // 2
            for line in lines:
                draw.text((x + 18, text_y), line, fill=INK if col in {0, 2} else MUTED, font=font(15, col in {0, 2}))
                text_y += 23
        for col in range(3):
            arrow(draw, (headers[col][0] + 320, y1 + 57), (headers[col + 1][0], y1 + 57), color=colors[index])
    draw.text((210, 842), "범위 변경은 ‘완료’가 아니라 근거 기반 의사결정입니다. 원안 대비 미달성 항목과 후속 gate를 최종 일정에 유지합니다.", fill=INK, font=font(18, True))
    save(image, "scope_change_map.png")


def build_adoption_roadmap() -> None:
    image, draw = canvas(
        "실증에서 활용까지의 단계",
        "측정되지 않은 효과를 주장하지 않고, 단계별 사용자·운영 지표가 통과될 때 다음 범위로 이동합니다.",
    )
    stages = [
        (70, "현재 MVP", "CODE·AUTO·격리 E2E\n위험 선별·신고·검수 연결", GREEN),
        (440, "통제 실폰 실증", "camera/mic/TTS/진동·PWA\n지연 p50/p95·오경고/분", BLUE),
        (810, "복지기관·사용자 검토", "과업완료율·이해도·중지 성공\n안전 프로토콜·접근성 의견", GOLD),
        (1180, "시설관리 운영 실증", "검수 승인율·처리시간\nagency 자료·실제 접수 receipt", RED),
    ]
    for x, title, body, color in stages:
        card(draw, (x, 235, x + 300, 495), title, body, color, title_size=24, body_size=16, dashed=title != "현재 MVP")
    for i in range(len(stages) - 1):
        arrow(draw, (stages[i][0] + 300, 365), (stages[i + 1][0], 365), color=stages[i][3], label="증거 통과")
    card(draw, (160, 625, 550, 795), "사용자 활용", "보행 중 보조적 위험 안내\n음성 목적지·길안내\n효과는 실제 과업으로 측정", GREEN, body_size=16)
    card(draw, (605, 625, 995, 795), "복지기관 활용", "안전한 실증·접근성 교육\n기능 이해와 실패 대응 훈련\n당사자·전문가 검토", BLUE, body_size=16)
    card(draw, (1050, 625, 1440, 795), "시설관리 활용", "손상 후보의 위치·이미지·이력 검수\n기관용 최소자료 수동 제출\n운영 IAM·보존 절차 필요", RED, body_size=16)
    draw.text((330, 842), "장기 사회효과는 사고 감소·비용 절감이 아니라, 실제 접수·조치와 사용자 연구 데이터가 쌓인 뒤 평가합니다.", fill=INK, font=font(18, True))
    save(image, "adoption_roadmap.png")


def main() -> int:
    missing_fonts = [path for path in (FONT_REGULAR, FONT_BOLD) if not path.is_file()]
    if missing_fonts:
        raise SystemExit(f"Required Korean fonts are missing: {missing_fonts}")
    with submission_build_lock(REPO_ROOT):
        require_clean_source_revision(REPO_ROOT, ALL_SUBMISSION_GENERATED_PATHS)
        submission_toolchain_attestation(REPO_ROOT)
        require_no_unexpected_files(OUTPUT_DIR, ASSET_DIRECTORY_FILES)
        build_architecture()
        build_risk_flow()
        build_report_flow()
        build_erd()
        build_value_flow()
        build_model_quality_gate()
        build_problem_solution_map()
        build_use_case_swimlane()
        build_ui_state_map()
        build_ui_screen_storyboard()
        build_navigation_flow()
        build_risk_timeline()
        build_evidence_results()
        build_scope_change_map()
        build_adoption_roadmap()
        repository_inputs = [file_record(REPO_ROOT, path) for path in BUILD_INPUT_PATHS]
        system_inputs = [
            {"path": str(path), "sha256": sha256(path), "bytes": path.stat().st_size}
            for path in (FONT_REGULAR, FONT_BOLD)
        ]
        artifacts = [
            file_record(REPO_ROOT, OUTPUT_DIR / name)
            for name in sorted(GENERATED_ASSET_ARTIFACT_NAMES)
        ]
        manifest = {
            "schema_version": "walksafe.submission-assets-build.v1",
            **require_clean_source_revision(REPO_ROOT, ALL_SUBMISSION_GENERATED_PATHS),
            "build_tools": build_tool_provenance(("Pillow",)),
            "submission_toolchain": submission_toolchain_attestation(REPO_ROOT),
            "allowed_asset_files": sorted(ASSET_DIRECTORY_FILES),
            "input_bundle_sha256": record_bundle_sha256([*repository_inputs, *system_inputs]),
            "repository_inputs": repository_inputs,
            "system_inputs": system_inputs,
            "artifacts": artifacts,
        }
        with atomic_output_path(MANIFEST_PATH) as temporary:
            temporary.write_text(
                json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
        require_exact_file_set(OUTPUT_DIR, ASSET_DIRECTORY_FILES)
    for name in sorted(GENERATED_ASSET_ARTIFACT_NAMES):
        print(OUTPUT_DIR / name)
    print(MANIFEST_PATH)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

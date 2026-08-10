from __future__ import annotations

import fcntl
import hashlib
import os
import re
import tempfile
from contextlib import contextmanager
from datetime import datetime
from pathlib import Path
from typing import Iterator


CURRENT_ANDROID_DEVICE_VERIFICATION = {
    "status": "NOT_RUN_CURRENT_SOURCE_FREEZE",
    "executed_at": None,
    "passed": None,
    "total": None,
}
HISTORICAL_ANDROID_DEVICE_EVIDENCE = {
    "status": "PASS_HISTORICAL_EVIDENCE",
    "executed_at": "2026-07-13",
    "device": "SM-G981N",
    "scope": "unified/legacy TFLite asset contract and load/invoke instrumentation",
    "passed": 2,
    "total": 2,
}
APPROVED_SOURCE_FREEZE_POLICY = (
    "사용자가 승인한 source-freeze commit의 자동검증 snapshot이며 최종 source-freeze "
    "근거로 채택했다. Field 또는 Release 증거로 확대하지 않고 PASS와 FAIL을 함께 "
    "기록하며 서로 다른 계층 수를 합산하지 않는다. current source-freeze Android "
    "device run은 NOT_RUN_CURRENT_SOURCE_FREEZE로 기록하고, 2026-07-13 SM-G981N "
    "2/2는 별도 historical evidence로만 보존한다."
)


def historical_source_freeze_policy(executed_at: str) -> str:
    return (
        f"이 snapshot은 {executed_at} 기준의 과거 계층 증거이며 현재 hardening 수치가 "
        "아니다. PASS와 FAIL을 함께 기록하고 Unit·격리 DB·build를 Field 또는 Release "
        "증거로 확대하지 않으며, source freeze 후 전체 재실행 수치로 교체한다. current "
        "source-freeze에서 Android device run이 없으면 NOT_RUN_CURRENT_SOURCE_FREEZE로 "
        "기록하고, 2026-07-13 SM-G981N 2/2는 별도 historical evidence로만 보존한다."
    )


def candidate_source_freeze_policy(executed_at: str) -> str:
    return (
        f"이 snapshot은 {executed_at} hardening 작업 후보의 임시 자동검증 수치이며, 새 "
        "clean source candidate의 전체 재현 대조를 통과해야 채택한다. 사용자가 승인한 "
        "최종 source-freeze commit이나 Field 또는 Release 증거가 아니고 PASS와 FAIL을 "
        "함께 기록하며 서로 다른 계층 수를 합산하지 않는다. current source-freeze "
        "Android device run은 NOT_RUN_CURRENT_SOURCE_FREEZE로 기록하고, 2026-07-13 "
        "SM-G981N 2/2는 별도 historical evidence로만 보존한다."
    )


def verification_snapshot_boundary(status: object, executed_at: object) -> str:
    """Return the canonical disclosure for one verification snapshot state."""

    if not isinstance(status, str) or not isinstance(executed_at, str):
        raise ValueError("verification snapshot status and executed_at must be strings")
    if re.fullmatch(r"\d{4}-\d{2}-\d{2}", executed_at) is None:
        raise ValueError("verification snapshot executed_at must be an ISO date")
    try:
        datetime.strptime(executed_at, "%Y-%m-%d")
    except ValueError as exc:
        raise ValueError("verification snapshot executed_at must be an ISO date") from exc
    if status == f"HISTORICAL_PRE_SOURCE_FREEZE_{executed_at}":
        return "과거 pre-source-freeze snapshot · 현재 hardening 수치 아님"
    if status == f"SOURCE_FREEZE_CANDIDATE_{executed_at}":
        return "source-freeze 후보 검증 snapshot · 최종 승인 source-freeze 아님"
    if status == f"SOURCE_FREEZE_APPROVED_{executed_at}":
        return "승인된 source-freeze 검증 snapshot"
    raise ValueError(f"unsupported verification snapshot status: {status}")


def verification_snapshot_policy_boundary(
    status: object,
    executed_at: object,
    policy: object,
) -> str:
    """Validate the generator-side exact state/policy contract and return its boundary."""

    boundary = verification_snapshot_boundary(status, executed_at)
    assert isinstance(status, str) and isinstance(executed_at, str)
    if status == f"HISTORICAL_PRE_SOURCE_FREEZE_{executed_at}":
        expected_policy = historical_source_freeze_policy(executed_at)
        label = "historical"
    elif status == f"SOURCE_FREEZE_CANDIDATE_{executed_at}":
        expected_policy = candidate_source_freeze_policy(executed_at)
        label = "candidate"
    else:
        expected_policy = APPROVED_SOURCE_FREEZE_POLICY
        label = "approved"
    if policy != expected_policy:
        raise ValueError(f"verification snapshot must use the exact {label} policy contract")
    return boundary


def canonical_android_device_evidence(
    snapshot: object,
) -> tuple[dict[str, object], dict[str, object], str, str]:
    """Validate and format the canonical current/historical device evidence split."""

    if not isinstance(snapshot, dict):
        raise ValueError("Android device verification snapshot is invalid")
    verification = snapshot.get("android_device_verification")
    if not isinstance(verification, dict) or set(verification) != {
        "current_source_freeze",
        "historical_evidence",
    }:
        raise ValueError("Android device verification must contain current and historical evidence")
    current = verification.get("current_source_freeze")
    historical = verification.get("historical_evidence")
    if not isinstance(current, dict) or set(current) != {
        "status",
        "executed_at",
        "passed",
        "total",
    }:
        raise ValueError("Android device verification current source-freeze contract is invalid")
    if not isinstance(historical, dict) or set(historical) != {
        "status",
        "executed_at",
        "device",
        "scope",
        "passed",
        "total",
    }:
        raise ValueError("Android device verification historical evidence contract is invalid")

    snapshot_date = snapshot.get("executed_at")
    if current != CURRENT_ANDROID_DEVICE_VERIFICATION:
        raise ValueError("Android device verification current NOT_RUN contract is invalid")
    current_label = "현재 source-freeze device: NOT_RUN_CURRENT_SOURCE_FREEZE"

    try:
        parsed_snapshot_date = datetime.strptime(str(snapshot_date), "%Y-%m-%d")
    except ValueError as exc:
        raise ValueError("Android device verification historical date is invalid") from exc
    if (
        historical != HISTORICAL_ANDROID_DEVICE_EVIDENCE
        or type(historical.get("passed")) is not int
        or type(historical.get("total")) is not int
        or datetime(2026, 7, 13) > parsed_snapshot_date
    ):
        raise ValueError("Android device verification historical PASS contract is invalid")
    historical_label = (
        f"과거 evidence {historical['executed_at']} {historical['device']}: "
        f"instrumentation {historical['passed']}/{historical['total']} "
        "PASS_HISTORICAL_EVIDENCE"
    )
    return current, historical, current_label, historical_label


@contextmanager
def submission_build_lock(repository_root: Path) -> Iterator[None]:
    """Serialize submission generators that read and replace shared assets."""

    identity = hashlib.sha256(str(repository_root.resolve()).encode("utf-8")).hexdigest()[:16]
    lock_path = Path(tempfile.gettempdir()) / f"walksafe-submission-{identity}.lock"
    with lock_path.open("a+b") as lock_file:
        fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX)
        try:
            yield
        finally:
            fcntl.flock(lock_file.fileno(), fcntl.LOCK_UN)


@contextmanager
def atomic_output_path(target: Path) -> Iterator[Path]:
    """Build one artifact beside its target and publish it with os.replace()."""

    target.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        dir=target.parent,
        prefix=f".{target.name}.",
        suffix=".tmp",
    )
    os.close(descriptor)
    temporary = Path(temporary_name)
    try:
        yield temporary
        if not temporary.is_file() or temporary.stat().st_size == 0:
            raise RuntimeError(f"submission artifact was not written: {target}")
        with temporary.open("rb") as stream:
            os.fsync(stream.fileno())
        os.replace(temporary, target)
        directory_fd = os.open(target.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    finally:
        temporary.unlink(missing_ok=True)

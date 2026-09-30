"""Real descriptor ownership at the key-provider parent validation boundary."""

from __future__ import annotations

import asyncio
from collections import Counter
import errno
import os
from pathlib import Path
from types import SimpleNamespace

import pytest

from backend.app.services import report_image_keys as keys


@pytest.fixture
def directory_descriptors(monkeypatch):
    opened: list[int] = []
    closed: list[int] = []

    def open_directory(*args, **kwargs):
        descriptor = os.open(*args, **kwargs)
        opened.append(descriptor)
        return descriptor

    def close_directory(descriptor):
        closed.append(descriptor)
        os.close(descriptor)

    proxy = SimpleNamespace(**vars(os))
    proxy.open = open_directory
    proxy.close = close_directory
    monkeypatch.setattr(keys, "os", proxy)
    try:
        yield SimpleNamespace(opened=opened, closed=closed, os=proxy)
    finally:
        # Keep pre-fix regression failures from leaking their real descriptors.
        for descriptor in opened:
            if descriptor not in closed:
                os.close(descriptor)


def _assert_closed_once(tracker):
    assert tracker.closed == list(reversed(tracker.opened))
    assert Counter(tracker.closed) == Counter(tracker.opened)
    for descriptor in tracker.opened:
        with pytest.raises(OSError) as error:
            os.fstat(descriptor)
        assert error.value.errno == errno.EBADF


@pytest.mark.parametrize("caller", ["helper", "secret_file", "kms_agent"])
@pytest.mark.parametrize("boundary", ["root", "child"])
@pytest.mark.parametrize("failure", ["fstat", "authority", "cancellation"])
def test_parent_validation_failure_closes_each_acquired_descriptor_once(
    tmp_path: Path, monkeypatch, directory_descriptors, caller, boundary, failure,
):
    tracker = directory_descriptors
    target_index = 0 if boundary == "root" else 1
    target_error = (
        OSError(errno.EIO, "owned fstat fault") if failure == "fstat"
        else asyncio.CancelledError("owned validation cancellation") if failure == "cancellation"
        else keys.ReportImageKeyringInvalid("owned directory authority failure")
    )
    real_validate = keys._validate_directory_authority

    def stat_directory(descriptor):
        if tracker.opened.index(descriptor) == target_index and failure == "fstat":
            raise target_error
        return os.fstat(descriptor)

    def validate_directory(metadata, **kwargs):
        if len(tracker.opened) - 1 == target_index and failure != "fstat":
            raise target_error
        return real_validate(metadata, **kwargs)

    tracker.os.fstat = stat_directory
    monkeypatch.setattr(keys, "_validate_directory_authority", validate_directory)
    path = tmp_path / "unopened-key-provider"
    if caller == "helper":
        load = lambda: keys._open_pinned_parent(path, require_root_authority=False)
    elif caller == "secret_file":
        load = keys.SecretFileReportImageKeyProvider(path).load
    else:
        load = keys.KmsAgentReportImageKeyProvider(
            path, expected_peer_uid=os.geteuid() + 1, timeout_seconds=1,
        ).load
    expected_error = (
        keys.ReportImageKeyUnavailable
        if failure == "fstat" and caller != "helper"
        else type(target_error)
    )
    with pytest.raises(expected_error) as captured:
        load()
    if expected_error is keys.ReportImageKeyUnavailable:
        assert captured.value.__cause__ is target_error
    else:
        assert captured.value is target_error
    assert len(tracker.opened) == target_index + 1
    _assert_closed_once(tracker)


@pytest.mark.parametrize("boundary", ["root", "child"])
def test_parent_open_failure_closes_only_already_acquired_descriptors(
    tmp_path: Path, directory_descriptors, boundary,
):
    tracker = directory_descriptors
    real_open = tracker.os.open
    target_index = 0 if boundary == "root" else 1
    failure = OSError(errno.EACCES, "owned directory open failure")

    def open_directory(*args, **kwargs):
        if len(tracker.opened) == target_index:
            raise failure
        return real_open(*args, **kwargs)

    tracker.os.open = open_directory
    with pytest.raises(OSError) as captured:
        keys._open_pinned_parent(tmp_path / "keyring", require_root_authority=False)
    assert captured.value is failure
    assert len(tracker.opened) == target_index
    _assert_closed_once(tracker)


def test_unsafe_final_parent_closes_the_rejected_real_directory(tmp_path, directory_descriptors):
    tmp_path.chmod(0o755)
    with pytest.raises(keys.ReportImageKeyringInvalid, match="parent must be private"):
        keys._open_pinned_parent(tmp_path / "keyring", require_root_authority=False)
    assert len(directory_descriptors.opened) > 2
    _assert_closed_once(directory_descriptors)


def test_successful_parent_validation_transfers_all_descriptor_ownership(
    tmp_path, directory_descriptors,
):
    tracker = directory_descriptors
    pinned = keys._open_pinned_parent(tmp_path / "keyring", require_root_authority=False)
    assert tracker.closed == []
    assert [directory.descriptor for directory in pinned] == tracker.opened
    assert pinned[0].parent_descriptor is None
    assert [directory.parent_descriptor for directory in pinned[1:]] == tracker.opened[:-1]
    keys._revalidate_pinned_directories(pinned, require_root_authority=False)
    keys._close_pinned_directories(pinned)
    _assert_closed_once(tracker)

#!/usr/bin/env python3
"""Non-effective R025 content-CAS transaction supervisor.

The protocol is deliberately fail-closed.  It grants no activation, canonical,
checkpoint, Goal, or product authority.  Its only project-write capability is
the reviewed builder's add-only r002 generator call in ``PUBLISH_P3``.
"""

from __future__ import annotations

import argparse
import base64
import binascii
import fcntl
import hashlib
import json
import os
import re
import secrets
import select
import stat
import subprocess
import sys
import time
import types
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path, PurePosixPath
from typing import Any, Mapping


MAX_FRAME_BYTES = 4 * 1024 * 1024
FRAME_TIMEOUT_SECONDS = 3600
UID = GID = 1000
PLAN_ROOT_REL = "plans/features/2026-07-29_walksafe_plan_rebaseline_r001"
BUNDLE_REL = f"{PLAN_ROOT_REL}/v2-5-control-candidate-r002"
RUN_ROOT_REL = "docs/control/execution/artifact-closure/run-20260727-001"
P2A_REL = f"{RUN_ROOT_REL}/walksafe-v25-r002-preparation-capture-20260802-r025-r001.json"
P2C_REL = f"{RUN_ROOT_REL}/walksafe-v25-r002-source-postimage-20260802-r025-r001.json"
P4_STRUCTURAL_REL = f"{PLAN_ROOT_REL}/v2-5-control-candidate-r002-independent-structural-review-r001.md"
P4_SKEPTICAL_REL = f"{PLAN_ROOT_REL}/v2-5-control-candidate-r002-independent-skeptical-review-r001.md"
P5_REL = f"{RUN_ROOT_REL}/walksafe-v25-r002-reviewed-closure-20260802-r025-r001.json"
OUTPUT_NAMES = (
    "candidate-output-manifest.json",
    "static-plan-manifest-v2.5.0.candidate.json",
    "transition-history-v2.5.candidate.json",
    "v2.5-application-transaction-plan.candidate.json",
    "v2.5-control-package-manifest.candidate.json",
    "walksafe-project-continuation-checkpoint-v2.5.candidate.json",
)
MODULE_SPECS = (
    ("core", "scripts/walksafe_v2_5_candidate_validation.py", "scripts.walksafe_v2_5_candidate_validation"),
    ("builder", "scripts/build_walksafe_v2_5_control_candidate_20260730.py", "scripts.build_walksafe_v2_5_control_candidate_20260730"),
    ("continuation", "scripts/check_walksafe_project_continuation_v2_5_candidate.py", "scripts.check_walksafe_project_continuation_v2_5_candidate"),
    ("goal", "scripts/check_walksafe_goal_graph_v2_5_candidate.py", "scripts.check_walksafe_goal_graph_v2_5_candidate"),
    ("test", "tests/test_walksafe_v2_5_control_candidate_20260730.py", "tests.test_walksafe_v2_5_control_candidate_20260730"),
)
S1_ROLES = ("source_validation_core_s1", "source_builder_s1", "source_test_s1")
SEAL_MASK = fcntl.F_SEAL_WRITE | fcntl.F_SEAL_GROW | fcntl.F_SEAL_SHRINK | fcntl.F_SEAL_SEAL
SHA_RE = re.compile(r"[0-9a-f]{64}\Z")
NONCE_RE = SHA_RE
AUTHORITY_BOUNDARY = {
    "applied": False, "approved": False, "canonical_write_authorized": False,
    "checkpoint_write_authorized": False, "effective": False, "evidence_only": True,
    "goal_write_authorized": False, "product_write_authorized": False,
}


class Failure(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise Failure(message)


def exact_object(value: Any, keys: set[str], label: str) -> dict[str, Any]:
    require(type(value) is dict, f"{label}: object required")
    require(set(value) == keys, f"{label}: exact keys required")
    return value


def canonical_line(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False) + "\n").encode()


def strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        require(key not in result, "duplicate JSON key")
        result[key] = value
    return result


def reject_constant(value: str) -> None:
    raise Failure(f"non-finite JSON number: {value}")


def strict_json_line(raw: bytes, label: str) -> dict[str, Any]:
    require(raw.endswith(b"\n") and not raw.endswith(b"\n\n"), f"{label}: one LF required")
    require(len(raw) <= MAX_FRAME_BYTES and not raw.startswith(b"\xef\xbb\xbf"), f"{label}: size/BOM")
    require(b"\r" not in raw and b"\x00" not in raw, f"{label}: CR/NUL")
    try:
        value = json.loads(raw[:-1].decode("utf-8"), object_pairs_hook=strict_object, parse_constant=reject_constant)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise Failure(f"{label}: invalid JSON") from exc
    require(type(value) is dict and canonical_line(value) == raw, f"{label}: noncanonical JSON")
    return value


def binding(value: Any, label: str, expected_path: str | None = None) -> dict[str, Any]:
    item = exact_object(value, {"bytes", "path", "sha256"}, label)
    require(type(item["path"]) is str and (expected_path is None or item["path"] == expected_path), f"{label}: path")
    require(type(item["bytes"]) is int and item["bytes"] >= 0, f"{label}: bytes")
    require(type(item["sha256"]) is str and SHA_RE.fullmatch(item["sha256"]) is not None, f"{label}: sha256")
    return dict(item)


def decode_blob(value: Any, label: str) -> bytes:
    require(type(value) is str, f"{label}: base64 string required")
    try:
        encoded = value.encode("ascii")
        raw = base64.b64decode(encoded, validate=True)
    except (UnicodeEncodeError, binascii.Error) as exc:
        raise Failure(f"{label}: invalid standard base64") from exc
    require(base64.b64encode(raw) == encoded, f"{label}: noncanonical base64")
    return raw


def verify_raw(raw: bytes, expected: Mapping[str, Any], label: str) -> None:
    require(len(raw) == expected["bytes"] and hashlib.sha256(raw).hexdigest() == expected["sha256"], f"{label}: content binding")


@dataclass
class HeldFile:
    row: dict[str, Any]
    raw: bytes
    fd: int
    directory_fds: tuple[int, ...]

    def close(self) -> None:
        os.close(self.fd)
        for descriptor in reversed(self.directory_fds):
            os.close(descriptor)


class AnchoredRepository:
    def __init__(self, root: Path):
        self.path = root.absolute()
        flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC
        self.fd = os.open(self.path, flags)
        self._safe_directory(os.fstat(self.fd), "repository root")

    @staticmethod
    def _safe_directory(info: os.stat_result, label: str) -> None:
        require(stat.S_ISDIR(info.st_mode), f"{label}: directory required")
        require((info.st_uid, info.st_gid) == (UID, GID) and not info.st_mode & stat.S_IWOTH, f"{label}: unsafe envelope")

    @staticmethod
    def _parts(relative: str, allowed: set[str]) -> tuple[str, ...]:
        require(type(relative) is str and relative in allowed and "\\" not in relative and "\x00" not in relative, "path not allowlisted")
        path = PurePosixPath(relative)
        require(not path.is_absolute() and path.as_posix() == relative and all(part not in ("", ".", "..") for part in path.parts), "path not normalized")
        return path.parts

    def _parent_chain(self, relative: str, allowed: set[str]) -> tuple[tuple[int, ...], str]:
        parts = self._parts(relative, allowed)
        descriptors = [os.dup(self.fd)]
        try:
            for part in parts[:-1]:
                descriptor = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=descriptors[-1])
                self._safe_directory(os.fstat(descriptor), relative)
                descriptors.append(descriptor)
            return tuple(descriptors), parts[-1]
        except BaseException:
            for descriptor in reversed(descriptors):
                os.close(descriptor)
            raise

    @staticmethod
    def _read_fd(descriptor: int, label: str) -> bytes:
        before = os.fstat(descriptor)
        require(stat.S_ISREG(before.st_mode) and before.st_nlink == 1, f"{label}: safe regular required")
        require((before.st_uid, before.st_gid) == (UID, GID) and not before.st_mode & stat.S_IWOTH, f"{label}: unsafe envelope")
        chunks, offset = [], 0
        while offset < before.st_size:
            chunk = os.pread(descriptor, min(1024 * 1024, before.st_size - offset), offset)
            require(bool(chunk), f"{label}: short read")
            chunks.append(chunk); offset += len(chunk)
        raw = b"".join(chunks)
        after = os.fstat(descriptor)
        fields = lambda item: (item.st_dev, item.st_ino, item.st_mode, item.st_uid, item.st_gid, item.st_nlink, item.st_size)
        require(fields(before) == fields(after) and len(raw) == before.st_size, f"{label}: changed during read")
        return raw

    def read(self, expected: Mapping[str, Any], allowed: set[str], *, retain: bool = False) -> bytes | HeldFile:
        row = binding(expected, "binding")
        chain, name = self._parent_chain(row["path"], allowed)
        first = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=chain[-1])
        try:
            raw = self._read_fd(first, row["path"]); verify_raw(raw, row, row["path"])
            second = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=chain[-1])
            try:
                reopened = self._read_fd(second, row["path"]); verify_raw(reopened, row, row["path"])
            finally:
                os.close(second)
            require(raw == reopened, f"{row['path']}: reopen mismatch")
            if retain:
                return HeldFile(dict(expected), raw, first, chain)
            return raw
        finally:
            if not retain:
                os.close(first)
                for descriptor in reversed(chain):
                    os.close(descriptor)

    def absent(self, relative: str) -> None:
        chain, name = self._parent_chain(relative, {relative})
        try:
            try:
                os.stat(name, dir_fd=chain[-1], follow_symlinks=False)
            except FileNotFoundError:
                return
            raise Failure(f"add-only target exists: {relative}")
        finally:
            for descriptor in reversed(chain):
                os.close(descriptor)

    def close(self) -> None:
        os.close(self.fd)


def sealed_memfd(name: str, raw: bytes) -> int:
    require(hasattr(os, "memfd_create"), "memfd unavailable")
    descriptor = os.memfd_create(name, os.MFD_CLOEXEC | os.MFD_ALLOW_SEALING)
    try:
        view = memoryview(raw)
        while view:
            written = os.write(descriptor, view); require(written > 0, "memfd short write"); view = view[written:]
        fcntl.fcntl(descriptor, fcntl.F_ADD_SEALS, SEAL_MASK)
        require(fcntl.fcntl(descriptor, fcntl.F_GET_SEALS) == SEAL_MASK, "memfd seal mismatch")
        require(os.pread(descriptor, len(raw) + 1, 0) == raw, "memfd content mismatch")
        return descriptor
    except BaseException:
        os.close(descriptor); raise


CHILD_BOOTSTRAP = r'''
import base64,fcntl,hashlib,json,os,stat,sys,types
MASK=fcntl.F_SEAL_WRITE|fcntl.F_SEAL_GROW|fcntl.F_SEAL_SHRINK|fcntl.F_SEAL_SEAL
def die(x): raise RuntimeError(x)
def pairs(xs):
 d={}
 for k,v in xs:
  if k in d: die("duplicate key")
  d[k]=v
 return d
def readfd(name):
 value=os.environ.get(name,"")
 if not value.isdecimal(): die("fd env")
 fd=int(value)
 if fcntl.fcntl(fd,fcntl.F_GET_SEALS)!=MASK: die("fd seals")
 info=os.fstat(fd); raw=os.pread(fd,info.st_size+1,0)
 if len(raw)!=info.st_size: die("fd bytes")
 return raw
source=readfd("WALKSAFE_R025_SEALED_SOURCE_FD")
anchor=readfd("WALKSAFE_R025_EXPECTED_ANCHOR_FD")
obj=json.loads(source.decode(),object_pairs_hook=pairs,parse_constant=die)
canon=(json.dumps(obj,ensure_ascii=False,sort_keys=True,separators=(",",":"),allow_nan=False)+"\n").encode()
if canon!=source or obj.get("schema_version")!="walksafe.r025.sealed-execution.v1": die("bundle")
if (json.dumps(obj["anchor"],ensure_ascii=False,sort_keys=True,separators=(",",":"),allow_nan=False)+"\n").encode()!=anchor: die("anchor")
root=obj["repo_root"]; sys.path[:]=[root]; sys.dont_write_bytecode=True
for package in ("scripts","tests"):
 module=types.ModuleType(package); module.__path__=[os.path.join(root,package)]; module.__package__=package; sys.modules[package]=module
loaded={}
for item in obj["modules"]:
 raw=base64.b64decode(item["content_b64"],validate=True)
 if len(raw)!=item["bytes"] or hashlib.sha256(raw).hexdigest()!=item["sha256"]: die("source binding")
 module=types.ModuleType(item["module_name"]); module.__file__=os.path.join(root,item["path"]); module.__package__=item["module_name"].rpartition(".")[0]
 sys.modules[item["module_name"]]=module; setattr(sys.modules[module.__package__],item["module_name"].rpartition(".")[2],module)
 exec(compile(raw,"<sealed:"+item["module_name"]+">","exec",dont_inherit=True),module.__dict__); loaded[item["role"]]=(item,raw)
item,raw=loaded[obj["entry_role"]]; sys.argv=[item["path"],*obj["argv"]]
scope={"__name__":"__main__","__file__":os.path.join(root,item["path"]),"__package__":item["module_name"].rpartition(".")[0]}
try: exec(compile(raw,"<sealed-entry:"+item["module_name"]+">","exec",dont_inherit=True),scope)
except SystemExit as exc:
 code=exc.code
 if code not in (None,0): raise
'''


class FrameReader:
    def __init__(self, descriptor: int): self.fd, self.buffer = descriptor, bytearray()

    def read(self) -> bytes:
        deadline = time.monotonic() + FRAME_TIMEOUT_SECONDS
        while b"\n" not in self.buffer:
            require(len(self.buffer) < MAX_FRAME_BYTES, "frame exceeds 4 MiB")
            remaining = deadline - time.monotonic(); require(remaining > 0, "frame timeout")
            ready, _, _ = select.select([self.fd], [], [], remaining); require(bool(ready), "frame timeout")
            chunk = os.read(self.fd, min(65536, MAX_FRAME_BYTES + 1 - len(self.buffer)))
            require(bool(chunk), "terminal EOF")
            self.buffer.extend(chunk)
        index = self.buffer.index(10); raw = bytes(self.buffer[:index + 1]); del self.buffer[:index + 1]
        require(len(raw) <= MAX_FRAME_BYTES, "frame exceeds 4 MiB")
        return raw


class Supervisor:
    def __init__(self, root: Path):
        self.repo = AnchoredRepository(root); self.root = self.repo.path
        self.reader = FrameReader(sys.stdin.fileno()); self.seq = 1; self.state = "WAIT_P2A_COMMIT"
        self.pending: str | None = None; self.held: dict[str, HeldFile] = {}; self.modules: dict[str, types.ModuleType] = {}
        self.p2a = self.p2c = self.anchor = self.candidate = self.challenge = self.p5 = None
        self.s1_rows: list[dict[str, Any]] = []; self.outputs: dict[str, bytes] = {}; self.reviews: list[dict[str, Any]] = []

    def emit(self, op: str, payload: Mapping[str, Any]) -> None:
        raw = canonical_line({"op": op, "payload": dict(payload), "seq": self.seq, "state": self.state})
        try:
            sys.stdout.buffer.write(raw); sys.stdout.buffer.flush()
        except BrokenPipeError as exc:
            raise Failure("terminal broken pipe") from exc

    def ready(self, state: str, op: str, payload: Mapping[str, Any] = {}) -> None:
        nonce = secrets.token_hex(32); require(NONCE_RE.fullmatch(nonce) is not None, "nonce generation")
        self.pending, self.state = nonce, state; self.emit(op, {**payload, "nonce": nonce})

    def consume_nonce(self, payload: Any, extra: set[str] = set()) -> dict[str, Any]:
        value = exact_object(payload, {"nonce", *extra}, "ACK payload")
        nonce = value["nonce"]
        require(type(nonce) is str and NONCE_RE.fullmatch(nonce) is not None and self.pending is not None, "nonce shape")
        require(secrets.compare_digest(nonce, self.pending), "nonce mismatch")
        self.pending = None
        return value

    def document_commit(self, payload: Any, path: str, keys: set[str], schema: str) -> tuple[dict[str, Any], bytes, dict[str, Any]]:
        value = exact_object(payload, {"binding", "content_b64"}, "document commit")
        row = binding(value["binding"], "document binding", path); raw = decode_blob(value["content_b64"], "document")
        verify_raw(raw, row, path); obj = strict_json_line(raw, path)
        exact_object(obj, keys, path); require(obj.get("schema_version") == schema and obj.get("roadmap_revision") == "R025", f"{path}: identity")
        return row, raw, obj

    def validate_boundary(self) -> None:
        for item in self.held.values():
            require((fcntl.fcntl(item.fd, fcntl.F_GETFL) & os.O_ACCMODE) == os.O_RDONLY, "held fd not O_RDONLY")
            require(fcntl.fcntl(item.fd, fcntl.F_GETFD) & fcntl.FD_CLOEXEC, "held fd not CLOEXEC")
            verify_raw(self.repo._read_fd(item.fd, item.row["path"]), item.row, "held source")
            require(self.repo.read(item.row, {item.row["path"]}) == item.raw, "live source differs")
        for item in (self.p2a, self.p2c, self.p5):
            if item is not None:
                require(self.repo.read(item["binding"], {item["binding"]["path"]}) == item["raw"], "receipt differs")
        if self.candidate is not None:
            self.validate_candidate(self.candidate)

    def validate_candidate(self, candidate: Mapping[str, Any]) -> None:
        value = exact_object(candidate, {"candidate_path", "entry_count", "entry_name_digest_sha256", "files", "source_transition_anchor_sha256"}, "candidate")
        require(value["candidate_path"] == BUNDLE_REL and value["entry_count"] == 6, "candidate root/count")
        names = sorted(OUTPUT_NAMES, key=lambda name: name.encode()); expected_files = []
        require(type(value["files"]) is list and len(value["files"]) == 6, "candidate files")
        for index, name in enumerate(names):
            row = binding(value["files"][index], "candidate file", f"{BUNDLE_REL}/{name}")
            require(self.repo.read(row, {row["path"]}) is not None, "candidate content"); expected_files.append(row)
        digest = hashlib.sha256(b"".join(name.encode() + b"\0" for name in names)).hexdigest()
        require(value["files"] == expected_files and value["entry_name_digest_sha256"] == digest, "candidate order/name digest")
        require(value["source_transition_anchor_sha256"] == hashlib.sha256(canonical_line(self.anchor)[:-1]).hexdigest(), "candidate anchor")

    def source_commit(self, payload: Any) -> None:
        value = exact_object(payload, {"sources", "wrappers"}, "S1 binding commit")
        require(type(value["sources"]) is list and len(value["sources"]) == 3 and type(value["wrappers"]) is list and len(value["wrappers"]) == 2, "five-module bundle")
        items = [*value["sources"], *value["wrappers"]]
        for index, (role, path, module_name) in enumerate(MODULE_SPECS):
            keys = {"bytes", "content_b64", "module_name", "path", "role", "sha256"}
            if index < 3: keys.add("ordinal")
            item = exact_object(items[index], keys, f"module {role}")
            require(item["role"] == role and item["path"] == path and item["module_name"] == module_name, f"module {role}: identity")
            if index < 3: require(item["ordinal"] == index + 1, f"module {role}: ordinal")
            raw = decode_blob(item["content_b64"], f"module {role}"); verify_raw(raw, item, f"module {role}")
            self.held[role] = HeldFile(dict(item), raw, -1, ())
        self.s1_rows = [{"bytes": items[i]["bytes"], "ordinal": i + 1, "path": items[i]["path"], "role": S1_ROLES[i], "sha256": items[i]["sha256"]} for i in range(3)]

    def hold_sources(self) -> None:
        committed = self.held; self.held = {}
        try:
            for role, item in committed.items():
                held = self.repo.read(item.row, {item.row["path"]}, retain=True)
                require(isinstance(held, HeldFile) and held.raw == item.raw, f"{role}: committed/live mismatch")
                self.held[role] = held
        finally:
            for role, item in committed.items():
                if item.fd >= 0: item.close()

    def load_modules(self) -> dict[str, types.ModuleType]:
        for package in ("scripts", "tests"):
            module = types.ModuleType(package); module.__path__ = [str(self.root / package)]; module.__package__ = package; sys.modules[package] = module
        result: dict[str, types.ModuleType] = {}
        for role, path, name in MODULE_SPECS:
            item = self.held[role]; module = types.ModuleType(name)
            module.__file__ = str(self.root / path); module.__package__ = name.rpartition(".")[0]
            sys.modules[name] = module; setattr(sys.modules[module.__package__], name.rpartition(".")[2], module)
            exec(compile(item.raw, f"<held:{name}>", "exec", dont_inherit=True), module.__dict__); result[role] = module
        return result

    def run_children(self, payload: Any) -> list[dict[str, Any]]:
        value = exact_object(payload, {"runs"}, "run request"); runs = value["runs"]
        require(type(runs) is list and 1 <= len(runs) <= 16, "runs")
        self.validate_boundary(); results = []
        for index, spec in enumerate(runs):
            spec = exact_object(spec, {"argv", "entry_role", "expected_returncode", "timeout_seconds"}, f"run {index}")
            require(spec["entry_role"] in {row[0] for row in MODULE_SPECS}, "entry role")
            require(type(spec["argv"]) is list and all(type(arg) is str and "\0" not in arg for arg in spec["argv"]), "argv")
            require("--write" not in spec["argv"] and spec["expected_returncode"] == 0, "child write/returncode")
            require(type(spec["timeout_seconds"]) is int and 1 <= spec["timeout_seconds"] <= FRAME_TIMEOUT_SECONDS, "child timeout")
            modules = [{"bytes": item.raw.__len__(), "content_b64": base64.b64encode(item.raw).decode(), "module_name": item.row["module_name"], "path": item.row["path"], "role": role, "sha256": hashlib.sha256(item.raw).hexdigest()} for role, item in self.held.items()]
            bundle = {"anchor": self.anchor, "argv": spec["argv"], "entry_role": spec["entry_role"], "modules": modules, "repo_root": str(self.root), "schema_version": "walksafe.r025.sealed-execution.v1"}
            source_fd = sealed_memfd("walksafe-r025-sources", canonical_line(bundle)); anchor_fd = sealed_memfd("walksafe-r025-anchor", canonical_line(self.anchor))
            try:
                completed = subprocess.run([sys.executable, "-I", "-S", "-B", "-c", CHILD_BOOTSTRAP], cwd=self.root, env={"WALKSAFE_R025_EXPECTED_ANCHOR_FD": str(anchor_fd), "WALKSAFE_R025_SEALED_SOURCE_FD": str(source_fd)}, pass_fds=(anchor_fd, source_fd), close_fds=True, capture_output=True, timeout=spec["timeout_seconds"], check=False)
            finally:
                os.close(source_fd); os.close(anchor_fd)
            require(completed.returncode == 0, f"child {index} failed")
            results.append({"returncode": completed.returncode, "stderr_bytes": len(completed.stderr), "stderr_sha256": hashlib.sha256(completed.stderr).hexdigest(), "stdout_bytes": len(completed.stdout), "stdout_sha256": hashlib.sha256(completed.stdout).hexdigest()})
            self.validate_boundary()
        return results

    def review_ack(self, payload: Any, expected_path: str) -> dict[str, Any]:
        value = self.consume_nonce(payload, {"review"}); review = exact_object(value["review"], {"authority", "bytes", "candidate_binding_sha256", "challenge_bytes", "challenge_sha256", "findings", "path", "sha256", "source_transition_anchor_sha256", "status"}, "review")
        row = binding({key: review[key] for key in ("bytes", "path", "sha256")}, "review binding", expected_path)
        expected_anchor = hashlib.sha256(canonical_line(self.anchor)[:-1]).hexdigest(); expected_candidate = hashlib.sha256(canonical_line(self.candidate)[:-1]).hexdigest()
        require(review["status"] == "PASS_FOR_NON_EFFECTIVE_V25_CANDIDATE_ONLY" and review["findings"] == "BLOCKING=0 MAJOR=0 MINOR=0" and review["authority"] == "NONE_FOR_ACTIVATION_GOAL_PRODUCT", "review verdict")
        require(review["challenge_sha256"] == hashlib.sha256(self.challenge).hexdigest() and review["challenge_bytes"] == len(self.challenge), "review challenge")
        require(review["source_transition_anchor_sha256"] == expected_anchor and review["candidate_binding_sha256"] == expected_candidate, "review anchors")
        raw = self.repo.read(row, {expected_path}); text = raw.decode("utf-8")
        for token in (review["status"], review["findings"], review["authority"], review["challenge_sha256"], expected_anchor, expected_candidate): require(token in text, "review content field")
        saved = dict(review); saved["raw"] = raw; return saved

    def dispatch(self, op: str, payload: Any) -> None:
        if self.state == "WAIT_P2A_COMMIT":
            require(op == "COMMIT_P2A", "expected COMMIT_P2A")
            row, raw, obj = self.document_commit(payload, P2A_REL, {"authority_boundary", "candidate_target", "capture_algorithm", "causal_max", "immutable_causal_inputs", "observation", "pre_capture_state", "receipt_id", "roadmap_revision", "schema_version", "source_preimage"}, "walksafe.v2.5.r002-preparation-capture.v6")
            require(obj["authority_boundary"] == AUTHORITY_BOUNDARY, "P2A authority"); exact_object(obj["causal_max"], {"ns", "role"}, "P2A causal_max")
            require(type(obj["source_preimage"]) is list and len(obj["source_preimage"]) == 3, "P2A source preimage")
            self.p2a = {"binding": row, "raw": raw, "object": obj}; self.ready("WAIT_P2A_ACK", "READY_P2A", {"binding": row})
        elif self.state == "WAIT_P2A_ACK":
            require(op == "ACK_P2A", "expected ACK_P2A"); self.consume_nonce(payload)
            require(self.repo.read(self.p2a["binding"], {P2A_REL}) == self.p2a["raw"], "P2A live mismatch")
            self.state = "WAIT_S1_BINDING_COMMIT"; self.emit("P2A_ACKNOWLEDGED", {})
        elif self.state == "WAIT_S1_BINDING_COMMIT":
            require(op == "COMMIT_S1_BINDINGS", "expected COMMIT_S1_BINDINGS"); self.source_commit(payload)
            self.ready("WAIT_S1_ACK", "READY_S1", {"module_count": 5})
        elif self.state == "WAIT_S1_ACK":
            require(op == "ACK_S1", "expected ACK_S1"); self.consume_nonce(payload); self.hold_sources(); self.validate_boundary()
            self.state = "WAIT_P2C_COMMIT"; self.emit("S1_ACKNOWLEDGED", {"held_fd_count": 5})
        elif self.state == "WAIT_P2C_COMMIT":
            require(op == "COMMIT_P2C", "expected COMMIT_P2C")
            row, raw, obj = self.document_commit(payload, P2C_REL, {"authority_boundary", "capture_receipt_binding", "captured_ns", "receipt_id", "roadmap_revision", "schema_version", "source_postimage", "source_preimage_binding"}, "walksafe.v2.5.r002-source-postimage.v5")
            require(obj["authority_boundary"] == AUTHORITY_BOUNDARY and obj["capture_receipt_binding"] == self.p2a["binding"] and obj["source_postimage"] == self.s1_rows, "P2C original bindings")
            self.p2c = {"binding": row, "raw": raw, "object": obj}; self.anchor = {"preparation_capture_receipt_binding": self.p2a["binding"], "source_postimage": self.s1_rows, "source_postimage_receipt_binding": row}
            self.ready("WAIT_P2C_ACK", "READY_P2C", {"binding": row})
        elif self.state == "WAIT_P2C_ACK":
            require(op == "ACK_P2C", "expected ACK_P2C"); self.consume_nonce(payload); self.validate_boundary()
            self.state = "WAIT_PREBUILD_RUN"; self.emit("P2C_ACKNOWLEDGED", {"source_transition_anchor_sha256": hashlib.sha256(canonical_line(self.anchor)[:-1]).hexdigest()})
        elif self.state == "WAIT_PREBUILD_RUN":
            require(op == "RUN_PREBUILD", "expected RUN_PREBUILD"); results = self.run_children(payload)
            self.state = "WAIT_P3_COMMIT"; self.emit("PREBUILD_COMPLETE", {"results": results})
        elif self.state == "WAIT_P3_COMMIT":
            require(op == "COMMIT_P3", "expected COMMIT_P3"); value = exact_object(payload, {"candidate_binding"}, "P3 commit")
            self.validate_boundary(); self.repo.absent(BUNDLE_REL); self.candidate = value["candidate_binding"]; self.modules = self.load_modules()
            builder = self.modules["builder"]; outputs = builder.build_outputs(self.root, expected_source_transition_anchor=self.anchor)
            require(type(outputs) is dict and set(outputs) == set(OUTPUT_NAMES) and all(type(raw) is bytes for raw in outputs.values()), "builder outputs")
            self.outputs = dict(outputs)
            files = sorted(self.candidate["files"], key=lambda row: Path(row["path"]).name.encode())
            for row in files: verify_raw(self.outputs[Path(row["path"]).name], row, "committed P3 output")
            self.ready("WAIT_P3_PUBLISH", "READY_P3", {"candidate_binding_sha256": hashlib.sha256(canonical_line(self.candidate)[:-1]).hexdigest()})
        elif self.state == "WAIT_P3_PUBLISH":
            require(op == "PUBLISH_P3", "expected PUBLISH_P3"); self.consume_nonce(payload); self.validate_boundary(); self.repo.absent(BUNDLE_REL)
            result = self.modules["builder"].write_add_only(self.root, self.outputs, expected_source_transition_anchor=self.anchor)
            require(result == "PUBLISHED_NEW", "P3 must publish new"); self.validate_candidate(self.candidate)
            self.state = "WAIT_POSTBUILD_RUN"; self.emit("P3_PUBLISHED", {"publication_result": result})
        elif self.state == "WAIT_POSTBUILD_RUN":
            require(op == "RUN_POSTBUILD", "expected RUN_POSTBUILD"); results = self.run_children(payload)
            self.state = "WAIT_P4_CHALLENGE_EMIT"; self.emit("POSTBUILD_COMPLETE", {"results": results})
        elif self.state == "WAIT_P4_CHALLENGE_EMIT":
            require(op == "EMIT_P4_CHALLENGE", "expected EMIT_P4_CHALLENGE"); exact_object(payload, set(), "challenge emit"); self.validate_boundary()
            challenge = {"authority_boundary": AUTHORITY_BOUNDARY, "candidate_binding": self.candidate, "challenge_id": "WS-WALKSAFE-V25-R002-REVIEW-CHALLENGE-20260802-R025-R001", "p2a": self.p2a["binding"], "p2c": self.p2c["binding"], "roadmap_revision": "R025", "schema_version": "walksafe.v2.5.r002-review-challenge.v2", "source_postimage": self.s1_rows, "source_transition_anchor": self.anchor, "source_transition_anchor_sha256": hashlib.sha256(canonical_line(self.anchor)[:-1]).hexdigest()}
            self.challenge = canonical_line(challenge); self.ready("WAIT_P4_STRUCTURAL_ACK", "P4_CHALLENGE", {"challenge_binding": {"bytes": len(self.challenge), "sha256": hashlib.sha256(self.challenge).hexdigest()}, "content_b64": base64.b64encode(self.challenge).decode()})
        elif self.state == "WAIT_P4_STRUCTURAL_ACK":
            require(op == "ACK_P4_STRUCTURAL", "expected ACK_P4_STRUCTURAL"); self.validate_boundary(); self.reviews.append(self.review_ack(payload, P4_STRUCTURAL_REL)); self.validate_boundary()
            self.ready("WAIT_P4_SKEPTICAL_ACK", "READY_P4_SKEPTICAL", {"challenge_sha256": hashlib.sha256(self.challenge).hexdigest()})
        elif self.state == "WAIT_P4_SKEPTICAL_ACK":
            require(op == "ACK_P4_SKEPTICAL", "expected ACK_P4_SKEPTICAL"); self.validate_boundary(); self.reviews.append(self.review_ack(payload, P4_SKEPTICAL_REL)); self.validate_boundary()
            self.state = "WAIT_P5_COMMIT"; self.emit("P4_REVIEWS_ACKNOWLEDGED", {})
        elif self.state == "WAIT_P5_COMMIT":
            require(op == "COMMIT_P5", "expected COMMIT_P5"); value = exact_object(payload, {"binding", "closed_at", "closed_ns", "content_b64"}, "P5 commit")
            require(type(value["closed_ns"]) is int and value["closed_ns"] % 1000 == 0, "P5 closed_ns")
            seconds, nanos = divmod(value["closed_ns"], 1_000_000_000); observed = datetime.fromtimestamp(seconds, timezone(timedelta(hours=9))) + timedelta(microseconds=nanos // 1000)
            require(value["closed_at"] == observed.isoformat(timespec="microseconds"), "P5 closed_at")
            review_rows = [{key: row[key] for key in ("authority", "bytes", "findings", "path", "sha256", "status")} for row in self.reviews]
            expected = {"authority_boundary": AUTHORITY_BOUNDARY, "candidate_binding": self.candidate, "candidate_reviews": review_rows, "challenge_binding": {"bytes": len(self.challenge), "sha256": hashlib.sha256(self.challenge).hexdigest()}, "closure_id": "WS-WALKSAFE-V25-R002-REVIEWED-CLOSURE-20260802-R025-R001", "closure_observation": {"closed_at": value["closed_at"], "closed_ns": value["closed_ns"]}, "roadmap_revision": "R025", "schema_version": "walksafe.v2.5.r002-reviewed-closure.v2", "source_transition_anchor_sha256": hashlib.sha256(canonical_line(self.anchor)[:-1]).hexdigest(), "source_transition_evidence": self.anchor}
            row = binding(value["binding"], "P5 binding", P5_REL); raw = decode_blob(value["content_b64"], "P5"); verify_raw(raw, row, "P5"); require(strict_json_line(raw, "P5") == expected, "P5 object")
            self.p5 = {"binding": row, "raw": raw, "object": expected}; self.ready("WAIT_P5_ACK", "READY_P5", {"binding": row})
        elif self.state == "WAIT_P5_ACK":
            require(op == "ACK_P5", "expected ACK_P5"); self.consume_nonce(payload); self.validate_boundary()
            self.ready("WAIT_HANDOFF_ACK", "READY_HANDOFF", {"closure_binding": self.p5["binding"]})
        elif self.state == "WAIT_HANDOFF_ACK":
            require(op == "ACK_HANDOFF", "expected ACK_HANDOFF"); value = self.consume_nonce(payload, {"channels", "closure_binding"})
            require(value["closure_binding"] == self.p5["binding"] and value["channels"] == ["daylog", "local-memory", "user-visible-final"], "handoff binding/channels")
            self.state = "COMPLETE"; self.emit("COMPLETE", {"closure_binding": self.p5["binding"], "effective": False})
        else:
            raise Failure("terminal state")

    def run(self) -> None:
        while self.state != "COMPLETE":
            frame = strict_json_line(self.reader.read(), "IPC frame"); exact_object(frame, {"op", "payload", "seq"}, "IPC frame")
            require(type(frame["seq"]) is int and frame["seq"] == self.seq, "non-monotonic seq")
            require(type(frame["op"]) is str and type(frame["payload"]) is dict, "IPC op/payload")
            self.dispatch(frame["op"], frame["payload"]); self.seq += 1

    def close(self) -> None:
        for item in self.held.values():
            if item.fd >= 0: item.close()
        self.repo.close()


def self_test() -> None:
    sample = {"a": 1, "z": [False, None, "한글"]}; require(strict_json_line(canonical_line(sample), "self-test") == sample, "canonical self-test")
    raw = canonical_line({"schema": "memory-only"}); descriptor = sealed_memfd("walksafe-r025-self-test", raw)
    try: require(os.pread(descriptor, len(raw), 0) == raw and fcntl.fcntl(descriptor, fcntl.F_GET_SEALS) == SEAL_MASK, "seal self-test")
    finally: os.close(descriptor)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument("--root", type=Path); parser.add_argument("--self-test", action="store_true"); args = parser.parse_args()
    if args.self_test:
        try: self_test()
        except BaseException: return 1
        print("WALKSAFE_R025_CONTENT_SUPERVISOR_SELF_TEST_PASS"); return 0
    root = args.root if args.root is not None else Path(__file__).resolve().parents[4]
    supervisor: Supervisor | None = None
    try:
        supervisor = Supervisor(root); supervisor.run(); return 0
    except BaseException as exc:
        if supervisor is not None:
            supervisor.state = "TERMINAL"
            try: supervisor.emit("TERMINAL", {"effective": False, "reason": f"{type(exc).__name__}:{exc}"})
            except BaseException: pass
        return 1
    finally:
        if supervisor is not None:
            try: supervisor.close()
            except BaseException: pass


if __name__ == "__main__":
    raise SystemExit(main())

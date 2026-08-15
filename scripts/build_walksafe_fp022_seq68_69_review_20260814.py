#!/usr/bin/env python3
"""Build and validate the actor-separated FP-022 seq68/69 review."""

from __future__ import annotations

import argparse
import copy
import os
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import build_walksafe_fp022_seq66_67_review_20260814 as predecessor


trace = predecessor.trace
require = predecessor.require
ReviewError = predecessor.ReviewError

REVIEW_ROOT = Path(
    "docs/control/execution/workstream-transitions/seq68-69/review-rounds"
)
R001_DIR = REVIEW_ROOT / "R001"
R001_ASSIGNMENT_REL = R001_DIR / "review-assignment.json"
R001_RESULT_REL = R001_DIR / "review-result.json"
R001_INDEPENDENT_REL = R001_DIR / "independent-review.json"
R002_DIR = REVIEW_ROOT / "R002"
R002_ASSIGNMENT_REL = R002_DIR / "review-assignment.json"
R002_RESULT_REL = R002_DIR / "review-result.json"
R002_INDEPENDENT_REL = R002_DIR / "independent-review.json"
R003_DIR = REVIEW_ROOT / "R003"
R003_ASSIGNMENT_REL = R003_DIR / "review-assignment.json"
R003_RESULT_REL = R003_DIR / "review-result.json"
R003_INDEPENDENT_REL = R003_DIR / "independent-review.json"
R004_DIR = REVIEW_ROOT / "R004"
R004_ASSIGNMENT_REL = R004_DIR / "review-assignment.json"
R004_RESULT_REL = R004_DIR / "review-result.json"
R004_INDEPENDENT_REL = R004_DIR / "independent-review.json"
R005_DIR = REVIEW_ROOT / "R005"
R005_ASSIGNMENT_REL = R005_DIR / "review-assignment.json"
R005_RESULT_REL = R005_DIR / "review-result.json"
R005_INDEPENDENT_REL = R005_DIR / "independent-review.json"
R006_DIR = REVIEW_ROOT / "R006"
R006_ASSIGNMENT_REL = R006_DIR / "review-assignment.json"
R006_RESULT_REL = R006_DIR / "review-result.json"
R006_INDEPENDENT_REL = R006_DIR / "independent-review.json"
R007_DIR = REVIEW_ROOT / "R007"
R007_ASSIGNMENT_REL = R007_DIR / "review-assignment.json"
R007_RESULT_REL = R007_DIR / "review-result.json"
R007_INDEPENDENT_REL = R007_DIR / "independent-review.json"
R008_DIR = REVIEW_ROOT / "R008"
R008_ASSIGNMENT_REL = R008_DIR / "review-assignment.json"
R008_RESULT_REL = R008_DIR / "review-result.json"
R008_INDEPENDENT_REL = R008_DIR / "independent-review.json"
R009_DIR = REVIEW_ROOT / "R009"
R009_ASSIGNMENT_REL = R009_DIR / "review-assignment.json"
R009_RESULT_REL = R009_DIR / "review-result.json"
R009_INDEPENDENT_REL = R009_DIR / "independent-review.json"
R010_DIR = REVIEW_ROOT / "R010"
R010_ASSIGNMENT_REL = R010_DIR / "review-assignment.json"
R010_RESULT_REL = R010_DIR / "review-result.json"
R010_INDEPENDENT_REL = R010_DIR / "independent-review.json"
R011_DIR = REVIEW_ROOT / "R011"
R011_ASSIGNMENT_REL = R011_DIR / "review-assignment.json"
R011_RESULT_REL = R011_DIR / "review-result.json"
R011_INDEPENDENT_REL = R011_DIR / "independent-review.json"
R012_DIR = REVIEW_ROOT / "R012"
R012_ASSIGNMENT_REL = R012_DIR / "review-assignment.json"
R012_RESULT_REL = R012_DIR / "review-result.json"
R012_INDEPENDENT_REL = R012_DIR / "independent-review.json"
R013_DIR = REVIEW_ROOT / "R013"
R013_ASSIGNMENT_REL = R013_DIR / "review-assignment.json"
R013_RESULT_REL = R013_DIR / "review-result.json"
R013_INDEPENDENT_REL = R013_DIR / "independent-review.json"
R014_DIR = REVIEW_ROOT / "R014"
R014_ASSIGNMENT_REL = R014_DIR / "review-assignment.json"
R014_RESULT_REL = R014_DIR / "review-result.json"
R014_INDEPENDENT_REL = R014_DIR / "independent-review.json"
R015_DIR = REVIEW_ROOT / "R015"
R015_ASSIGNMENT_REL = R015_DIR / "review-assignment.json"
R015_RESULT_REL = R015_DIR / "review-result.json"
R015_INDEPENDENT_REL = R015_DIR / "independent-review.json"
R016_DIR = REVIEW_ROOT / "R016"
R016_ASSIGNMENT_REL = R016_DIR / "review-assignment.json"
R016_RESULT_REL = R016_DIR / "review-result.json"
R016_INDEPENDENT_REL = R016_DIR / "independent-review.json"
R017_DIR = REVIEW_ROOT / "R017"
R017_ASSIGNMENT_REL = R017_DIR / "review-assignment.json"
R017_RESULT_REL = R017_DIR / "review-result.json"
R017_INDEPENDENT_REL = R017_DIR / "independent-review.json"
R018_DIR = REVIEW_ROOT / "R018"
R018_ASSIGNMENT_REL = R018_DIR / "review-assignment.json"
R018_RESULT_REL = R018_DIR / "review-result.json"
R018_INDEPENDENT_REL = R018_DIR / "independent-review.json"
R019_DIR = REVIEW_ROOT / "R019"
R019_ASSIGNMENT_REL = R019_DIR / "review-assignment.json"
R019_RESULT_REL = R019_DIR / "review-result.json"
R019_INDEPENDENT_REL = R019_DIR / "independent-review.json"
R020_DIR = REVIEW_ROOT / "R020"
R020_ASSIGNMENT_REL = R020_DIR / "review-assignment.json"
R020_RESULT_REL = R020_DIR / "review-result.json"
R020_INDEPENDENT_REL = R020_DIR / "independent-review.json"
R021_DIR = REVIEW_ROOT / "R021"
R021_ASSIGNMENT_REL = R021_DIR / "review-assignment.json"
R021_RESULT_REL = R021_DIR / "review-result.json"
R021_INDEPENDENT_REL = R021_DIR / "independent-review.json"
R022_DIR = REVIEW_ROOT / "R022"
R022_ASSIGNMENT_REL = R022_DIR / "review-assignment.json"
R022_RESULT_REL = R022_DIR / "review-result.json"
R022_INDEPENDENT_REL = R022_DIR / "independent-review.json"
R023_DIR = REVIEW_ROOT / "R023"
R023_ASSIGNMENT_REL = R023_DIR / "review-assignment.json"
R023_RESULT_REL = R023_DIR / "review-result.json"
R023_INDEPENDENT_REL = R023_DIR / "independent-review.json"
R024_DIR = REVIEW_ROOT / "R024"
R024_ASSIGNMENT_REL = R024_DIR / "review-assignment.json"
R024_RESULT_REL = R024_DIR / "review-result.json"
R024_INDEPENDENT_REL = R024_DIR / "independent-review.json"
R025_DIR = REVIEW_ROOT / "R025"
R025_ASSIGNMENT_REL = R025_DIR / "review-assignment.json"
R025_RESULT_REL = R025_DIR / "review-result.json"
R025_INDEPENDENT_REL = R025_DIR / "independent-review.json"
R026_DIR = REVIEW_ROOT / "R026"
R026_ASSIGNMENT_REL = R026_DIR / "review-assignment.json"
R026_RESULT_REL = R026_DIR / "review-result.json"
R026_INDEPENDENT_REL = R026_DIR / "independent-review.json"
R027_DIR = REVIEW_ROOT / "R027"
R027_ASSIGNMENT_REL = R027_DIR / "review-assignment.json"
R027_RESULT_REL = R027_DIR / "review-result.json"
R027_INDEPENDENT_REL = R027_DIR / "independent-review.json"
R028_DIR = REVIEW_ROOT / "R028"
R028_ASSIGNMENT_REL = R028_DIR / "review-assignment.json"
R028_RESULT_REL = R028_DIR / "review-result.json"
R028_INDEPENDENT_REL = R028_DIR / "independent-review.json"
R029_DIR = REVIEW_ROOT / "R029"
R029_ASSIGNMENT_REL = R029_DIR / "review-assignment.json"
R029_RESULT_REL = R029_DIR / "review-result.json"
R029_INDEPENDENT_REL = R029_DIR / "independent-review.json"
R030_DIR = REVIEW_ROOT / "R030"
R030_ASSIGNMENT_REL = R030_DIR / "review-assignment.json"
R030_RESULT_REL = R030_DIR / "review-result.json"
R030_INDEPENDENT_REL = R030_DIR / "independent-review.json"
SUPERSEDED_ASSIGNMENTS = (
    {
        "path": R001_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "99b3c11342a7b0fc4f2ec191f07e1182071ff425bbcdc6b7381be4506ee35fb5"
        ),
        "byte_length": 9_610,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "SEQ69_FULL_CHECKERS_WERE_NOT_REVALIDATED_AT_PUBLICATION",
    },
    {
        "path": R002_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "18fc4e5453d0a53f0a21aa5ecf15f87edd8448fe7bd7620f4b716ae8a19b7364"
        ),
        "byte_length": 10_052,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "SEQ66_67_PREDECESSOR_REVIEW_WAS_REVALIDATED_AGAINST_LIVE_CONTROLS",
    },
    {
        "path": R003_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "06813200f1b0de4cba73a10aac9954a9ae1e61dadf274a2ed3664e7f0490767c"
        ),
        "byte_length": 10_440,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "SEQ66_67_POSTPUBLICATION_TEST_EXPECTED_ONLY_THE_SYNTHETIC_REVIEW_BINDING",
    },
    {
        "path": R004_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "f4c66d94964471aa1a6172c953f727ff9f7298f30fdca7b637ffba366c0b23e8"
        ),
        "byte_length": 10_835,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "SEQ62_AGGREGATE_FIXTURE_DID_NOT_REVERSE_FP022_SEQ66_67_STATE",
    },
    {
        "path": R005_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "ee5dcd2740a344d0ebb05402a53b140daaa4769dd7009ce160ba894a5205951a"
        ),
        "byte_length": 11_218,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "SEQ68_MANAGED_CLOSURE_DID_NOT_IMPORT_THE_START_REVIEW_AUTHORITY",
    },
    {
        "path": R006_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "9ad8b1476fab7572fa2d8d6b83362988ca4257ef4590a0b7165a356621c81271"
        ),
        "byte_length": 11_604,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "SEQ66_67_PREDECESSOR_BINDING_WAS_REPLAYED_AGAINST_LIVE_CONTROLS",
    },
    {
        "path": R007_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "913483230b4860c2b04ff4ecbc4aa05f83dcf85adf41a4957764dc84104f0d3a"
        ),
        "byte_length": 11_990,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "FP022_READY_CURRENT_FOCUS_OMITTED_THE_POLICY_ID_HYPHEN",
    },
    {
        "path": R008_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "929100cdce179b016bfd4739d19cceeddb5f3b49fa5816c713df71ad4d1b0f52"
        ),
        "byte_length": 12_367,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "FP022_GATE_DIRECT_CHECKER_COMMANDS_DID_NOT_SET_PYTHONPATH",
    },
    {
        "path": R009_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "5c3a5c7e79d965711e60a8da5435f9dde1c3dbd0dd4bc771356636018a1dd9a8"
        ),
        "byte_length": 12_747,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "FP022_OFFLINE_ANDROID_GATE_DID_NOT_BIND_A_GRADLE_CACHE",
    },
    {
        "path": R010_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "89768ab21323c22fda19c43e990c8d6cbe9b137f94dd896e6476473f65132b1c"
        ),
        "byte_length": 13_124,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "SEQ69_EVENT_ID_WAS_FORMAT_VALIDATED_BUT_NOT_EXACTLY_SEALED",
    },
    {
        "path": R011_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "680b1e8a8f42511560c2f91784fd060a1bb24653306248a333f699aefca84043"
        ),
        "byte_length": 13_506,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "START_GATE_CONTROL_TESTS_REQUIRED_PHYSICAL_SEQ67_AFTER_SEQ68_PUBLICATION",
    },
    {
        "path": R012_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "733046bd5e51993e5d8a26edbd5f2fb3075c2534c0321d8603e6bb5f3f4a37f7"
        ),
        "byte_length": 13_901,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "CATALOG_FIXED_POINT_TEST_BYPASSED_THE_SEQ68_REVERSE_PROJECTED_SOURCE",
    },
    {
        "path": R013_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "b7f85c026a423cd76f6064f25a7846e54c8b74563a9581309e3cfb44b7419893"
        ),
        "byte_length": 14_292,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "SEQ69_RECAPTURE_DID_NOT_EXCLUDE_THE_VERIFIED_ATOMIC_STAGING_FILE",
    },
    {
        "path": R014_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "f3a7db43bb7858bbf16848ed8ca43d2cbdd04ddbc0307f9db2191a38f5f5e053"
        ),
        "byte_length": 14_679,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "SEQ69_POSTPUBLICATION_REGRESSION_AND_CATALOGS_WERE_STALE",
    },
    {
        "path": R015_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "36769daf922fc4fc1212a4c8ddb6af3459e1cb4697299d1014271ca700734ee4"
        ),
        "byte_length": 15_279,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "CATALOG_ATOMIC_STAGING_FILE_CHANGED_THE_SOURCE_UNIVERSE",
    },
    {
        "path": R016_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "72488b7e03343ba01cadcf9865e75479d272420b3ca397288d913fcf21a25852"
        ),
        "byte_length": 15_657,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "CATALOG_STAGING_SYMLINK_WAS_READ_BEFORE_TYPE_VALIDATION",
    },
    {
        "path": R017_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "603da771a2081ba79db3444d2241a095df4ec73c883ef64eb2b7914d20fd2920"
        ),
        "byte_length": 16_035,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "CATALOG_DISCOVERY_CORRECTLY_OMITTED_THE_DOT_PREFIXED_STAGING_FILE",
    },
    {
        "path": R018_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "737c48158fc73e726efa06f1c16a6fe749983137c06c39753eb304b0044e4d0d"
        ),
        "byte_length": 16_423,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "CATALOG_STAGING_WAS_REOPENED_BY_PATH_DURING_STABLE_READ",
    },
    {
        "path": R019_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "9a000485985e8cd96b715150b8c4aa7738fe8dd54a9cc8f88dd0ea41c8ad9d8a"
        ),
        "byte_length": 16_801,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "SEQ69_CATALOG_TRANSITION_RECAPTURE_REJECTED_EXACT_CANDIDATE_BYTES",
    },
    {
        "path": R020_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "3c74084e2fc31e084b7efa86c8c8b308bbbf3ff0085daff3e3c796a15ed52f0f"
        ),
        "byte_length": 17_189,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "R020_ASSIGNMENT_ACTOR_IDENTITIES_REFERENCED_R019",
    },
    {
        "path": R021_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "a877aa5b491efa9aa6a3626c3388e87482584d7277ac3fe20edf06fdd5821270"
        ),
        "byte_length": 17_560,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "CATALOG_TRANSITION_PHASE_AND_STABLE_LIVE_IDENTITY_WERE_NOT_BOUND",
    },
    {
        "path": R022_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "b48f5ebf238920f54dcc4c0348817a1a8e198017024f270b390aef8e7ebdd141"
        ),
        "byte_length": 17_947,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "FRESH_CLI_COULD_NOT_RESUME_A_VALID_PARTIAL_CATALOG_PHASE",
    },
    {
        "path": R023_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "e059c7eb8f4d9f36aafd3c1f3b3d615f59a0890d0667f265b29e492358d19f0f"
        ),
        "byte_length": 18_326,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "SUPERSEDED_REVIEW_OUTPUT_ABSENCE_WAS_NOT_ENFORCED_AFTER_R018",
    },
    {
        "path": R024_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "17c440b95b2c9e90adb8f529043449fa7104585f69e2986584d6b9ce5676a595"
        ),
        "byte_length": 18_709,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "FRESH_PARTIAL_CATALOG_CLI_WAS_REJECTED_BY_SEQ68_LIVE_SNAPSHOT_CHECK",
    },
    {
        "path": R025_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "1b1430eb1c102a2ba5ca9c6abe88cd9162a4daf3c8db45351ffb180a6c81551f"
        ),
        "byte_length": 19_099,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "GATE_CONTEXT_RELOADED_THE_SEQ68_LIVE_SNAPSHOT_DURING_PARTIAL_CATALOG_RESUME",
    },
    {
        "path": R026_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "c5c2815a72eba3b52e9baf2d412f7f6575d36f7366b78e973c95c165f7f117c2"
        ),
        "byte_length": 19_497,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "SEQ69_VALIDATION_COMPARED_THE_HISTORICAL_SEQ68_REANCHOR_TO_THE_CURRENT_SNAPSHOT",
    },
    {
        "path": R027_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "d94df20da66fba9bfbbac979add0bfb3ebfd3e9957032590c7ff7f60e31ca30c"
        ),
        "byte_length": 19_899,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "GENERIC_CANONICAL_SUFFIX_WAS_MISCLASSIFIED_AS_FP022_GOAL_STARTED",
    },
    {
        "path": R028_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "c4ff27da7ed14d9f304219d7c35a7017ac2cc26e82a1049bbc268084f15e26c7"
        ),
        "byte_length": 20_286,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "SEQ69_TRANSACTION_RECHECK_REQUIRED_THE_HISTORICAL_CATALOG_SNAPSHOT",
    },
    {
        "path": R029_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "b4cd104575bff3842507bfc85efac01e8ccbbf42cbc688fba769e1e0b23135d9"
        ),
        "byte_length": 20_675,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "CHECKPOINT_STAGING_CHANGED_THE_CANDIDATE_CATALOG_SOURCE_UNIVERSE",
    },
    {
        "path": R030_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "b1bcc31570c1a852e126f4e4b83686af10a6d5dbbcf1322200d460157aca6f8d"
        ),
        "byte_length": 21_062,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "POST_EXCHANGE_RECAPTURE_NORMALIZED_CATALOGS_TO_THE_SEQ68_PREDECESSOR",
    },
)
RESULT_DIR = REVIEW_ROOT / "R031"
ASSIGNMENT_REL = RESULT_DIR / "review-assignment.json"
RESULT_REL = RESULT_DIR / "review-result.json"
INDEPENDENT_REL = RESULT_DIR / "independent-review.json"
ROUND_ID = "WS-FP022-SEQ68-69-REVIEW-20260814-R031"

SOURCE_CHECKPOINT = {
    "path": "docs/control/walksafe-project-continuation-checkpoint.json",
    "sequence": 67,
    "sha256": "87990596835e0ed3dad48c4a974fb2521a28d9dfdfe076a1ec10596306179354",
    "byte_length": 1_994_168,
    "tail_event_sha256": (
        "37207b4393dd5820de6b71c7e167885f8592875f8b54d9d75d650aef230a87a2"
    ),
}
PREDECESSOR_REVIEW_PINS = {
    predecessor.ASSIGNMENT_REL: (
        "106f22a96ee9cf976b74e2b33cf92b9f14fd9009e38e5463e8fc462bd3b0e523",
        7_889,
    ),
    predecessor.RESULT_REL: (
        "734801f30e1d977806e5ec19751117b4d3c13a2467f8b3d5caea21a42e40246b",
        7_951,
    ),
    predecessor.INDEPENDENT_REL: (
        "0a882fb03cb9918cdb153d9df5ce1c148bfbb9fd4442325d82226b9bf4b4f101",
        8_228,
    ),
}

CONTRACT_R001_REL = predecessor.fp022.CONTRACT_PATH
CONTRACT_R002_REL = Path(
    "docs/control/execution/goal-contracts/WS-GOAL-EPIC-04-FP-022-R001/"
    "initial-start-gate-contract-r002.json"
)
CONTRACT_R001_EVIDENCE = {
    "document_id": predecessor.fp022.CONTRACT_DOCUMENT_ID,
    "contract_id": predecessor.fp022.CONTRACT_ID,
    "contract_version": predecessor.fp022.CONTRACT_VERSION,
    "path": CONTRACT_R001_REL.as_posix(),
    "file_sha256": predecessor.fp022.CONTRACT_FILE_SHA256,
    "byte_length": predecessor.fp022.CONTRACT_BYTE_COUNT,
    "canonical_contract_sha256": predecessor.fp022.CONTRACT_CANONICAL_SHA256,
}
CONTRACT_R002_EVIDENCE = {
    "document_id": "WS-FP022-INITIAL-START-GATE-CONTRACT-20260814-002",
    "contract_id": "WS-FP022-INTERNAL-START-GATE-R002",
    "contract_version": "2026-08-14.1",
    "path": CONTRACT_R002_REL.as_posix(),
    "file_sha256": "8e7f55dffcc0725fcb831118ed553b79ed09a41c0d323d00da343fa26ab01b42",
    "byte_length": 4_561,
    "canonical_contract_sha256": (
        "bc382fc5095cfa0eef246447f961d268ff3953274ba1df84360a1a5bb4663e7c"
    ),
    "successor_reason_code": (
        "SEQ67_READY_TRUST_ANCHOR_AND_CURRENT_CONTROL_COHORT_REQUIRED"
    ),
    "ordered_check_ids": list(predecessor.fp022.CONTRACT_CHECK_IDS),
}

REANCHOR_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-FP022-20260814-001"
)
STARTED_EVENT_ID = "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP022-20260814-001"
PROJECTED_TRANSITION = {
    "control_reanchor": {
        "sequence": 68,
        "event_id": REANCHOR_EVENT_ID,
        "event_type": "GOAL_START_CONTROL_REANCHORED",
        "from_status": "READY",
        "to_status": "READY",
        "status_changes": {},
        "occurred_at": "2026-08-14T01:54:30+09:00",
        "contract_id": CONTRACT_R002_EVIDENCE["contract_id"],
    },
    "goal_started": {
        "sequence": 69,
        "event_id": STARTED_EVENT_ID,
        "event_type": "GOAL_STARTED",
        "from_status": "READY",
        "to_status": "IN_PROGRESS",
        "start_gate_required_status": "PASS",
        "catalog_publication_required": True,
        "catalog_transition_paths": [
            "docs/catalogs/repository-paths.json",
            "docs/catalogs/scripts.json",
            "docs/catalogs/tests.json",
        ],
    },
    "final_focus_goal_id": predecessor.fp022.GOAL_ID,
    "final_status": "IN_PROGRESS",
}

SCRIPT_REL = Path("scripts/build_walksafe_fp022_seq68_69_review_20260814.py")
TEST_REL = Path("tests/test_build_walksafe_fp022_seq68_69_review_20260814.py")
CONTROL_PATHS = (
    Path("scripts/check_walksafe_project_continuation_v2_4.py"),
    Path("tests/test_walksafe_project_continuation_v2_4.py"),
    Path("scripts/check_walksafe_goal_graph_v2_4.py"),
    Path("tests/test_walksafe_goal_graph_v2_4.py"),
    Path("scripts/generate_repository_catalogs.py"),
    Path("tests/test_repository_catalogs.py"),
    Path("scripts/run_walksafe_test_layers_current.sh"),
    Path("tests/test_apply_walksafe_workstream_aggregate_seq63_65_20260813.py"),
    predecessor.fp022.SCRIPT_REL,
    predecessor.fp022.TEST_REL,
    predecessor.SCRIPT_REL,
    predecessor.TEST_REL,
    Path("scripts/run_walksafe_fp022_goal_start_gate_20260813.py"),
    Path("tests/test_walksafe_fp022_goal_start_gate_20260813.py"),
    Path("scripts/apply_walksafe_fp022_goal_start_control_reanchor_seq68_20260814.py"),
    Path("tests/test_apply_walksafe_fp022_goal_start_control_reanchor_seq68_20260814.py"),
    Path("scripts/apply_walksafe_fp022_goal_started_seq69_20260814.py"),
    Path("tests/test_apply_walksafe_fp022_goal_started_seq69_20260814.py"),
    SCRIPT_REL,
    TEST_REL,
)
FINDING_IDS: tuple[str, ...] = ()
BOUNDARY = {
    "product_implementation_credit_added": 0,
    "artifact_completion_credit_added": 0,
    "formal_test_credit_added": 0,
    "actual_device_credit_added": 0,
    "external_review_credit_added": 0,
    "deployment_credit_added": 0,
    "approval_credit_added": 0,
    "release_credit_added": 0,
    "release_status": "NOT_ELIGIBLE",
}


@dataclass(frozen=True)
class ReviewContext:
    root: Path
    predecessor_review_bindings: tuple[dict[str, Any], ...]
    superseded_assignment_bindings: tuple[dict[str, Any], ...]
    contract_r001_evidence: dict[str, Any]
    contract_r002_evidence: dict[str, Any]
    control_code_cohort: tuple[dict[str, Any], ...]
    control_code_cohort_sha256: str


def _raw(root: Path, relative: Path) -> bytes:
    return predecessor._raw(root, relative)


def _document(root: Path, relative: Path) -> tuple[dict[str, Any], bytes]:
    return predecessor._document(root, relative)


def _binding(relative: Path, raw: bytes) -> dict[str, Any]:
    return {
        "path": relative.as_posix(),
        "sha256": trace.bytes_sha256(raw),
        "byte_length": len(raw),
    }


def _identity(value: Any, role: str) -> dict[str, str]:
    require(
        type(value) is dict
        and set(value) == {"role", "agent_instance_id", "canonical_task"}
        and value.get("role") == role
        and all(type(value.get(key)) is str and value.get(key) for key in value),
        f"{role} identity differs",
    )
    return dict(value)


def prepare_frozen_predecessor_review(
    root: Path = ROOT,
) -> tuple[dict[str, Any], ...]:
    """Replay seq66/67 R002 from the cohort frozen in its assignment."""

    root = root.resolve(strict=True)
    documents: dict[Path, dict[str, Any]] = {}
    raw_by_path: dict[Path, bytes] = {}
    for relative, (digest, byte_length) in PREDECESSOR_REVIEW_PINS.items():
        document, raw = _document(root, relative)
        require(
            len(raw) == byte_length and trace.bytes_sha256(raw) == digest,
            f"seq66/67 R002 frozen binding differs: {relative}",
        )
        documents[relative] = document
        raw_by_path[relative] = raw

    assignment = documents[predecessor.ASSIGNMENT_REL]
    result = documents[predecessor.RESULT_REL]
    scope = assignment.get("review_scope")
    require(type(scope) is dict, "seq66/67 R002 frozen scope is missing")
    frozen_context = predecessor.ReviewContext(
        root=root,
        predecessor_review_bindings=tuple(scope["predecessor_review_bindings"]),
        superseded_assignment_bindings=tuple(
            scope["superseded_review_assignments"]
        ),
        goal_evidence=copy.deepcopy(scope["goal_evidence"]),
        contract_evidence=copy.deepcopy(scope["start_gate_contract_evidence"]),
        control_code_cohort=tuple(scope["reviewed_control_code_cohort"]),
        control_code_cohort_sha256=scope["reviewed_control_code_cohort_sha256"],
    )
    predecessor.validate_review_result(
        result,
        raw_by_path[predecessor.RESULT_REL],
        assignment,
        raw_by_path[predecessor.ASSIGNMENT_REL],
        frozen_context,
    )
    require(
        predecessor.build_independent_review(
            frozen_context,
            assignment,
            raw_by_path[predecessor.ASSIGNMENT_REL],
            result,
            raw_by_path[predecessor.RESULT_REL],
        ).encode("utf-8")
        == raw_by_path[predecessor.INDEPENDENT_REL],
        "seq66/67 R002 frozen independent review differs",
    )
    return tuple(
        _binding(relative, raw_by_path[relative])
        for relative in PREDECESSOR_REVIEW_PINS
    )


def _require_exact_source(root: Path) -> None:
    relative = Path(SOURCE_CHECKPOINT["path"])
    raw = _raw(root, relative)
    require(
        len(raw) == SOURCE_CHECKPOINT["byte_length"]
        and trace.bytes_sha256(raw) == SOURCE_CHECKPOINT["sha256"],
        "FP-022 start review source checkpoint differs from exact seq67",
    )
    checkpoint = trace.strict_json_bytes(raw, relative.as_posix())
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if type(state) is dict else None
    require(
        type(history) is list
        and len(history) == SOURCE_CHECKPOINT["sequence"]
        and type(history[-1]) is dict
        and history[-1].get("sequence") == SOURCE_CHECKPOINT["sequence"]
        and history[-1].get("event_sha256")
        == SOURCE_CHECKPOINT["tail_event_sha256"]
        and state.get("transition_history_anchor_sha256")
        == SOURCE_CHECKPOINT["tail_event_sha256"],
        "FP-022 start review seq67 lineage differs",
    )


def _require_contracts(root: Path) -> tuple[dict[str, Any], dict[str, Any]]:
    evidence_by_relative = {
        CONTRACT_R001_REL: CONTRACT_R001_EVIDENCE,
        CONTRACT_R002_REL: CONTRACT_R002_EVIDENCE,
    }
    documents: dict[Path, dict[str, Any]] = {}
    for relative, evidence in evidence_by_relative.items():
        document, raw = _document(root, relative)
        require(
            len(raw) == evidence["byte_length"]
            and trace.bytes_sha256(raw) == evidence["file_sha256"]
            and trace.object_sha256(document)
            == evidence["canonical_contract_sha256"],
            f"FP-022 start-gate contract evidence differs: {relative}",
        )
        require(
            document.get("document_id") == evidence["document_id"]
            and document.get("contract_id") == evidence["contract_id"]
            and document.get("contract_version") == evidence["contract_version"]
            and document.get("target_goal_id") == predecessor.fp022.GOAL_ID
            and document.get("target_goal_content_sha256")
            == predecessor.fp022.GOAL_SHA256,
            f"FP-022 start-gate contract identity differs: {relative}",
        )
        documents[relative] = document

    r002 = documents[CONTRACT_R002_REL]
    require(
        r002.get("successor_reason_code")
        == CONTRACT_R002_EVIDENCE["successor_reason_code"]
        and [row.get("check_id") for row in r002.get("ordered_checks", [])]
        == CONTRACT_R002_EVIDENCE["ordered_check_ids"]
        and r002.get("supersedes")
        == {
            "document_id": CONTRACT_R001_EVIDENCE["document_id"],
            "contract_id": CONTRACT_R001_EVIDENCE["contract_id"],
            "contract_version": CONTRACT_R001_EVIDENCE["contract_version"],
            "path": CONTRACT_R001_EVIDENCE["path"],
            "file_sha256": CONTRACT_R001_EVIDENCE["file_sha256"],
            "canonical_sha256": CONTRACT_R001_EVIDENCE[
                "canonical_contract_sha256"
            ],
            "source_ready_event_sequence": 67,
            "source_ready_event_id": predecessor.fp022.READY_EVENT_ID,
            "source_ready_event_sha256": SOURCE_CHECKPOINT[
                "tail_event_sha256"
            ],
        },
        "FP-022 R002 contract successor binding differs",
    )
    claim = r002.get("claim_boundary")
    require(
        type(claim) is dict
        and claim.get("seq67_ready_event_modified") is False
        and claim.get("binding_replacement_effective_without_reanchor_event")
        is False
        and claim.get("append_only_reanchor_event_required") is True
        and claim.get("goal_started") is False
        and claim.get("start_gate_status") == "NOT_RUN"
        and claim.get("product_implementation_credit_delta") == 0
        and claim.get("artifact_completion_credit_delta") == 0
        and claim.get("test_credit_delta") == 0
        and claim.get("formal_test_credit_delta") == 0
        and claim.get("approval_credit_delta") == 0
        and claim.get("actual_event_credit_delta") == 0
        and claim.get("release_status") == "NOT_ELIGIBLE",
        "FP-022 R002 contract claim boundary differs",
    )
    return copy.deepcopy(CONTRACT_R001_EVIDENCE), copy.deepcopy(
        CONTRACT_R002_EVIDENCE
    )


def _require_transition_constants() -> None:
    from scripts import apply_walksafe_fp022_goal_start_control_reanchor_seq68_20260814 as reanchor
    from scripts import apply_walksafe_fp022_goal_started_seq69_20260814 as started

    require(
        reanchor.CONTROL_REANCHOR_SEQUENCE == 68
        and reanchor.CONTROL_REANCHOR_EVENT_ID == REANCHOR_EVENT_ID
        and reanchor.OCCURRED_AT
        == PROJECTED_TRANSITION["control_reanchor"]["occurred_at"]
        and reanchor.SOURCE_SEQUENCE == SOURCE_CHECKPOINT["sequence"]
        and reanchor.SOURCE_SHA256 == SOURCE_CHECKPOINT["sha256"]
        and reanchor.SOURCE_BYTE_COUNT == SOURCE_CHECKPOINT["byte_length"]
        and reanchor.SOURCE_TAIL_SHA256 == SOURCE_CHECKPOINT["tail_event_sha256"]
        and reanchor.R002_CONTRACT_ID == CONTRACT_R002_EVIDENCE["contract_id"]
        and reanchor.R002_FILE_SHA256
        == CONTRACT_R002_EVIDENCE["file_sha256"]
        and reanchor.R002_BYTE_LENGTH == CONTRACT_R002_EVIDENCE["byte_length"]
        and reanchor.R002_CANONICAL_SHA256
        == CONTRACT_R002_EVIDENCE["canonical_contract_sha256"],
        "FP-022 seq68 sealed transition constants differ",
    )
    require(
        reanchor.CLAIM_BOUNDARY["implementation_start_authorized"] is False
        and reanchor.CLAIM_BOUNDARY["goal_status_change_count"] == 0
        and all(
            value == 0
            for key, value in reanchor.CLAIM_BOUNDARY.items()
            if key.endswith("_credit_delta")
        )
        and reanchor.CLAIM_BOUNDARY["release_status"] == "NOT_ELIGIBLE",
        "FP-022 seq68 claim boundary is not zero-credit",
    )
    require(
        started.EVENT_SEQUENCE == 69
        and started.EVENT_ID == STARTED_EVENT_ID
        and started.gate.STARTED_EVENT_ID == STARTED_EVENT_ID
        and [path.as_posix() for path in started.CATALOG_PATHS]
        == PROJECTED_TRANSITION["goal_started"][
            "catalog_transition_paths"
        ]
        and "GOAL_STARTED seq69" in started.STARTED_SCOPE
        and "no product completion" in started.STARTED_SCOPE,
        "FP-022 seq69 sealed transition constants differ",
    )


def _require_superseded_outputs_absent(
    root: Path,
    assignments: Sequence[Mapping[str, Any]],
) -> None:
    for row in assignments:
        relative = Path(row["path"])
        for output_name in ("review-result.json", "independent-review.json"):
            output = root / relative.parent / output_name
            require(
                not os.path.lexists(output),
                f"superseded FP-022 seq68/69 review produced an output: {output}",
            )


def prepare_review_context(
    root: Path = ROOT, *, require_exact_source: bool = False
) -> ReviewContext:
    root = root.resolve(strict=True)
    predecessor_bindings = prepare_frozen_predecessor_review(root)
    superseded = tuple(copy.deepcopy(SUPERSEDED_ASSIGNMENTS))
    for row in superseded:
        relative = Path(row["path"])
        require(
            _binding(relative, _raw(root, relative))
            == {key: row[key] for key in ("path", "sha256", "byte_length")},
            f"superseded FP-022 seq68/69 assignment differs: {relative}",
        )
    _require_superseded_outputs_absent(root, superseded)
    r001, r002 = _require_contracts(root)
    _require_transition_constants()
    controls = tuple(_binding(path, _raw(root, path)) for path in CONTROL_PATHS)
    require(
        len(controls) == len(CONTROL_PATHS)
        and len({row["path"] for row in controls}) == len(CONTROL_PATHS),
        "FP-022 seq68/69 review control cohort is not exact",
    )
    if require_exact_source:
        _require_exact_source(root)
    return ReviewContext(
        root=root,
        predecessor_review_bindings=predecessor_bindings,
        superseded_assignment_bindings=superseded,
        contract_r001_evidence=r001,
        contract_r002_evidence=r002,
        control_code_cohort=controls,
        control_code_cohort_sha256=trace.object_sha256(list(controls)),
    )


def _scope(context: ReviewContext) -> dict[str, Any]:
    return {
        "source_checkpoint": copy.deepcopy(SOURCE_CHECKPOINT),
        "predecessor_review_bindings": copy.deepcopy(
            list(context.predecessor_review_bindings)
        ),
        "superseded_review_assignments": copy.deepcopy(
            list(context.superseded_assignment_bindings)
        ),
        "superseded_start_gate_contract_evidence": copy.deepcopy(
            context.contract_r001_evidence
        ),
        "successor_start_gate_contract_evidence": copy.deepcopy(
            context.contract_r002_evidence
        ),
        "projected_transition": copy.deepcopy(PROJECTED_TRANSITION),
        "reviewed_control_code_cohort": copy.deepcopy(
            list(context.control_code_cohort)
        ),
        "reviewed_control_code_cohort_sha256": context.control_code_cohort_sha256,
        "required_finding_ids": list(FINDING_IDS),
        "acceptance": {
            "source_checkpoint_is_exact_seq67": True,
            "seq66_67_r002_predecessor_review_is_exact": True,
            "r002_contract_exactly_supersedes_r001": True,
            "seq68_is_add_only_ready_to_ready_control_reanchor": True,
            "seq68_status_changes_are_empty": True,
            "private_seven_check_gate_runs_only_after_seq68": True,
            "seq69_started_requires_the_exact_pass_receipt": True,
            "seq69_changes_only_fp022_ready_to_in_progress": True,
            "projected_seq68_and_seq69_pass_continuation_and_goal_graph": True,
            "atomic_checkpoint_publication_is_fail_closed": True,
            "no_product_artifact_formal_device_external_deployment_"
            "approval_or_release_credit_is_added": True,
        },
    }


def validate_assignment(
    assignment: Mapping[str, Any], raw: bytes, context: ReviewContext
) -> None:
    require(raw == trace.json_text(assignment).encode(), "assignment is noncanonical")
    require(
        set(assignment)
        == {
            "schema_version",
            "evidence_type",
            "goal_id",
            "round_id",
            "assigned_at",
            "assigner",
            "executor",
            "reviewer",
            "review_scope",
            "review_boundary",
        }
        and assignment.get("schema_version") == "1.0"
        and assignment.get("evidence_type")
        == "FP022_SEQ68_69_REVIEW_ASSIGNMENT"
        and assignment.get("goal_id") == predecessor.fp022.GOAL_ID
        and assignment.get("round_id") == ROUND_ID,
        "assignment identity differs",
    )
    trace._parse_time(assignment.get("assigned_at"), "assigned_at")
    assigner = _identity(assignment.get("assigner"), "INTERNAL_REVIEW_ASSIGNER")
    executor = _identity(
        assignment.get("executor"), "INTERNAL_IMPLEMENTATION_EXECUTOR"
    )
    reviewer = _identity(
        assignment.get("reviewer"), "SEPARATE_INTERNAL_REVIEWER"
    )
    require(
        reviewer["agent_instance_id"]
        not in {assigner["agent_instance_id"], executor["agent_instance_id"]}
        and reviewer["canonical_task"]
        not in {assigner["canonical_task"], executor["canonical_task"]},
        "reviewer is not separate",
    )
    require(assignment.get("review_scope") == _scope(context), "assignment scope differs")
    require(assignment.get("review_boundary") == BOUNDARY, "assignment boundary differs")


def validate_review_result(
    result: Mapping[str, Any],
    raw: bytes,
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
    context: ReviewContext,
) -> None:
    validate_assignment(assignment, assignment_raw, context)
    require(raw == trace.json_text(result).encode(), "review result is noncanonical")
    require(
        set(result)
        == {
            "schema_version",
            "evidence_type",
            "goal_id",
            "round_id",
            "reviewed_at",
            "reviewer",
            "assignment_binding",
            "review_scope",
            "decision",
            "findings",
            "finding_dispositions",
            "review_boundary",
        }
        and result.get("schema_version") == "1.0"
        and result.get("evidence_type")
        == "FP022_SEQ68_69_REVIEWER_AUTHORED_RESULT"
        and result.get("goal_id") == predecessor.fp022.GOAL_ID
        and result.get("round_id") == ROUND_ID,
        "review result identity differs",
    )
    require(result.get("reviewer") == assignment.get("reviewer"), "reviewer binding differs")
    require(
        result.get("assignment_binding") == _binding(ASSIGNMENT_REL, assignment_raw),
        "assignment binding differs",
    )
    require(result.get("review_scope") == _scope(context), "review result scope differs")
    require(
        result.get("decision") == "APPROVED"
        and result.get("findings")
        == {"blocking": [], "major_open": [], "minor_open": []},
        "review is not finding-free approved",
    )
    require(
        result.get("finding_dispositions") == list(FINDING_IDS),
        "finding disposition inventory differs",
    )
    require(result.get("review_boundary") == BOUNDARY, "review result boundary differs")
    require(
        trace._parse_time(result.get("reviewed_at"), "reviewed_at")
        >= trace._parse_time(assignment.get("assigned_at"), "assigned_at"),
        "review predates assignment",
    )


def build_independent_review(
    context: ReviewContext,
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
    result: Mapping[str, Any],
    result_raw: bytes,
) -> str:
    validate_review_result(result, result_raw, assignment, assignment_raw, context)
    return trace.json_text(
        {
            "schema_version": "1.0",
            "evidence_type": "FP022_SEQ68_69_INDEPENDENT_INTERNAL_REVIEW",
            "goal_id": predecessor.fp022.GOAL_ID,
            "round_id": ROUND_ID,
            "status": "PASS",
            "decision": result["decision"],
            "reviewed_at": result["reviewed_at"],
            "reviewer": result["reviewer"],
            "assignment_provenance": _binding(ASSIGNMENT_REL, assignment_raw),
            "review_result_provenance": _binding(RESULT_REL, result_raw),
            "review_scope": result["review_scope"],
            "findings": result["findings"],
            "finding_dispositions": result["finding_dispositions"],
            "review_boundary": result["review_boundary"],
        }
    )


def validate_post_review(root: Path = ROOT) -> ReviewContext:
    context = prepare_review_context(root)
    assignment, assignment_raw = _document(root, ASSIGNMENT_REL)
    result, result_raw = _document(root, RESULT_REL)
    validate_review_result(result, result_raw, assignment, assignment_raw, context)
    require(
        _raw(root, INDEPENDENT_REL)
        == build_independent_review(
            context, assignment, assignment_raw, result, result_raw
        ).encode(),
        "independent review differs",
    )
    return context


def transition_review_binding(root: Path = ROOT) -> dict[str, dict[str, Any]]:
    validate_post_review(root)
    return {
        "assignment": _binding(ASSIGNMENT_REL, _raw(root, ASSIGNMENT_REL)),
        "review_result": _binding(RESULT_REL, _raw(root, RESULT_REL)),
        "independent_review": _binding(INDEPENDENT_REL, _raw(root, INDEPENDENT_REL)),
    }


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _write_add_only(root: Path, relative: Path, text: str) -> None:
    target = root / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("x", encoding="utf-8", newline="") as handle:
        handle.write(text)


def write_assignment(root: Path) -> None:
    context = prepare_review_context(root, require_exact_source=True)
    assignment = {
        "schema_version": "1.0",
        "evidence_type": "FP022_SEQ68_69_REVIEW_ASSIGNMENT",
        "goal_id": predecessor.fp022.GOAL_ID,
        "round_id": ROUND_ID,
        "assigned_at": _now(),
        "assigner": {
            "role": "INTERNAL_REVIEW_ASSIGNER",
            "agent_instance_id": "codex-root-fp022-seq68-69-r031-assigner-20260814",
            "canonical_task": "/root",
        },
        "executor": {
            "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
            "agent_instance_id": "codex-root-fp022-seq68-69-r031-executor-20260814",
            "canonical_task": "/root",
        },
        "reviewer": {
            "role": "SEPARATE_INTERNAL_REVIEWER",
            "agent_instance_id": "codex-fp022-seq68-69-r031-reviewer-20260814",
            "canonical_task": "/root/fp022_seq68_69_r031_reviewer",
        },
        "review_scope": _scope(context),
        "review_boundary": copy.deepcopy(BOUNDARY),
    }
    text = trace.json_text(assignment)
    validate_assignment(assignment, text.encode(), context)
    _write_add_only(root, ASSIGNMENT_REL, text)


def write_independent(root: Path) -> None:
    context = prepare_review_context(root)
    assignment, assignment_raw = _document(root, ASSIGNMENT_REL)
    result, result_raw = _document(root, RESULT_REL)
    _write_add_only(
        root,
        INDEPENDENT_REL,
        build_independent_review(
            context, assignment, assignment_raw, result, result_raw
        ),
    )


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("--root", type=Path, default=ROOT)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write-assignment", action="store_true")
    mode.add_argument("--check-assignment", action="store_true")
    mode.add_argument("--check-review-result", action="store_true")
    mode.add_argument("--write-independent", action="store_true")
    mode.add_argument("--check-post-review", action="store_true")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    root = args.root.resolve(strict=True)
    try:
        if args.write_assignment:
            write_assignment(root)
        elif args.check_assignment:
            context = prepare_review_context(root)
            assignment, raw = _document(root, ASSIGNMENT_REL)
            validate_assignment(assignment, raw, context)
        elif args.check_review_result:
            context = prepare_review_context(root)
            assignment, assignment_raw = _document(root, ASSIGNMENT_REL)
            result, result_raw = _document(root, RESULT_REL)
            validate_review_result(
                result, result_raw, assignment, assignment_raw, context
            )
        elif args.write_independent:
            write_independent(root)
        else:
            validate_post_review(root)
    except (OSError, ValueError, TypeError, ReviewError, trace.BuildError) as exc:
        print(f"FP-022 seq68/69 review: FAIL: {exc}")
        return 1
    print("FP-022 seq68/69 review: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

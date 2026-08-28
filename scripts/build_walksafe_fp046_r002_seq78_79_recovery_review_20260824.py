#!/usr/bin/env python3
"""Validate the add-only FP-046 R002 seq84/85 recovery review authority.

This module deliberately treats the approved seq77/78 R006 review as an
opaque, byte-exact predecessor.  The session artifacts named inside R006 are
historical evidence; they are not reread as mutable live authority inputs.
"""

from __future__ import annotations

import argparse
import copy
from dataclasses import dataclass
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import stat
import sys
from typing import Any, Callable, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import build_walksafe_fp046_r002_seq77_78_review_20260823 as predecessor


class ReviewError(RuntimeError):
    """The recovery review authority is absent, malformed, or has drifted."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ReviewError(message)


def bytes_sha256(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def json_text(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def strict_json_equal(actual: Any, expected: Any) -> bool:
    if type(actual) is not type(expected):
        return False
    if type(actual) is dict:
        return set(actual) == set(expected) and all(
            strict_json_equal(actual[key], expected[key]) for key in actual
        )
    if type(actual) is list:
        return len(actual) == len(expected) and all(
            strict_json_equal(left, right)
            for left, right in zip(actual, expected, strict=True)
        )
    return actual == expected


MAXIMUM_EVIDENCE_BYTES = 8 * 1024 * 1024
REVIEW_DIRECTORY_MODES = frozenset({0o700, 0o755, 0o775})
REVIEW_FILE_MODES = frozenset({0o600, 0o644, 0o664, 0o755, 0o775})

GOAL_ID = "WS-GOAL-EPIC-03-FP-046-R002"
GOAL_SHA256 = "4627c19b421f626323778fcdfd01cc2edbc36644c48c7d65c2b4847e698ac429"
CHECKPOINT_REL = Path("docs/control/walksafe-project-continuation-checkpoint.json")
CATALOG_PATHS = (
    Path("docs/catalogs/repository-paths.json"),
    Path("docs/catalogs/scripts.json"),
    Path("docs/catalogs/tests.json"),
)
SOURCE_CHECKPOINT_SHA256 = "c88b6017f6b8a61d7dce6cb99b9af49b750d3c9e10791270ec0382e525c94747"
SOURCE_CHECKPOINT_BYTE_LENGTH = 2_249_359
SOURCE_SEQUENCE = 77
SOURCE_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-"
    "FP046-R002-20260823-001"
)
SOURCE_EVENT_SHA256 = "0d80244b6d1c1f7a91bcdce78f7861086f16b39c66874e21e5cb6ba8b2c88fd1"
REVIEW_SOURCE_SEQUENCE = 83
REVIEW_SOURCE_CHECKPOINT_SHA256 = (
    "234b190dbed90d2f7f3cb1a1a5a616b7b25f79577d3b88bad5cfc9571b1f9bb1"
)
REVIEW_SOURCE_CHECKPOINT_BYTE_LENGTH = 2_425_953
REVIEW_SOURCE_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-"
    "FP046-R002-CORRECTION-20260824-006"
)
REVIEW_SOURCE_EVENT_SHA256 = (
    "488f8b87f4d6a3ef605e2360b10d1df7b932096f3baf775e68189fb3683f1f0b"
)

AUTHORIZATION_REL = Path(
    "docs/control/execution/workstream-transitions/seq84-85/authorization.json"
)
AUTHORIZATION_SHA256 = "c919cef3dae010f4b3201375fb8a3b542cdece5a3bc498102f3e0f5d5a8767b1"
AUTHORIZATION_BYTE_LENGTH = 4_417
CONTRACT_REL = Path(
    "docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-FP-046-R002/"
    "initial-start-gate-contract-r002.json"
)
CONTRACT_SHA256 = "62311945a57cd96bbaaf10e66ce6f4114835324f74f10ca00443b49482d96a6e"
CONTRACT_BYTE_LENGTH = 5_089

PREDECESSOR_REVIEW_DIR = Path(
    "docs/control/execution/workstream-transitions/seq77-78/review-rounds/R006"
)
R006_REVIEW_PATHS = (
    PREDECESSOR_REVIEW_DIR / "review-assignment.json",
    PREDECESSOR_REVIEW_DIR / "review-result.json",
    PREDECESSOR_REVIEW_DIR / "independent-review.json",
)
PRESERVED_REVIEW_PINS = {
    R006_REVIEW_PATHS[0]: (
        "9ea8e8754b4a6c8a8b74899b27be1b0051ef8c7f81504092fc8745e4d670994c",
        31_097,
    ),
    R006_REVIEW_PATHS[1]: (
        "624bd4a9955939317e8d26c3424bf48b210b339e34d0f48ad09634d8cfe8dd18",
        31_109,
    ),
    R006_REVIEW_PATHS[2]: (
        "f4758c8324c7acc55432d59ae6b2884f8b80c9a439889cdd00f60fe87d7e09e3",
        31_392,
    ),
}
PREDECESSOR_CONTROL_COHORT_SHA256 = (
    "8a5548ae52f55bfb39e2ea1837553e62f08974a2722cb0ad4c911baa36fbb367"
)

# These paths remain described by the immutable R006 documents only.  Keeping
# the live tuple empty prevents later daylog/plan edits from invalidating the
# recovery continuation.
PRESERVED_SESSION_ARTIFACT_PATHS = predecessor.SESSION_ARTIFACT_PATHS
SESSION_ARTIFACT_PATHS: tuple[Path, ...] = ()

REVIEW_ROOT = Path(
    "docs/control/execution/workstream-transitions/seq78-79/review-rounds"
)
REJECTED_R001_DIR = REVIEW_ROOT / "R001"
REJECTED_R001_ASSIGNMENT_REL = REJECTED_R001_DIR / "review-assignment.json"
REJECTED_R001_RESULT_REL = REJECTED_R001_DIR / "review-result.json"
REJECTED_R001_INDEPENDENT_REL = REJECTED_R001_DIR / "independent-review.json"
REJECTED_R001_ASSIGNMENT_SHA256 = (
    "b5ace9b6525b28e185cce75eeec28774c1cfb96ee792079f26babf3af9aa23dd"
)
REJECTED_R001_ASSIGNMENT_BYTE_LENGTH = 26_445
R001_SUPERSESSION_REASON_CODE = (
    "R001_REVIEW_AUTHORITY_AND_PUBLICATION_CAS_INSUFFICIENT"
)
APPROVED_R002_DIR = REVIEW_ROOT / "R002"
APPROVED_R002_REVIEW_PATHS = (
    APPROVED_R002_DIR / "review-assignment.json",
    APPROVED_R002_DIR / "review-result.json",
    APPROVED_R002_DIR / "independent-review.json",
)
APPROVED_R002_REVIEW_PINS = {
    APPROVED_R002_REVIEW_PATHS[0]: (
        "624f081fb01cb8a95c21b787a8b6ae65f3875676b7700409e83adaca7c3ea2ed",
        27_460,
    ),
    APPROVED_R002_REVIEW_PATHS[1]: (
        "a9b9f7e7d5f2ddea813da6236b6400308a4aad69c7375198e7049c540a253720",
        27_252,
    ),
    APPROVED_R002_REVIEW_PATHS[2]: (
        "c3b1b0013b271f212ada18cb4c4850631cc29ea38bd4b1b93629507210dbe78f",
        27_535,
    ),
}
R002_SUPERSESSION_REASON_CODE = "R002_LIVE_GIT_PATH_BASELINE_INCORRECT"
APPROVED_R003_DIR = REVIEW_ROOT / "R003"
APPROVED_R003_REVIEW_PATHS = (
    APPROVED_R003_DIR / "review-assignment.json",
    APPROVED_R003_DIR / "review-result.json",
    APPROVED_R003_DIR / "independent-review.json",
)
APPROVED_R003_REVIEW_PINS = {
    APPROVED_R003_REVIEW_PATHS[0]: (
        "b060fb5cd801a06f0cf2ef79be5babb56a0fc13f153872c63f076c87b1cf5b5a",
        28_440,
    ),
    APPROVED_R003_REVIEW_PATHS[1]: (
        "592b6b69d2b2be143bef29b9d8b973a2c12259a4dc14ae221d75b7671ea126d4",
        28_232,
    ),
    APPROVED_R003_REVIEW_PATHS[2]: (
        "4172919a6d74a58ede8c8b7d93a545c2a9f68815f0bcc029e170f9d870963279",
        28_515,
    ),
}
R003_SUPERSESSION_REASON_CODE = "R003_DIRECT_GATE_EVIDENCE_INCLUDED_IN_SNAPSHOT"
APPROVED_R004_DIR = REVIEW_ROOT / "R004"
APPROVED_R004_REVIEW_PATHS = (
    APPROVED_R004_DIR / "review-assignment.json",
    APPROVED_R004_DIR / "review-result.json",
    APPROVED_R004_DIR / "independent-review.json",
)
APPROVED_R004_REVIEW_PINS = {
    APPROVED_R004_REVIEW_PATHS[0]: (
        "550f13e56aab4759ab30eb8d799343b3d6d527434eeb5a0823fceab4280b1524",
        29_436,
    ),
    APPROVED_R004_REVIEW_PATHS[1]: (
        "b885944277d0e4ee97a1548ac6b078390c8c7cc639ed2f84a70f4eff0817f7a3",
        29_228,
    ),
    APPROVED_R004_REVIEW_PATHS[2]: (
        "8a8058de2c11d5c0775212fd11066237e2ae86f4082b1d41459674cf04a8e850",
        29_511,
    ),
}
R004_SUPERSESSION_REASON_CODE = "R004_POST_TRANSITION_SOURCE_BINDING_SEQ77_ONLY"
APPROVED_R005_DIR = REVIEW_ROOT / "R005"
APPROVED_R005_REVIEW_PATHS = (
    APPROVED_R005_DIR / "review-assignment.json",
    APPROVED_R005_DIR / "review-result.json",
    APPROVED_R005_DIR / "independent-review.json",
)
APPROVED_R005_REVIEW_PINS = {
    APPROVED_R005_REVIEW_PATHS[0]: (
        "838d11cc1ae161453b563df752d6b9c31c115d46ffba46a1a025546bfb8e2c53",
        30_947,
    ),
    APPROVED_R005_REVIEW_PATHS[1]: (
        "615f8fb5f70c0adfb10f14df3faa4de5440d4e1d598badb93622616fd967f103",
        30_739,
    ),
    APPROVED_R005_REVIEW_PATHS[2]: (
        "366652e2b9d98aa09dc87a22036477dfc83dabdd2c05446a48c33835dbbf52a6",
        31_022,
    ),
}
R005_SUPERSESSION_REASON_CODE = "R005_SEQ79_CORRECTION_EVENT_NOT_DISPATCHED"
APPROVED_R006_DIR = REVIEW_ROOT / "R006"
APPROVED_R006_REVIEW_PATHS = (
    APPROVED_R006_DIR / "review-assignment.json",
    APPROVED_R006_DIR / "review-result.json",
    APPROVED_R006_DIR / "independent-review.json",
)
APPROVED_R006_REVIEW_PINS = {
    APPROVED_R006_REVIEW_PATHS[0]: (
        "386b6c02ac4765fe404d881681020617f7a18c876c60395d055584f667e80268",
        31_928,
    ),
    APPROVED_R006_REVIEW_PATHS[1]: (
        "e2eb20d256ee479f866ac90add3618e264edeb78dbefb72764b400bc3ba87275",
        31_720,
    ),
    APPROVED_R006_REVIEW_PATHS[2]: (
        "56419c6e40280bfb2e1a1ab3a8d771df65f264c0d3d8a00f1d36fe0c944dad11",
        32_003,
    ),
}
R006_SUPERSESSION_REASON_CODE = "R006_GOAL_CONSUMER_R005_PREDECESSOR_NOT_BOUND"
APPROVED_R007_DIR = REVIEW_ROOT / "R007"
APPROVED_R007_REVIEW_PATHS = (
    APPROVED_R007_DIR / "review-assignment.json",
    APPROVED_R007_DIR / "review-result.json",
    APPROVED_R007_DIR / "independent-review.json",
)
APPROVED_R007_REVIEW_PINS = {
    APPROVED_R007_REVIEW_PATHS[0]: (
        "aad0caaf234b915b7438065bb3e14c751270c263aeb68201caea7944e1454933",
        32_135,
    ),
    APPROVED_R007_REVIEW_PATHS[1]: (
        "1217e4d2b849cf4dcf13d07bb33ee286ebe1c68ac94c427541b5dc74eda8be78",
        31_927,
    ),
    APPROVED_R007_REVIEW_PATHS[2]: (
        "33c903b06a620c63998d1e34ecbf81e2fb4f32b7e64f44dbc14ad75ab3418050",
        32_210,
    ),
}
R007_SUPERSESSION_REASON_CODE = (
    "R007_GATE_EVIDENCE_DIRECTORY_SCANNER_REJECTED_EXACT_FAILED_ATTEMPT"
)
APPROVED_R008_DIR = REVIEW_ROOT / "R008"
APPROVED_R008_REVIEW_PATHS = (
    APPROVED_R008_DIR / "review-assignment.json",
    APPROVED_R008_DIR / "review-result.json",
    APPROVED_R008_DIR / "independent-review.json",
)
APPROVED_R008_REVIEW_PINS = {
    APPROVED_R008_REVIEW_PATHS[0]: (
        "632b11be64ebcb83b0d76e579b217568c7ef928382ddebd96d4b2884de8eb10a",
        33_680,
    ),
    APPROVED_R008_REVIEW_PATHS[1]: (
        "fcaf66afc18268d5a4a87ff3623475aa25f7156176a41d9ec86340f6e97cb069",
        33_472,
    ),
    APPROVED_R008_REVIEW_PATHS[2]: (
        "c4d00677c700f17cb0d3bf986dc9b3d0d5b8b21068d00dd06502a9fc02536095",
        33_755,
    ),
}
R008_SUPERSESSION_REASON_CODE = (
    "R008_EMPTY_FAILED_GATE_002_NOT_PROJECTED_IN_ISOLATED_SNAPSHOT"
)
APPROVED_R009_DIR = REVIEW_ROOT / "R009"
APPROVED_R009_REVIEW_PATHS = (
    APPROVED_R009_DIR / "review-assignment.json",
    APPROVED_R009_DIR / "review-result.json",
    APPROVED_R009_DIR / "independent-review.json",
)
APPROVED_R009_REVIEW_PINS = {
    APPROVED_R009_REVIEW_PATHS[0]: (
        "a0e94be42eafe9143306bc4bc4e7ce169c5f225bbf9d44f79fcb1f070645e86a",
        35_321,
    ),
    APPROVED_R009_REVIEW_PATHS[1]: (
        "8b7a14f57103e65b7fc9e40899fb4149f6ee09ae7aa66c42a9520a29d7a6150a",
        35_113,
    ),
    APPROVED_R009_REVIEW_PATHS[2]: (
        "b813e577265981f80720b1205850e622e86ca8e2bf2076cca6563b23fa4a7214",
        35_396,
    ),
}
R009_SUPERSESSION_REASON_CODE = (
    "R009_ISOLATED_REVIEW_CONSUMER_REEVALUATED_FAILED_GATE_002_PHYSICAL"
)
REJECTED_R010_DIR = REVIEW_ROOT / "R010"
REJECTED_R010_ASSIGNMENT_REL = REJECTED_R010_DIR / "review-assignment.json"
REJECTED_R010_RESULT_REL = REJECTED_R010_DIR / "review-result.json"
REJECTED_R010_INDEPENDENT_REL = REJECTED_R010_DIR / "independent-review.json"
REJECTED_R010_ASSIGNMENT_SHA256 = (
    "2a68a42ec883cee0ffab91c90d8f5e1d2daa001d9db7041551d850fda24db8a7"
)
REJECTED_R010_ASSIGNMENT_BYTE_LENGTH = 36_282
R010_SUPERSESSION_REASON_CODE = "R010_VALID_ADD_ONLY_PREFIX_REGRESSION_REJECTED"
APPROVED_R011_DIR = REVIEW_ROOT / "R011"
APPROVED_R011_REVIEW_PATHS = (
    APPROVED_R011_DIR / "review-assignment.json",
    APPROVED_R011_DIR / "review-result.json",
    APPROVED_R011_DIR / "independent-review.json",
)
APPROVED_R011_REVIEW_PINS = {
    APPROVED_R011_REVIEW_PATHS[0]: (
        "8a27b0661d75d17e9e972a70082c0a3e0cfad21d89a177c3b36d1a4904a1ad89",
        36_828,
    ),
    APPROVED_R011_REVIEW_PATHS[1]: (
        "00f44cae1ffbb1af25b95433e89ab2b098be58af7b734d083cdf448875a264f8",
        36_620,
    ),
    APPROVED_R011_REVIEW_PATHS[2]: (
        "3c71039c6dfa57dd9001c4f5f4893c08f1b10db5e64bccca0d578c3d76e07702",
        36_903,
    ),
}
R011_SUPERSESSION_REASON_CODE = (
    "R011_CHECKPOINT_AND_CATALOG_DESTINATION_PUBLICATION_NOT_ATOMIC"
)
APPROVED_R012_DIR = REVIEW_ROOT / "R012"
APPROVED_R012_REVIEW_PATHS = (
    APPROVED_R012_DIR / "review-assignment.json",
    APPROVED_R012_DIR / "review-result.json",
    APPROVED_R012_DIR / "independent-review.json",
)
APPROVED_R012_REVIEW_PINS = {
    APPROVED_R012_REVIEW_PATHS[0]: (
        "287e64d716cc23d138ec8bf87690342df8e9136d220f0b227f212c29341886d7",
        38_480,
    ),
    APPROVED_R012_REVIEW_PATHS[1]: (
        "9f71b7717d60746e59c8b476acc6679bb3f8aedaa15bf7135a554e1d3b709237",
        38_272,
    ),
    APPROVED_R012_REVIEW_PATHS[2]: (
        "2729e096da3596b939e6260444b7b8eb4b329fba11bdf27f5391c40b0c707330",
        38_555,
    ),
}
R012_SUPERSESSION_REASON_CODE = (
    "R012_PROJECTED_START_ACTIVATION_PREDECESSOR_STALE_SEQ81"
)
REJECTED_R013_DIR = REVIEW_ROOT / "R013"
REJECTED_R013_ASSIGNMENT_REL = REJECTED_R013_DIR / "review-assignment.json"
REJECTED_R013_RESULT_REL = REJECTED_R013_DIR / "review-result.json"
REJECTED_R013_INDEPENDENT_REL = REJECTED_R013_DIR / "independent-review.json"
REJECTED_R013_ASSIGNMENT_SHA256 = (
    "217b0e8c33b7d28c020bf661ca16ed74fd5bc3b9eef059829c52478359aaec98"
)
REJECTED_R013_ASSIGNMENT_BYTE_LENGTH = 41_673
R013_SUPERSESSION_REASON_CODE = "R013_ASSIGNMENT_ONLY_REVIEW_INTERRUPTED"
PRESERVED_REVIEW_PATHS = (
    *R006_REVIEW_PATHS,
    REJECTED_R001_ASSIGNMENT_REL,
    *APPROVED_R002_REVIEW_PATHS,
    *APPROVED_R003_REVIEW_PATHS,
    *APPROVED_R004_REVIEW_PATHS,
    *APPROVED_R005_REVIEW_PATHS,
    *APPROVED_R006_REVIEW_PATHS,
    *APPROVED_R007_REVIEW_PATHS,
    *APPROVED_R008_REVIEW_PATHS,
    *APPROVED_R009_REVIEW_PATHS,
    REJECTED_R010_ASSIGNMENT_REL,
    *APPROVED_R011_REVIEW_PATHS,
    *APPROVED_R012_REVIEW_PATHS,
    REJECTED_R013_ASSIGNMENT_REL,
)

REVIEW_DIR = REVIEW_ROOT / "R014"
ASSIGNMENT_REL = REVIEW_DIR / "review-assignment.json"
RESULT_REL = REVIEW_DIR / "review-result.json"
INDEPENDENT_REL = REVIEW_DIR / "independent-review.json"
REVIEW_PATHS = (ASSIGNMENT_REL, RESULT_REL, INDEPENDENT_REL)
ROUND_ID = "WS-FP046-R002-SEQ84-85-RECOVERY-REVIEW-20260825-R014"
ASSIGNMENT_DOCUMENT_ID = (
    "WS-FP046-R002-SEQ84-85-RECOVERY-REVIEW-ASSIGNMENT-20260825-R014"
)
RESULT_DOCUMENT_ID = (
    "WS-FP046-R002-SEQ84-85-RECOVERY-REVIEW-RESULT-20260825-R014"
)
INDEPENDENT_DOCUMENT_ID = (
    "WS-FP046-R002-SEQ84-85-RECOVERY-INDEPENDENT-REVIEW-20260825-R014"
)
ASSIGNER_ID = "codex-root-fp046-r002-seq84-85-r014-assigner-20260825"
ASSIGNER_TASK = "/root"
EXECUTOR_ID = "codex-fp046-r002-seq84-85-r014-control-executor-20260825"
EXECUTOR_TASK = "/root/seq77_78_control_review"
REVIEWER_ID = "codex-fp046-r002-seq84-85-r014-independent-reviewer-20260825"
REVIEWER_TASK = "/root/seq77_78_final_review"

FAILED_GATE_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-001"
)
FAILED_GATE_DIR = Path("docs/control/execution/goal-gates") / FAILED_GATE_EVENT_ID
FAILED_GATE_LOG_REL = FAILED_GATE_DIR / "01-CONTINUATION.log"
FAILED_GATE_LOG_SHA256 = "b49838682f11cac1a78ef86bfb7866a7a5166e60c21d0fd39c78a9d1d7c1f411"
FAILED_GATE_LOG_BYTE_LENGTH = 681
FAILED_GATE_DIRECTORY_MODE = 0o700
FAILED_GATE_LOG_MODE = 0o600
FAILED_GATE_002_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-002"
)
FAILED_GATE_002_DIR = (
    Path("docs/control/execution/goal-gates") / FAILED_GATE_002_EVENT_ID
)
FAILED_GATE_003_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-003"
)
FAILED_GATE_003_DIR = (
    Path("docs/control/execution/goal-gates") / FAILED_GATE_003_EVENT_ID
)
FAILED_GATE_003_LOG_REL = FAILED_GATE_003_DIR / "01-CONTINUATION.log"
FAILED_GATE_003_LOG_SHA256 = (
    "2fcd1a0cde039739c7be46d321370b3d11c14c4ac01608dbe9ad73061a65fedc"
)
FAILED_GATE_003_LOG_BYTE_LENGTH = 848
STORED_FAILED_GATE_ATTEMPT = {
    "event_id": FAILED_GATE_EVENT_ID,
    "directory": FAILED_GATE_DIR.as_posix(),
    "directory_mode": "0700",
    "only_log_binding": {
        "path": FAILED_GATE_LOG_REL.as_posix(),
        "sha256": FAILED_GATE_LOG_SHA256,
        "byte_length": FAILED_GATE_LOG_BYTE_LENGTH,
    },
    "log_mode": "0600",
    "receipt_present": False,
    "later_log_count": 0,
}
STORED_FAILED_GATE_ATTEMPT_002 = {
    "event_id": FAILED_GATE_002_EVENT_ID,
    "directory": FAILED_GATE_002_DIR.as_posix(),
    "directory_mode": "0700",
    "directory_inventory": [],
    "log_count": 0,
    "receipt_present": False,
}
STORED_FAILED_GATE_ATTEMPT_003 = {
    "event_id": FAILED_GATE_003_EVENT_ID,
    "directory": FAILED_GATE_003_DIR.as_posix(),
    "directory_mode": "0700",
    "only_log_binding": {
        "path": FAILED_GATE_003_LOG_REL.as_posix(),
        "sha256": FAILED_GATE_003_LOG_SHA256,
        "byte_length": FAILED_GATE_003_LOG_BYTE_LENGTH,
    },
    "log_mode": "0600",
    "receipt_present": False,
    "later_log_count": 0,
}
PASSED_GATE_004_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-004"
)
PASSED_GATE_004_DIR = (
    Path("docs/control/execution/goal-gates") / PASSED_GATE_004_EVENT_ID
)
PASSED_GATE_004_FILE_PINS = {
    PASSED_GATE_004_DIR / "01-CONTINUATION.log": (
        "25b5c8cf65854e11a04a9d0810dd2503585037683809c153f578c08a076ac5a7",
        39,
    ),
    PASSED_GATE_004_DIR / "02-GOAL_GRAPH.log": (
        "7190afa3a3f6e6852b462bfcd6663462b588efa73688b109b66799670e7f8b55",
        108,
    ),
    PASSED_GATE_004_DIR / "03-TEST_LAYER_REGISTRY_VALIDATE.log": (
        "1eef448a998bf080cbe648b330b4c9369d49ea95bbccf662ce8d077b56c96187",
        35,
    ),
    PASSED_GATE_004_DIR / "04-ROOT_FP046_R002_CONTROL_REGRESSION.log": (
        "6c3e7ef984f83b697ccfc37f9973b285f83fb4df3094340638e2d9de204b00de",
        451,
    ),
    PASSED_GATE_004_DIR / "05-REPOSITORY_STATE.log": (
        "a2e85e15efb1c342d5f4a61935aac9bc14655071c8023cd8c20a581b01e869fa",
        50_975,
    ),
    PASSED_GATE_004_DIR / "implementation-start-gate-receipt.json": (
        "2499d7d2e8833c51d6715f5bde408e9a927e35f7962c966aff21af8d2cf11cb6",
        7_918,
    ),
}
STORED_PASSED_GATE_ATTEMPT_004 = {
    "event_id": PASSED_GATE_004_EVENT_ID,
    "directory": PASSED_GATE_004_DIR.as_posix(),
    "directory_mode": "0700",
    "status": "PASS_UNCONSUMED",
    "files": [
        {
            "path": path.as_posix(),
            "sha256": digest,
            "byte_length": byte_length,
        }
        for path, (digest, byte_length) in PASSED_GATE_004_FILE_PINS.items()
    ],
}

SCRIPT_REL = Path("scripts/build_walksafe_fp046_r002_seq78_79_recovery_review_20260824.py")
TEST_REL = Path("tests/test_build_walksafe_fp046_r002_seq78_79_recovery_review_20260824.py")
CORRECTION_SCRIPT_REL = Path(
    "scripts/apply_walksafe_fp046_r002_goal_start_control_correction_seq78_20260824.py"
)
CORRECTION_TEST_REL = Path(
    "tests/test_apply_walksafe_fp046_r002_goal_start_control_correction_seq78_20260824.py"
)
STARTED_SCRIPT_REL = Path(
    "scripts/apply_walksafe_fp046_r002_goal_started_seq79_20260824.py"
)
STARTED_TEST_REL = Path(
    "tests/test_apply_walksafe_fp046_r002_goal_started_seq79_20260824.py"
)
GATE_SCRIPT_REL = Path("scripts/run_walksafe_fp046_r002_goal_start_gate_20260823.py")
GATE_TEST_REL = Path("tests/test_walksafe_fp046_r002_goal_start_gate_20260823.py")
MODIFIED_CONTROL_PATHS = (
    *tuple(sorted(predecessor.MODIFIED_CONTROL_PATHS)),
    GATE_SCRIPT_REL,
    GATE_TEST_REL,
)
ADDED_CONTROL_PATHS = (
    SCRIPT_REL,
    TEST_REL,
    CORRECTION_SCRIPT_REL,
    CORRECTION_TEST_REL,
    STARTED_SCRIPT_REL,
    STARTED_TEST_REL,
)
CURRENT_CONTROL_PATHS = (*predecessor.CURRENT_CONTROL_PATHS, *ADDED_CONTROL_PATHS)
PENDING_OTHER_LANE_PATHS = (
    GATE_SCRIPT_REL,
    GATE_TEST_REL,
    STARTED_SCRIPT_REL,
    STARTED_TEST_REL,
)

PROJECTED_TRANSITION = {
    "control_correction": {
        "sequence": 84,
        "event_id": (
            "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-"
            "FP046-R002-CORRECTION-20260824-007"
        ),
        "event_type": "GOAL_START_CONTROL_REANCHORED",
        "subject_goal_id": GOAL_ID,
        "from_status": "READY",
        "to_status": "READY",
        "status_changes": {},
        "credit_delta": 0,
    },
    "goal_started": {
        "sequence": 85,
        "event_id": "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-005",
        "burned_event_ids": [
            FAILED_GATE_EVENT_ID,
            FAILED_GATE_002_EVENT_ID,
            FAILED_GATE_003_EVENT_ID,
            PASSED_GATE_004_EVENT_ID,
        ],
        "from_status": "READY",
        "to_status": "IN_PROGRESS",
        "requires_exact_private_gate_pass_receipt": True,
    },
}


def _safe_relative(relative: Path) -> None:
    require(
        not relative.is_absolute()
        and relative.parts
        and all(part not in {"", ".", ".."} for part in relative.parts),
        f"unsafe path: {relative}",
    )


def _read_regular(
    root: Path,
    relative: Path,
    *,
    allowed_modes: frozenset[int] = REVIEW_FILE_MODES,
    maximum_bytes: int = MAXIMUM_EVIDENCE_BYTES,
) -> bytes:
    _safe_relative(relative)
    root = root.resolve(strict=True)
    current = root
    for part in relative.parts[:-1]:
        current /= part
        info = current.lstat()
        require(stat.S_ISDIR(info.st_mode), f"non-directory parent: {relative}")
        require(not current.is_symlink(), f"symlink parent: {relative}")
        if REVIEW_ROOT == relative or REVIEW_ROOT in relative.parents:
            require(
                stat.S_IMODE(info.st_mode) in REVIEW_DIRECTORY_MODES
                and info.st_uid == os.getuid()
                and info.st_gid == os.getgid(),
                f"review directory mode differs: {current.relative_to(root)}",
            )
    path = root / relative
    info = path.lstat()
    require(
        stat.S_ISREG(info.st_mode)
        and not path.is_symlink()
        and info.st_nlink == 1
        and info.st_uid == os.getuid()
        and info.st_gid == os.getgid()
        and stat.S_IMODE(info.st_mode) in allowed_modes
        and info.st_size <= maximum_bytes,
        f"unsafe review input: {relative}",
    )
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0)
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    descriptor = os.open(path, flags)
    try:
        opened = os.fstat(descriptor)
        require(
            (info.st_dev, info.st_ino) == (opened.st_dev, opened.st_ino),
            f"review input identity changed before read: {relative}",
        )
        chunks: list[bytes] = []
        remaining = maximum_bytes + 1
        while remaining:
            chunk = os.read(descriptor, min(1024 * 1024, remaining))
            if not chunk:
                break
            chunks.append(chunk)
            remaining -= len(chunk)
        raw = b"".join(chunks)
        after = os.fstat(descriptor)
        require(
            (opened.st_dev, opened.st_ino, opened.st_mode, opened.st_nlink,
             opened.st_uid, opened.st_gid, opened.st_size, opened.st_mtime_ns,
             opened.st_ctime_ns)
            == (after.st_dev, after.st_ino, after.st_mode, after.st_nlink,
                after.st_uid, after.st_gid, after.st_size, after.st_mtime_ns,
                after.st_ctime_ns)
            and len(raw) == opened.st_size
            and len(raw) <= maximum_bytes,
            f"review input changed while read: {relative}",
        )
    finally:
        os.close(descriptor)
    return raw


def _strict_json(raw: bytes, label: str) -> dict[str, Any]:
    try:
        value = json.loads(
            raw.decode("utf-8"),
            object_pairs_hook=lambda pairs: _unique_object(pairs, label),
            parse_constant=lambda value: (_ for _ in ()).throw(
                ReviewError(f"non-finite number in {label}: {value}")
            ),
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ReviewError(f"invalid JSON: {label}") from exc
    require(type(value) is dict, f"JSON object required: {label}")
    require(raw == json_text(value).encode(), f"noncanonical JSON: {label}")
    return value


def _checkpoint_json(raw: bytes) -> dict[str, Any]:
    try:
        value = json.loads(
            raw.decode("utf-8"),
            object_pairs_hook=lambda pairs: _unique_object(pairs, "checkpoint"),
            parse_constant=lambda item: (_ for _ in ()).throw(
                ReviewError(f"non-finite number in checkpoint: {item}")
            ),
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ReviewError("invalid checkpoint JSON") from exc
    require(type(value) is dict, "checkpoint JSON object required")
    return value


def _unique_object(pairs: list[tuple[str, Any]], label: str) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        require(key not in value, f"duplicate JSON member in {label}: {key}")
        value[key] = item
    return value


def binding(relative: Path, raw: bytes) -> dict[str, Any]:
    return {
        "path": relative.as_posix(),
        "sha256": bytes_sha256(raw),
        "byte_length": len(raw),
    }


def preserved_review_bindings(root: Path = ROOT) -> tuple[dict[str, Any], ...]:
    rows = []
    documents = []
    for relative in R006_REVIEW_PATHS:
        raw = _read_regular(root, relative, allowed_modes=frozenset({0o644}))
        digest, size = PRESERVED_REVIEW_PINS[relative]
        require(
            len(raw) == size and bytes_sha256(raw) == digest,
            f"approved R006 predecessor differs: {relative}",
        )
        rows.append(binding(relative, raw))
        documents.append(_strict_json(raw, relative.as_posix()))
    assignment, result, independent = documents
    predecessor_scope = assignment.get("review_scope")
    predecessor_cohort = (
        predecessor_scope.get("reviewed_current_control_cohort")
        if type(predecessor_scope) is dict
        else None
    )
    require(
        assignment.get("round_id") == predecessor.ROUND_ID
        and result.get("decision") == "APPROVED"
        and independent.get("decision") == "APPROVED"
        and independent.get("status") == "PASS"
        and strict_json_equal(result.get("assignment_binding"), rows[0])
        and strict_json_equal(independent.get("assignment_provenance"), rows[0])
        and strict_json_equal(independent.get("review_result_provenance"), rows[1]),
        "approved R006 predecessor lineage differs",
    )
    require(
        type(predecessor_cohort) is list
        and len(predecessor_cohort) == 31
        and tuple(row.get("path") for row in predecessor_cohort)
        == tuple(path.as_posix() for path in predecessor.CURRENT_CONTROL_PATHS)
        and predecessor_scope.get("reviewed_current_control_cohort_sha256")
        == PREDECESSOR_CONTROL_COHORT_SHA256,
        "approved R006 predecessor control cohort differs",
    )
    return tuple(rows)


def failed_gate_attempt_binding(root: Path = ROOT) -> dict[str, Any]:
    root = root.resolve(strict=True)
    directory = root / FAILED_GATE_DIR
    info = directory.lstat()
    require(
        stat.S_ISDIR(info.st_mode)
        and not directory.is_symlink()
        and stat.S_IMODE(info.st_mode) == FAILED_GATE_DIRECTORY_MODE
        and info.st_nlink == 2
        and info.st_uid == os.getuid()
        and info.st_gid == os.getgid(),
        "failed gate directory authority differs",
    )
    names = sorted(path.name for path in directory.iterdir())
    after_directory = directory.lstat()
    require(
        names == [FAILED_GATE_LOG_REL.name]
        and (info.st_dev, info.st_ino, info.st_mode, info.st_nlink, info.st_uid,
             info.st_gid, info.st_mtime_ns, info.st_ctime_ns)
        == (after_directory.st_dev, after_directory.st_ino,
            after_directory.st_mode, after_directory.st_nlink,
            after_directory.st_uid, after_directory.st_gid,
            after_directory.st_mtime_ns, after_directory.st_ctime_ns),
        "failed gate directory inventory differs",
    )
    raw = _read_regular(
        root,
        FAILED_GATE_LOG_REL,
        allowed_modes=frozenset({FAILED_GATE_LOG_MODE}),
        maximum_bytes=FAILED_GATE_LOG_BYTE_LENGTH,
    )
    require(
        len(raw) == FAILED_GATE_LOG_BYTE_LENGTH
        and bytes_sha256(raw) == FAILED_GATE_LOG_SHA256,
        "failed gate log differs",
    )
    return {
        "event_id": FAILED_GATE_EVENT_ID,
        "directory": FAILED_GATE_DIR.as_posix(),
        "directory_mode": "0700",
        "only_log_binding": binding(FAILED_GATE_LOG_REL, raw),
        "log_mode": "0600",
        "receipt_present": False,
        "later_log_count": 0,
    }


def failed_gate_attempt_002_binding(root: Path = ROOT) -> dict[str, Any]:
    root = root.resolve(strict=True)
    directory = root / FAILED_GATE_002_DIR
    info = directory.lstat()
    require(
        stat.S_ISDIR(info.st_mode)
        and not directory.is_symlink()
        and stat.S_IMODE(info.st_mode) == 0o700
        and info.st_nlink == 2
        and info.st_uid == os.getuid()
        and info.st_gid == os.getgid(),
        "failed gate 002 directory authority differs",
    )
    names = sorted(path.name for path in directory.iterdir())
    after = directory.lstat()
    require(
        names == []
        and (
            info.st_dev,
            info.st_ino,
            info.st_mode,
            info.st_nlink,
            info.st_uid,
            info.st_gid,
            info.st_mtime_ns,
            info.st_ctime_ns,
        )
        == (
            after.st_dev,
            after.st_ino,
            after.st_mode,
            after.st_nlink,
            after.st_uid,
            after.st_gid,
            after.st_mtime_ns,
            after.st_ctime_ns,
        ),
        "failed gate 002 directory inventory differs",
    )
    return {
        "event_id": FAILED_GATE_002_EVENT_ID,
        "directory": FAILED_GATE_002_DIR.as_posix(),
        "directory_mode": "0700",
        "directory_inventory": [],
        "log_count": 0,
        "receipt_present": False,
    }


def failed_gate_attempt_003_binding(root: Path = ROOT) -> dict[str, Any]:
    root = root.resolve(strict=True)
    directory = root / FAILED_GATE_003_DIR
    info = directory.lstat()
    require(
        stat.S_ISDIR(info.st_mode)
        and not directory.is_symlink()
        and stat.S_IMODE(info.st_mode) == 0o700
        and info.st_nlink == 2
        and info.st_uid == os.getuid()
        and info.st_gid == os.getgid(),
        "failed gate 003 directory authority differs",
    )
    names = sorted(path.name for path in directory.iterdir())
    after = directory.lstat()
    require(
        names == [FAILED_GATE_003_LOG_REL.name]
        and (
            info.st_dev, info.st_ino, info.st_mode, info.st_nlink,
            info.st_uid, info.st_gid, info.st_mtime_ns, info.st_ctime_ns,
        )
        == (
            after.st_dev, after.st_ino, after.st_mode, after.st_nlink,
            after.st_uid, after.st_gid, after.st_mtime_ns, after.st_ctime_ns,
        ),
        "failed gate 003 directory inventory differs",
    )
    raw = _read_regular(
        root,
        FAILED_GATE_003_LOG_REL,
        allowed_modes=frozenset({0o600}),
        maximum_bytes=FAILED_GATE_003_LOG_BYTE_LENGTH,
    )
    require(
        len(raw) == FAILED_GATE_003_LOG_BYTE_LENGTH
        and bytes_sha256(raw) == FAILED_GATE_003_LOG_SHA256,
        "failed gate 003 log differs",
    )
    return {
        "event_id": FAILED_GATE_003_EVENT_ID,
        "directory": FAILED_GATE_003_DIR.as_posix(),
        "directory_mode": "0700",
        "only_log_binding": binding(FAILED_GATE_003_LOG_REL, raw),
        "log_mode": "0600",
        "receipt_present": False,
        "later_log_count": 0,
    }


def passed_gate_attempt_004_binding(root: Path = ROOT) -> dict[str, Any]:
    root = root.resolve(strict=True)
    directory = root / PASSED_GATE_004_DIR
    before = directory.lstat()
    require(
        stat.S_ISDIR(before.st_mode)
        and not directory.is_symlink()
        and stat.S_IMODE(before.st_mode) == 0o700
        and before.st_nlink == 2
        and before.st_uid == os.getuid()
        and before.st_gid == os.getgid(),
        "passed gate 004 directory authority differs",
    )
    require(
        sorted(path.name for path in directory.iterdir())
        == sorted(path.name for path in PASSED_GATE_004_FILE_PINS),
        "passed gate 004 inventory differs",
    )
    records = []
    for relative, (digest, byte_length) in PASSED_GATE_004_FILE_PINS.items():
        raw = _read_regular(
            root,
            relative,
            allowed_modes=frozenset({0o600}),
            maximum_bytes=byte_length,
        )
        require(
            len(raw) == byte_length and bytes_sha256(raw) == digest,
            f"passed gate 004 file differs: {relative}",
        )
        records.append(binding(relative, raw))
    after = directory.lstat()
    require(
        (
            before.st_dev,
            before.st_ino,
            before.st_mode,
            before.st_nlink,
            before.st_uid,
            before.st_gid,
            before.st_mtime_ns,
            before.st_ctime_ns,
        )
        == (
            after.st_dev,
            after.st_ino,
            after.st_mode,
            after.st_nlink,
            after.st_uid,
            after.st_gid,
            after.st_mtime_ns,
            after.st_ctime_ns,
        ),
        "passed gate 004 directory inventory changed",
    )
    return {
        "event_id": PASSED_GATE_004_EVENT_ID,
        "directory": PASSED_GATE_004_DIR.as_posix(),
        "directory_mode": "0700",
        "status": "PASS_UNCONSUMED",
        "files": records,
    }


def _static_binding(root: Path, relative: Path, digest: str | None = None, size: int | None = None) -> dict[str, Any]:
    raw = _read_regular(root, relative)
    if digest is not None:
        require(bytes_sha256(raw) == digest and len(raw) == size, f"static binding differs: {relative}")
    return binding(relative, raw)


def source_checkpoint_binding(root: Path = ROOT) -> dict[str, Any]:
    live_raw = _read_regular(root, CHECKPOINT_REL)
    document = _checkpoint_json(live_raw)
    history = document.get("goal_execution", {}).get("transition_history")
    require(
        type(history) is list
        and len(history) >= SOURCE_SEQUENCE
        and history[SOURCE_SEQUENCE - 1].get("event_id") == SOURCE_EVENT_ID
        and history[SOURCE_SEQUENCE - 1].get("event_sha256") == SOURCE_EVENT_SHA256,
        "checkpoint does not preserve exact seq77",
    )
    if len(history) == SOURCE_SEQUENCE:
        require(
            len(live_raw) == SOURCE_CHECKPOINT_BYTE_LENGTH
            and bytes_sha256(live_raw) == SOURCE_CHECKPOINT_SHA256,
            "exact seq77 pre-review checkpoint bytes differ",
        )
        source_raw = live_raw
    else:
        from scripts import (
            apply_walksafe_fp046_r002_goal_start_control_reanchor_seq77_20260823
            as reanchor,
        )

        source_raw = reanchor.reconstructed_seq77_checkpoint_bytes(
            root, history[SOURCE_SEQUENCE - 1]
        )
    require(
        len(source_raw) == SOURCE_CHECKPOINT_BYTE_LENGTH
        and bytes_sha256(source_raw) == SOURCE_CHECKPOINT_SHA256,
        "reconstructed seq77 source checkpoint differs",
    )
    row = binding(CHECKPOINT_REL, source_raw)
    return {**row, "sequence": SOURCE_SEQUENCE, "tail_event_id": SOURCE_EVENT_ID, "tail_event_sha256": SOURCE_EVENT_SHA256}


def review_source_checkpoint_binding(root: Path = ROOT) -> dict[str, Any]:
    live_raw = _read_regular(root, CHECKPOINT_REL)
    document = _checkpoint_json(live_raw)
    history = document.get("goal_execution", {}).get("transition_history")
    require(
        type(history) is list
        and len(history) >= REVIEW_SOURCE_SEQUENCE
        and history[REVIEW_SOURCE_SEQUENCE - 1].get("event_id")
        == REVIEW_SOURCE_EVENT_ID
        and history[REVIEW_SOURCE_SEQUENCE - 1].get("event_sha256")
        == REVIEW_SOURCE_EVENT_SHA256,
        "checkpoint does not preserve exact seq83 review source",
    )
    if len(history) == REVIEW_SOURCE_SEQUENCE:
        require(
            len(live_raw) == REVIEW_SOURCE_CHECKPOINT_BYTE_LENGTH
            and bytes_sha256(live_raw) == REVIEW_SOURCE_CHECKPOINT_SHA256,
            "exact seq83 review source checkpoint bytes differ",
        )
        source_raw = live_raw
    else:
        from scripts import (
            apply_walksafe_fp046_r002_goal_start_control_correction_seq78_20260824
            as correction,
        )

        source_raw = correction.reconstructed_seq83_checkpoint_bytes(
            root, history[REVIEW_SOURCE_SEQUENCE - 1]
        )
    require(
        len(source_raw) == REVIEW_SOURCE_CHECKPOINT_BYTE_LENGTH
        and bytes_sha256(source_raw) == REVIEW_SOURCE_CHECKPOINT_SHA256,
        "reconstructed seq83 review source checkpoint differs",
    )
    return {
        **binding(CHECKPOINT_REL, source_raw),
        "sequence": REVIEW_SOURCE_SEQUENCE,
        "tail_event_id": REVIEW_SOURCE_EVENT_ID,
        "tail_event_sha256": REVIEW_SOURCE_EVENT_SHA256,
    }


def current_control_cohort(root: Path = ROOT, *, require_complete: bool = True) -> tuple[dict[str, Any], ...]:
    rows = []
    for relative in CURRENT_CONTROL_PATHS:
        path = root / relative
        if (
            not path.exists()
            and not require_complete
            and relative in (*ADDED_CONTROL_PATHS, *PENDING_OTHER_LANE_PATHS)
        ):
            continue
        rows.append(_static_binding(root, relative))
    if require_complete:
        require(len(rows) == 37, "recovery current control cohort is not exactly 37 paths")
    return tuple(rows)


@dataclass(frozen=True)
class ReviewContext:
    source_checkpoint: dict[str, Any]
    review_source_checkpoint: dict[str, Any]
    predecessor_review_bindings: tuple[dict[str, Any], ...]
    predecessor_control_cohort: tuple[dict[str, Any], ...]
    failed_gate_attempt: dict[str, Any]
    failed_gate_attempt_002: dict[str, Any]
    failed_gate_attempt_003: dict[str, Any]
    passed_gate_attempt_004: dict[str, Any]
    authorization_binding: dict[str, Any]
    contract_binding: dict[str, Any]
    current_control_cohort: tuple[dict[str, Any], ...]
    control_code_successors: tuple[dict[str, Any], ...]
    added_control_code_bindings: tuple[dict[str, Any], ...]
    missing_add_only_paths: tuple[str, ...]
    unready_modified_paths: tuple[str, ...]
    rejected_r001_assignment_binding: dict[str, Any]
    approved_r002_review_bindings: tuple[dict[str, Any], ...]
    approved_r003_review_bindings: tuple[dict[str, Any], ...]
    approved_r004_review_bindings: tuple[dict[str, Any], ...]
    approved_r005_review_bindings: tuple[dict[str, Any], ...]
    approved_r006_review_bindings: tuple[dict[str, Any], ...]
    approved_r007_review_bindings: tuple[dict[str, Any], ...]
    approved_r008_review_bindings: tuple[dict[str, Any], ...]
    approved_r009_review_bindings: tuple[dict[str, Any], ...]
    rejected_r010_assignment_binding: dict[str, Any]
    approved_r011_review_bindings: tuple[dict[str, Any], ...]
    approved_r012_review_bindings: tuple[dict[str, Any], ...]
    rejected_r013_assignment_binding: dict[str, Any]
    source_catalog_bindings: tuple[dict[str, Any], ...]


def rejected_r001_assignment_binding(root: Path = ROOT) -> dict[str, Any]:
    raw = _read_regular(
        root,
        REJECTED_R001_ASSIGNMENT_REL,
        allowed_modes=frozenset({0o600}),
    )
    require(
        len(raw) == REJECTED_R001_ASSIGNMENT_BYTE_LENGTH
        and bytes_sha256(raw) == REJECTED_R001_ASSIGNMENT_SHA256,
        "rejected R001 assignment differs",
    )
    value = _strict_json(raw, REJECTED_R001_ASSIGNMENT_REL.as_posix())
    require(
        value.get("round_id")
        == "WS-FP046-R002-SEQ78-79-RECOVERY-REVIEW-20260824-R001"
        and value.get("document_id")
        == "WS-FP046-R002-SEQ78-79-RECOVERY-REVIEW-ASSIGNMENT-20260824-R001",
        "rejected R001 assignment identity differs",
    )
    for relative in (REJECTED_R001_RESULT_REL, REJECTED_R001_INDEPENDENT_REL):
        try:
            (root / relative).lstat()
        except FileNotFoundError:
            continue
        raise ReviewError(f"rejected R001 output must remain absent: {relative}")
    return binding(REJECTED_R001_ASSIGNMENT_REL, raw)


def rejected_r010_assignment_binding(root: Path = ROOT) -> dict[str, Any]:
    raw = _read_regular(
        root,
        REJECTED_R010_ASSIGNMENT_REL,
        allowed_modes=frozenset({0o600}),
    )
    require(
        len(raw) == REJECTED_R010_ASSIGNMENT_BYTE_LENGTH
        and bytes_sha256(raw) == REJECTED_R010_ASSIGNMENT_SHA256,
        "rejected R010 assignment differs",
    )
    value = _strict_json(raw, REJECTED_R010_ASSIGNMENT_REL.as_posix())
    require(
        value.get("round_id")
        == "WS-FP046-R002-SEQ82-83-RECOVERY-REVIEW-20260825-R010"
        and value.get("document_id")
        == "WS-FP046-R002-SEQ82-83-RECOVERY-REVIEW-ASSIGNMENT-20260825-R010",
        "rejected R010 assignment identity differs",
    )
    for relative in (REJECTED_R010_RESULT_REL, REJECTED_R010_INDEPENDENT_REL):
        try:
            (root / relative).lstat()
        except FileNotFoundError:
            continue
        raise ReviewError(f"rejected R010 output must remain absent: {relative}")
    return binding(REJECTED_R010_ASSIGNMENT_REL, raw)


def rejected_r013_assignment_binding(root: Path = ROOT) -> dict[str, Any]:
    raw = _read_regular(
        root,
        REJECTED_R013_ASSIGNMENT_REL,
        allowed_modes=frozenset({0o600}),
    )
    require(
        len(raw) == REJECTED_R013_ASSIGNMENT_BYTE_LENGTH
        and bytes_sha256(raw) == REJECTED_R013_ASSIGNMENT_SHA256,
        "rejected R013 assignment differs",
    )
    value = _strict_json(raw, REJECTED_R013_ASSIGNMENT_REL.as_posix())
    require(
        value.get("round_id")
        == "WS-FP046-R002-SEQ84-85-RECOVERY-REVIEW-20260825-R013"
        and value.get("document_id")
        == "WS-FP046-R002-SEQ84-85-RECOVERY-REVIEW-ASSIGNMENT-20260825-R013",
        "rejected R013 assignment identity differs",
    )
    for relative in (REJECTED_R013_RESULT_REL, REJECTED_R013_INDEPENDENT_REL):
        try:
            (root / relative).lstat()
        except FileNotFoundError:
            continue
        raise ReviewError(f"rejected R013 output must remain absent: {relative}")
    return binding(REJECTED_R013_ASSIGNMENT_REL, raw)


def approved_r002_review_bindings(
    root: Path = ROOT,
) -> tuple[dict[str, Any], ...]:
    raw = tuple(
        _read_regular(root, path, allowed_modes=frozenset({0o600}))
        for path in APPROVED_R002_REVIEW_PATHS
    )
    for path, content in zip(APPROVED_R002_REVIEW_PATHS, raw, strict=True):
        digest, byte_length = APPROVED_R002_REVIEW_PINS[path]
        require(
            len(content) == byte_length and bytes_sha256(content) == digest,
            f"approved R002 review predecessor differs: {path}",
        )
    assignment, result, independent = (
        _strict_json(content, path.as_posix())
        for path, content in zip(APPROVED_R002_REVIEW_PATHS, raw, strict=True)
    )
    require(
        assignment.get("round_id")
        == "WS-FP046-R002-SEQ78-79-RECOVERY-REVIEW-20260824-R002"
        and result.get("round_id") == assignment.get("round_id")
        and independent.get("round_id") == assignment.get("round_id")
        and result.get("decision") == "APPROVED"
        and result.get("findings")
        == {"blocking": [], "major_open": [], "minor_open": []}
        and independent.get("decision") == "APPROVED"
        and independent.get("status") == "PASS"
        and independent.get("findings") == result.get("findings")
        and strict_json_equal(result.get("reviewer"), assignment.get("reviewer"))
        and strict_json_equal(independent.get("reviewer"), assignment.get("reviewer"))
        and strict_json_equal(
            result.get("assignment_binding"),
            binding(APPROVED_R002_REVIEW_PATHS[0], raw[0]),
        )
        and strict_json_equal(
            independent.get("assignment_provenance"),
            binding(APPROVED_R002_REVIEW_PATHS[0], raw[0]),
        )
        and strict_json_equal(
            independent.get("review_result_provenance"),
            binding(APPROVED_R002_REVIEW_PATHS[1], raw[1]),
        ),
        "approved R002 review predecessor relation differs",
    )
    return tuple(
        binding(path, content)
        for path, content in zip(APPROVED_R002_REVIEW_PATHS, raw, strict=True)
    )


def approved_r003_review_bindings(
    root: Path = ROOT,
) -> tuple[dict[str, Any], ...]:
    raw = tuple(
        _read_regular(root, path, allowed_modes=frozenset({0o600}))
        for path in APPROVED_R003_REVIEW_PATHS
    )
    for path, content in zip(APPROVED_R003_REVIEW_PATHS, raw, strict=True):
        digest, byte_length = APPROVED_R003_REVIEW_PINS[path]
        require(
            len(content) == byte_length and bytes_sha256(content) == digest,
            f"approved R003 review predecessor differs: {path}",
        )
    assignment, result, independent = (
        _strict_json(content, path.as_posix())
        for path, content in zip(APPROVED_R003_REVIEW_PATHS, raw, strict=True)
    )
    require(
        assignment.get("round_id")
        == "WS-FP046-R002-SEQ78-79-RECOVERY-REVIEW-20260824-R003"
        and result.get("round_id") == assignment.get("round_id")
        and independent.get("round_id") == assignment.get("round_id")
        and result.get("decision") == "APPROVED"
        and result.get("findings")
        == {"blocking": [], "major_open": [], "minor_open": []}
        and independent.get("decision") == "APPROVED"
        and independent.get("status") == "PASS"
        and independent.get("findings") == result.get("findings")
        and strict_json_equal(result.get("reviewer"), assignment.get("reviewer"))
        and strict_json_equal(independent.get("reviewer"), assignment.get("reviewer"))
        and strict_json_equal(
            result.get("assignment_binding"),
            binding(APPROVED_R003_REVIEW_PATHS[0], raw[0]),
        )
        and strict_json_equal(
            independent.get("assignment_provenance"),
            binding(APPROVED_R003_REVIEW_PATHS[0], raw[0]),
        )
        and strict_json_equal(
            independent.get("review_result_provenance"),
            binding(APPROVED_R003_REVIEW_PATHS[1], raw[1]),
        ),
        "approved R003 review predecessor relation differs",
    )
    return tuple(
        binding(path, content)
        for path, content in zip(APPROVED_R003_REVIEW_PATHS, raw, strict=True)
    )


def approved_r004_review_bindings(
    root: Path = ROOT,
) -> tuple[dict[str, Any], ...]:
    raw = tuple(
        _read_regular(root, path, allowed_modes=frozenset({0o600}))
        for path in APPROVED_R004_REVIEW_PATHS
    )
    for path, content in zip(APPROVED_R004_REVIEW_PATHS, raw, strict=True):
        digest, byte_length = APPROVED_R004_REVIEW_PINS[path]
        require(
            len(content) == byte_length and bytes_sha256(content) == digest,
            f"approved R004 review predecessor differs: {path}",
        )
    assignment, result, independent = (
        _strict_json(content, path.as_posix())
        for path, content in zip(APPROVED_R004_REVIEW_PATHS, raw, strict=True)
    )
    require(
        assignment.get("round_id")
        == "WS-FP046-R002-SEQ78-79-RECOVERY-REVIEW-20260824-R004"
        and result.get("round_id") == assignment.get("round_id")
        and independent.get("round_id") == assignment.get("round_id")
        and result.get("decision") == "APPROVED"
        and result.get("findings")
        == {"blocking": [], "major_open": [], "minor_open": []}
        and independent.get("decision") == "APPROVED"
        and independent.get("status") == "PASS"
        and independent.get("findings") == result.get("findings")
        and strict_json_equal(result.get("reviewer"), assignment.get("reviewer"))
        and strict_json_equal(independent.get("reviewer"), assignment.get("reviewer"))
        and strict_json_equal(
            result.get("assignment_binding"),
            binding(APPROVED_R004_REVIEW_PATHS[0], raw[0]),
        )
        and strict_json_equal(
            independent.get("assignment_provenance"),
            binding(APPROVED_R004_REVIEW_PATHS[0], raw[0]),
        )
        and strict_json_equal(
            independent.get("review_result_provenance"),
            binding(APPROVED_R004_REVIEW_PATHS[1], raw[1]),
        ),
        "approved R004 review predecessor relation differs",
    )
    return tuple(
        binding(path, content)
        for path, content in zip(APPROVED_R004_REVIEW_PATHS, raw, strict=True)
    )


def approved_r005_review_bindings(
    root: Path = ROOT,
) -> tuple[dict[str, Any], ...]:
    raw = tuple(
        _read_regular(root, path, allowed_modes=frozenset({0o600}))
        for path in APPROVED_R005_REVIEW_PATHS
    )
    for path, content in zip(APPROVED_R005_REVIEW_PATHS, raw, strict=True):
        digest, byte_length = APPROVED_R005_REVIEW_PINS[path]
        require(
            len(content) == byte_length and bytes_sha256(content) == digest,
            f"approved R005 review predecessor differs: {path}",
        )
    assignment, result, independent = (
        _strict_json(content, path.as_posix())
        for path, content in zip(APPROVED_R005_REVIEW_PATHS, raw, strict=True)
    )
    require(
        assignment.get("round_id")
        == "WS-FP046-R002-SEQ79-80-RECOVERY-REVIEW-20260824-R005"
        and result.get("round_id") == assignment.get("round_id")
        and independent.get("round_id") == assignment.get("round_id")
        and result.get("decision") == "APPROVED"
        and result.get("findings")
        == {"blocking": [], "major_open": [], "minor_open": []}
        and independent.get("decision") == "APPROVED"
        and independent.get("status") == "PASS"
        and independent.get("findings") == result.get("findings")
        and strict_json_equal(result.get("reviewer"), assignment.get("reviewer"))
        and strict_json_equal(independent.get("reviewer"), assignment.get("reviewer"))
        and strict_json_equal(
            result.get("assignment_binding"),
            binding(APPROVED_R005_REVIEW_PATHS[0], raw[0]),
        )
        and strict_json_equal(
            independent.get("assignment_provenance"),
            binding(APPROVED_R005_REVIEW_PATHS[0], raw[0]),
        )
        and strict_json_equal(
            independent.get("review_result_provenance"),
            binding(APPROVED_R005_REVIEW_PATHS[1], raw[1]),
        ),
        "approved R005 review predecessor relation differs",
    )
    return tuple(
        binding(path, content)
        for path, content in zip(APPROVED_R005_REVIEW_PATHS, raw, strict=True)
    )


def approved_r006_review_bindings(
    root: Path = ROOT,
) -> tuple[dict[str, Any], ...]:
    raw = tuple(
        _read_regular(root, path, allowed_modes=frozenset({0o600}))
        for path in APPROVED_R006_REVIEW_PATHS
    )
    for path, content in zip(APPROVED_R006_REVIEW_PATHS, raw, strict=True):
        digest, byte_length = APPROVED_R006_REVIEW_PINS[path]
        require(
            len(content) == byte_length and bytes_sha256(content) == digest,
            f"approved R006 review predecessor differs: {path}",
        )
    assignment, result, independent = (
        _strict_json(content, path.as_posix())
        for path, content in zip(APPROVED_R006_REVIEW_PATHS, raw, strict=True)
    )
    require(
        assignment.get("round_id")
        == "WS-FP046-R002-SEQ79-80-RECOVERY-REVIEW-20260824-R006"
        and result.get("round_id") == assignment.get("round_id")
        and independent.get("round_id") == assignment.get("round_id")
        and result.get("decision") == "APPROVED"
        and result.get("findings")
        == {"blocking": [], "major_open": [], "minor_open": []}
        and independent.get("decision") == "APPROVED"
        and independent.get("status") == "PASS"
        and independent.get("findings") == result.get("findings")
        and strict_json_equal(result.get("reviewer"), assignment.get("reviewer"))
        and strict_json_equal(independent.get("reviewer"), assignment.get("reviewer"))
        and strict_json_equal(
            result.get("assignment_binding"),
            binding(APPROVED_R006_REVIEW_PATHS[0], raw[0]),
        )
        and strict_json_equal(
            independent.get("assignment_provenance"),
            binding(APPROVED_R006_REVIEW_PATHS[0], raw[0]),
        )
        and strict_json_equal(
            independent.get("review_result_provenance"),
            binding(APPROVED_R006_REVIEW_PATHS[1], raw[1]),
        ),
        "approved R006 review predecessor relation differs",
    )
    return tuple(
        binding(path, content)
        for path, content in zip(APPROVED_R006_REVIEW_PATHS, raw, strict=True)
    )


def approved_r007_review_bindings(
    root: Path = ROOT,
) -> tuple[dict[str, Any], ...]:
    raw = tuple(
        _read_regular(root, path, allowed_modes=frozenset({0o600}))
        for path in APPROVED_R007_REVIEW_PATHS
    )
    for path, content in zip(APPROVED_R007_REVIEW_PATHS, raw, strict=True):
        digest, byte_length = APPROVED_R007_REVIEW_PINS[path]
        require(
            len(content) == byte_length and bytes_sha256(content) == digest,
            f"approved R007 review predecessor differs: {path}",
        )
    assignment, result, independent = (
        _strict_json(content, path.as_posix())
        for path, content in zip(APPROVED_R007_REVIEW_PATHS, raw, strict=True)
    )
    require(
        assignment.get("round_id")
        == "WS-FP046-R002-SEQ79-80-RECOVERY-REVIEW-20260824-R007"
        and result.get("round_id") == assignment.get("round_id")
        and independent.get("round_id") == assignment.get("round_id")
        and result.get("decision") == "APPROVED"
        and result.get("findings")
        == {"blocking": [], "major_open": [], "minor_open": []}
        and independent.get("decision") == "APPROVED"
        and independent.get("status") == "PASS"
        and independent.get("findings") == result.get("findings")
        and strict_json_equal(result.get("reviewer"), assignment.get("reviewer"))
        and strict_json_equal(independent.get("reviewer"), assignment.get("reviewer"))
        and strict_json_equal(
            result.get("assignment_binding"),
            binding(APPROVED_R007_REVIEW_PATHS[0], raw[0]),
        )
        and strict_json_equal(
            independent.get("assignment_provenance"),
            binding(APPROVED_R007_REVIEW_PATHS[0], raw[0]),
        )
        and strict_json_equal(
            independent.get("review_result_provenance"),
            binding(APPROVED_R007_REVIEW_PATHS[1], raw[1]),
        ),
        "approved R007 review predecessor relation differs",
    )
    return tuple(
        binding(path, content)
        for path, content in zip(APPROVED_R007_REVIEW_PATHS, raw, strict=True)
    )


def approved_r008_review_bindings(
    root: Path = ROOT,
) -> tuple[dict[str, Any], ...]:
    raw = tuple(
        _read_regular(root, path, allowed_modes=frozenset({0o600}))
        for path in APPROVED_R008_REVIEW_PATHS
    )
    for path, content in zip(APPROVED_R008_REVIEW_PATHS, raw, strict=True):
        digest, byte_length = APPROVED_R008_REVIEW_PINS[path]
        require(
            len(content) == byte_length and bytes_sha256(content) == digest,
            f"approved R008 review predecessor differs: {path}",
        )
    assignment, result, independent = (
        _strict_json(content, path.as_posix())
        for path, content in zip(APPROVED_R008_REVIEW_PATHS, raw, strict=True)
    )
    require(
        assignment.get("round_id")
        == "WS-FP046-R002-SEQ80-81-RECOVERY-REVIEW-20260824-R008"
        and result.get("round_id") == assignment.get("round_id")
        and independent.get("round_id") == assignment.get("round_id")
        and result.get("decision") == "APPROVED"
        and result.get("findings")
        == {"blocking": [], "major_open": [], "minor_open": []}
        and independent.get("decision") == "APPROVED"
        and independent.get("status") == "PASS"
        and independent.get("findings") == result.get("findings")
        and strict_json_equal(result.get("reviewer"), assignment.get("reviewer"))
        and strict_json_equal(independent.get("reviewer"), assignment.get("reviewer"))
        and strict_json_equal(
            result.get("assignment_binding"),
            binding(APPROVED_R008_REVIEW_PATHS[0], raw[0]),
        )
        and strict_json_equal(
            independent.get("assignment_provenance"),
            binding(APPROVED_R008_REVIEW_PATHS[0], raw[0]),
        )
        and strict_json_equal(
            independent.get("review_result_provenance"),
            binding(APPROVED_R008_REVIEW_PATHS[1], raw[1]),
        ),
        "approved R008 review predecessor relation differs",
    )
    return tuple(
        binding(path, content)
        for path, content in zip(APPROVED_R008_REVIEW_PATHS, raw, strict=True)
    )


def approved_r009_review_bindings(
    root: Path = ROOT,
) -> tuple[dict[str, Any], ...]:
    raw = tuple(
        _read_regular(root, path, allowed_modes=frozenset({0o600}))
        for path in APPROVED_R009_REVIEW_PATHS
    )
    for path, content in zip(APPROVED_R009_REVIEW_PATHS, raw, strict=True):
        digest, byte_length = APPROVED_R009_REVIEW_PINS[path]
        require(
            len(content) == byte_length and bytes_sha256(content) == digest,
            f"approved R009 review predecessor differs: {path}",
        )
    assignment, result, independent = (
        _strict_json(content, path.as_posix())
        for path, content in zip(APPROVED_R009_REVIEW_PATHS, raw, strict=True)
    )
    require(
        assignment.get("round_id")
        == "WS-FP046-R002-SEQ81-82-RECOVERY-REVIEW-20260824-R009"
        and result.get("round_id") == assignment.get("round_id")
        and independent.get("round_id") == assignment.get("round_id")
        and result.get("decision") == "APPROVED"
        and result.get("findings")
        == {"blocking": [], "major_open": [], "minor_open": []}
        and independent.get("decision") == "APPROVED"
        and independent.get("status") == "PASS"
        and independent.get("findings") == result.get("findings")
        and strict_json_equal(result.get("reviewer"), assignment.get("reviewer"))
        and strict_json_equal(independent.get("reviewer"), assignment.get("reviewer"))
        and strict_json_equal(
            result.get("assignment_binding"),
            binding(APPROVED_R009_REVIEW_PATHS[0], raw[0]),
        )
        and strict_json_equal(
            independent.get("assignment_provenance"),
            binding(APPROVED_R009_REVIEW_PATHS[0], raw[0]),
        )
        and strict_json_equal(
            independent.get("review_result_provenance"),
            binding(APPROVED_R009_REVIEW_PATHS[1], raw[1]),
        ),
        "approved R009 review predecessor relation differs",
    )
    return tuple(
        binding(path, content)
        for path, content in zip(APPROVED_R009_REVIEW_PATHS, raw, strict=True)
    )


def approved_r011_review_bindings(
    root: Path = ROOT,
) -> tuple[dict[str, Any], ...]:
    raw = tuple(
        _read_regular(root, path, allowed_modes=frozenset({0o600}))
        for path in APPROVED_R011_REVIEW_PATHS
    )
    for path, content in zip(APPROVED_R011_REVIEW_PATHS, raw, strict=True):
        digest, byte_length = APPROVED_R011_REVIEW_PINS[path]
        require(
            len(content) == byte_length and bytes_sha256(content) == digest,
            f"approved R011 review predecessor differs: {path}",
        )
    assignment, result, independent = (
        _strict_json(content, path.as_posix())
        for path, content in zip(APPROVED_R011_REVIEW_PATHS, raw, strict=True)
    )
    require(
        assignment.get("round_id")
        == "WS-FP046-R002-SEQ82-83-RECOVERY-REVIEW-20260825-R011"
        and result.get("round_id") == assignment.get("round_id")
        and independent.get("round_id") == assignment.get("round_id")
        and result.get("decision") == "APPROVED"
        and result.get("findings")
        == {"blocking": [], "major_open": [], "minor_open": []}
        and independent.get("decision") == "APPROVED"
        and independent.get("status") == "PASS"
        and independent.get("findings") == result.get("findings")
        and strict_json_equal(result.get("reviewer"), assignment.get("reviewer"))
        and strict_json_equal(independent.get("reviewer"), assignment.get("reviewer"))
        and strict_json_equal(
            result.get("assignment_binding"),
            binding(APPROVED_R011_REVIEW_PATHS[0], raw[0]),
        )
        and strict_json_equal(
            independent.get("assignment_provenance"),
            binding(APPROVED_R011_REVIEW_PATHS[0], raw[0]),
        )
        and strict_json_equal(
            independent.get("review_result_provenance"),
            binding(APPROVED_R011_REVIEW_PATHS[1], raw[1]),
        ),
        "approved R011 review predecessor relation differs",
    )
    return tuple(
        binding(path, content)
        for path, content in zip(APPROVED_R011_REVIEW_PATHS, raw, strict=True)
    )


def approved_r012_review_bindings(
    root: Path = ROOT,
) -> tuple[dict[str, Any], ...]:
    raw = tuple(
        _read_regular(root, path, allowed_modes=frozenset({0o600}))
        for path in APPROVED_R012_REVIEW_PATHS
    )
    for path, content in zip(APPROVED_R012_REVIEW_PATHS, raw, strict=True):
        digest, byte_length = APPROVED_R012_REVIEW_PINS[path]
        require(
            len(content) == byte_length and bytes_sha256(content) == digest,
            f"approved R012 review predecessor differs: {path}",
        )
    assignment, result, independent = (
        _strict_json(content, path.as_posix())
        for path, content in zip(APPROVED_R012_REVIEW_PATHS, raw, strict=True)
    )
    require(
        assignment.get("round_id")
        == "WS-FP046-R002-SEQ83-84-RECOVERY-REVIEW-20260825-R012"
        and result.get("round_id") == assignment.get("round_id")
        and independent.get("round_id") == assignment.get("round_id")
        and result.get("decision") == "APPROVED"
        and result.get("findings")
        == {"blocking": [], "major_open": [], "minor_open": []}
        and independent.get("decision") == "APPROVED"
        and independent.get("status") == "PASS"
        and independent.get("findings") == result.get("findings")
        and strict_json_equal(result.get("reviewer"), assignment.get("reviewer"))
        and strict_json_equal(independent.get("reviewer"), assignment.get("reviewer"))
        and strict_json_equal(
            result.get("assignment_binding"),
            binding(APPROVED_R012_REVIEW_PATHS[0], raw[0]),
        )
        and strict_json_equal(
            independent.get("assignment_provenance"),
            binding(APPROVED_R012_REVIEW_PATHS[0], raw[0]),
        )
        and strict_json_equal(
            independent.get("review_result_provenance"),
            binding(APPROVED_R012_REVIEW_PATHS[1], raw[1]),
        ),
        "approved R012 review predecessor relation differs",
    )
    return tuple(
        binding(path, content)
        for path, content in zip(APPROVED_R012_REVIEW_PATHS, raw, strict=True)
    )


def predecessor_control_cohort(root: Path = ROOT) -> tuple[dict[str, Any], ...]:
    preserved_review_bindings(root)
    raw = _read_regular(
        root,
        R006_REVIEW_PATHS[0],
        allowed_modes=frozenset({0o644}),
    )
    assignment = _strict_json(raw, R006_REVIEW_PATHS[0].as_posix())
    scope = assignment["review_scope"]
    cohort = scope["reviewed_current_control_cohort"]
    require(
        type(cohort) is list
        and len(cohort) == 31
        and scope["reviewed_current_control_cohort_sha256"]
        == PREDECESSOR_CONTROL_COHORT_SHA256,
        "R006 predecessor control cohort differs",
    )
    return tuple(copy.deepcopy(cohort))


def prepare_review_context(
    root: Path = ROOT,
    *,
    require_complete_control_cohort: bool = True,
    require_live_snapshot: bool = True,
    source_catalog_bindings_override: Sequence[Mapping[str, Any]] | None = None,
) -> ReviewContext:
    root = root.resolve(strict=True)
    cohort = current_control_cohort(root, require_complete=require_complete_control_cohort)
    predecessor_cohort = predecessor_control_cohort(root)
    present = {row["path"] for row in cohort}
    before_by_path = {row["path"]: row for row in predecessor_cohort}
    after_by_path = {row["path"]: row for row in cohort}
    missing = tuple(path.as_posix() for path in ADDED_CONTROL_PATHS if path.as_posix() not in present)
    successors = tuple(
        {
            "path": path.as_posix(),
            "predecessor": copy.deepcopy(before_by_path[path.as_posix()]),
            "successor": copy.deepcopy(after_by_path[path.as_posix()]),
        }
        for path in MODIFIED_CONTROL_PATHS
        if path.as_posix() in after_by_path
        and before_by_path[path.as_posix()] != after_by_path[path.as_posix()]
    )
    unready_modified = tuple(
        path.as_posix()
        for path in MODIFIED_CONTROL_PATHS
        if path.as_posix() not in after_by_path
        or before_by_path[path.as_posix()] == after_by_path[path.as_posix()]
    )
    added = tuple(
        copy.deepcopy(after_by_path[path.as_posix()])
        for path in ADDED_CONTROL_PATHS
        if path.as_posix() in after_by_path
    )
    if require_complete_control_cohort:
        require(
            not missing
            and not unready_modified
            and len(successors) == 9
            and len(added) == 6,
            "recovery control delta is not exactly nine successors and six additions",
        )
    return ReviewContext(
        source_checkpoint=source_checkpoint_binding(root),
        review_source_checkpoint=review_source_checkpoint_binding(root),
        predecessor_review_bindings=preserved_review_bindings(root),
        predecessor_control_cohort=predecessor_cohort,
        failed_gate_attempt=(
            failed_gate_attempt_binding(root)
            if require_live_snapshot
            else copy.deepcopy(STORED_FAILED_GATE_ATTEMPT)
        ),
        failed_gate_attempt_002=(
            failed_gate_attempt_002_binding(root)
            if require_live_snapshot
            else copy.deepcopy(STORED_FAILED_GATE_ATTEMPT_002)
        ),
        failed_gate_attempt_003=(
            failed_gate_attempt_003_binding(root)
            if require_live_snapshot
            else copy.deepcopy(STORED_FAILED_GATE_ATTEMPT_003)
        ),
        passed_gate_attempt_004=(
            passed_gate_attempt_004_binding(root)
            if require_live_snapshot
            else copy.deepcopy(STORED_PASSED_GATE_ATTEMPT_004)
        ),
        authorization_binding=_static_binding(
            root,
            AUTHORIZATION_REL,
            AUTHORIZATION_SHA256,
            AUTHORIZATION_BYTE_LENGTH,
        ),
        contract_binding=_static_binding(root, CONTRACT_REL, CONTRACT_SHA256, CONTRACT_BYTE_LENGTH),
        current_control_cohort=cohort,
        control_code_successors=successors,
        added_control_code_bindings=added,
        missing_add_only_paths=missing,
        unready_modified_paths=unready_modified,
        rejected_r001_assignment_binding=rejected_r001_assignment_binding(root),
        approved_r002_review_bindings=approved_r002_review_bindings(root),
        approved_r003_review_bindings=approved_r003_review_bindings(root),
        approved_r004_review_bindings=approved_r004_review_bindings(root),
        approved_r005_review_bindings=approved_r005_review_bindings(root),
        approved_r006_review_bindings=approved_r006_review_bindings(root),
        approved_r007_review_bindings=approved_r007_review_bindings(root),
        approved_r008_review_bindings=approved_r008_review_bindings(root),
        approved_r009_review_bindings=approved_r009_review_bindings(root),
        rejected_r010_assignment_binding=rejected_r010_assignment_binding(root),
        approved_r011_review_bindings=approved_r011_review_bindings(root),
        approved_r012_review_bindings=approved_r012_review_bindings(root),
        rejected_r013_assignment_binding=rejected_r013_assignment_binding(root),
        source_catalog_bindings=(
            tuple(copy.deepcopy(list(source_catalog_bindings_override)))
            if source_catalog_bindings_override is not None
            else tuple(_static_binding(root, path) for path in CATALOG_PATHS)
        ),
    )


def review_scope(context: ReviewContext) -> dict[str, Any]:
    return {
        "source_checkpoint": copy.deepcopy(context.source_checkpoint),
        "review_source_checkpoint": copy.deepcopy(
            context.review_source_checkpoint
        ),
        "approved_r006_exact_predecessor": copy.deepcopy(list(context.predecessor_review_bindings)),
        "approved_r006_control_cohort": copy.deepcopy(
            list(context.predecessor_control_cohort)
        ),
        "approved_r006_control_cohort_sha256": (
            PREDECESSOR_CONTROL_COHORT_SHA256
        ),
        "rejected_r001_assignment_binding": copy.deepcopy(
            context.rejected_r001_assignment_binding
        ),
        "r001_supersession_reason_code": R001_SUPERSESSION_REASON_CODE,
        "rejected_r001_result_and_independent_absent": True,
        "approved_r002_exact_predecessor": copy.deepcopy(
            list(context.approved_r002_review_bindings)
        ),
        "r002_supersession_reason_code": R002_SUPERSESSION_REASON_CODE,
        "approved_r003_exact_predecessor": copy.deepcopy(
            list(context.approved_r003_review_bindings)
        ),
        "r003_supersession_reason_code": R003_SUPERSESSION_REASON_CODE,
        "approved_r004_exact_predecessor": copy.deepcopy(
            list(context.approved_r004_review_bindings)
        ),
        "r004_supersession_reason_code": R004_SUPERSESSION_REASON_CODE,
        "approved_r005_exact_predecessor": copy.deepcopy(
            list(context.approved_r005_review_bindings)
        ),
        "r005_supersession_reason_code": R005_SUPERSESSION_REASON_CODE,
        "approved_r006_exact_predecessor": copy.deepcopy(
            list(context.approved_r006_review_bindings)
        ),
        "r006_supersession_reason_code": R006_SUPERSESSION_REASON_CODE,
        "approved_r007_exact_predecessor": copy.deepcopy(
            list(context.approved_r007_review_bindings)
        ),
        "r007_supersession_reason_code": R007_SUPERSESSION_REASON_CODE,
        "approved_r008_exact_predecessor": copy.deepcopy(
            list(context.approved_r008_review_bindings)
        ),
        "r008_supersession_reason_code": R008_SUPERSESSION_REASON_CODE,
        "approved_r009_exact_predecessor": copy.deepcopy(
            list(context.approved_r009_review_bindings)
        ),
        "r009_supersession_reason_code": R009_SUPERSESSION_REASON_CODE,
        "rejected_r010_assignment_binding": copy.deepcopy(
            context.rejected_r010_assignment_binding
        ),
        "r010_supersession_reason_code": R010_SUPERSESSION_REASON_CODE,
        "rejected_r010_result_and_independent_absent": True,
        "approved_r011_exact_predecessor": copy.deepcopy(
            list(context.approved_r011_review_bindings)
        ),
        "r011_supersession_reason_code": R011_SUPERSESSION_REASON_CODE,
        "approved_r012_exact_predecessor": copy.deepcopy(
            list(context.approved_r012_review_bindings)
        ),
        "r012_supersession_reason_code": R012_SUPERSESSION_REASON_CODE,
        "rejected_r013_assignment_binding": copy.deepcopy(
            context.rejected_r013_assignment_binding
        ),
        "r013_supersession_reason_code": R013_SUPERSESSION_REASON_CODE,
        "rejected_r013_result_and_independent_absent": True,
        "source_catalog_bindings": copy.deepcopy(
            list(context.source_catalog_bindings)
        ),
        "failed_gate_attempt": copy.deepcopy(context.failed_gate_attempt),
        "failed_gate_attempt_002": copy.deepcopy(
            context.failed_gate_attempt_002
        ),
        "failed_gate_attempt_003": copy.deepcopy(
            context.failed_gate_attempt_003
        ),
        "passed_gate_attempt_004": copy.deepcopy(
            context.passed_gate_attempt_004
        ),
        "authorization_binding": copy.deepcopy(context.authorization_binding),
        "initial_start_gate_contract_r002_binding": copy.deepcopy(context.contract_binding),
        "current_control_cohort": copy.deepcopy(list(context.current_control_cohort)),
        "current_control_path_count": len(context.current_control_cohort),
        "reviewed_control_code_successors": copy.deepcopy(
            list(context.control_code_successors)
        ),
        "reviewed_control_code_successor_path_count": len(
            context.control_code_successors
        ),
        "reviewed_added_control_code_bindings": copy.deepcopy(
            list(context.added_control_code_bindings)
        ),
        "reviewed_added_control_code_path_count": len(
            context.added_control_code_bindings
        ),
        "modified_control_paths": [path.as_posix() for path in MODIFIED_CONTROL_PATHS],
        "add_only_control_paths": [path.as_posix() for path in ADDED_CONTROL_PATHS],
        "missing_add_only_paths": list(context.missing_add_only_paths),
        "unready_modified_paths": list(context.unready_modified_paths),
        "projected_transition": copy.deepcopy(PROJECTED_TRANSITION),
        "session_artifact_authority": "HISTORICAL_R006_PREDECESSOR_ONLY",
        "live_session_artifact_paths": [],
        "acceptance": {
            "source_seq77_is_preserved": True,
            "approved_r006_is_exact_predecessor": True,
            "failed_attempt_has_only_exact_01_log": True,
            "failed_attempt_has_no_receipt_or_later_logs": True,
            "failed_attempt_002_is_exact_empty_private_namespace": True,
            "seq78_predecessor_correction_is_preserved": True,
            "seq79_predecessor_correction_is_preserved": True,
            "seq80_predecessor_correction_is_preserved": True,
            "seq81_predecessor_correction_is_preserved": True,
            "seq82_predecessor_correction_is_preserved": True,
            "seq83_predecessor_correction_is_preserved": True,
            "seq84_is_ready_to_ready_zero_credit": True,
            "r002_contract_is_retained": True,
            "gate_pass_004_is_exact_and_unconsumed": True,
            "seq85_uses_unused_event_id_005_after_exact_gate_pass": True,
            "mutable_session_artifacts_are_not_live_authority": True,
            "approved_r002_review_is_exact_predecessor": True,
            "r002_live_git_path_baseline_is_superseded": True,
            "approved_r003_review_is_exact_predecessor": True,
            "r003_direct_gate_evidence_snapshot_is_superseded": True,
            "approved_r004_review_is_exact_predecessor": True,
            "r004_post_transition_source_binding_is_superseded": True,
            "approved_r005_review_is_exact_predecessor": True,
            "r005_seq79_dispatch_gap_is_superseded": True,
            "approved_r006_review_is_exact_predecessor": True,
            "r006_goal_consumer_predecessor_gap_is_superseded": True,
            "approved_r007_review_is_exact_predecessor": True,
            "r007_gate_scanner_gap_is_superseded": True,
            "rejected_r010_assignment_is_exact_predecessor": True,
            "r010_valid_add_only_prefix_regression_is_superseded": True,
            "approved_r011_review_is_exact_predecessor": True,
            "r011_non_atomic_catalog_publication_is_superseded": True,
            "approved_r012_review_is_exact_predecessor": True,
            "r012_stale_projected_start_activation_validator_is_superseded": True,
            "rejected_r013_assignment_is_exact_predecessor": True,
            "r013_assignment_only_review_interruption_is_superseded": True,
        },
    }


def build_assignment(context: ReviewContext, *, assigned_at: str) -> str:
    require(
        not context.missing_add_only_paths
        and not context.unready_modified_paths
        and len(context.control_code_successors) == 9
        and len(context.added_control_code_bindings) == 6,
        "recovery control cohort is incomplete",
    )
    datetime.fromisoformat(assigned_at)
    raw = json_text({
        "assigned_at": assigned_at,
        "assigner": {
            "agent_instance_id": ASSIGNER_ID,
            "canonical_task": ASSIGNER_TASK,
            "role": "INTERNAL_REVIEW_ASSIGNER",
        },
        "document_id": ASSIGNMENT_DOCUMENT_ID,
        "evidence_type": "FP046_R002_SEQ84_85_RECOVERY_REVIEW_ASSIGNMENT",
        "executor": {
            "agent_instance_id": EXECUTOR_ID,
            "canonical_task": EXECUTOR_TASK,
            "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
        },
        "goal_id": GOAL_ID,
        "review_boundary": {"external_independence_claimed": False, "formal_test_credit_added": 0, "product_implementation_credit_added": 0, "release_status": "NOT_ELIGIBLE"},
        "review_scope": review_scope(context),
        "round_id": ROUND_ID,
        "schema_version": "1.0",
        "reviewer": {
            "agent_instance_id": REVIEWER_ID,
            "canonical_task": REVIEWER_TASK,
            "role": "SEPARATE_INTERNAL_REVIEWER",
        },
    })
    validate_assignment(_strict_json(raw.encode(), "recovery assignment"), raw.encode(), context)
    return raw


def _expected_identity(
    role: str, agent_instance_id: str, canonical_task: str
) -> dict[str, str]:
    return {
        "agent_instance_id": agent_instance_id,
        "canonical_task": canonical_task,
        "role": role,
    }


def _parse_time(value: Any, label: str) -> datetime:
    require(type(value) is str, f"{label} is not a string")
    parsed = datetime.fromisoformat(value)
    require(
        parsed.tzinfo is not None and parsed.isoformat() == value,
        f"{label} is not canonical offset ISO-8601",
    )
    return parsed


def validate_assignment(value: Mapping[str, Any], raw: bytes, context: ReviewContext) -> None:
    require(raw == json_text(dict(value)).encode(), "recovery assignment is noncanonical")
    require(
        set(value)
        == {
            "assigned_at",
            "assigner",
            "document_id",
            "evidence_type",
            "executor",
            "goal_id",
            "review_boundary",
            "review_scope",
            "reviewer",
            "round_id",
            "schema_version",
        }
        and value.get("schema_version") == "1.0"
        and value.get("document_id") == ASSIGNMENT_DOCUMENT_ID
        and value.get("evidence_type") == "FP046_R002_SEQ84_85_RECOVERY_REVIEW_ASSIGNMENT"
        and value.get("goal_id") == GOAL_ID
        and value.get("round_id") == ROUND_ID
        and strict_json_equal(
            value.get("assigner"),
            _expected_identity(
                "INTERNAL_REVIEW_ASSIGNER", ASSIGNER_ID, ASSIGNER_TASK
            ),
        )
        and strict_json_equal(
            value.get("executor"),
            _expected_identity(
                "INTERNAL_IMPLEMENTATION_EXECUTOR",
                EXECUTOR_ID,
                EXECUTOR_TASK,
            ),
        )
        and strict_json_equal(
            value.get("reviewer"),
            _expected_identity(
                "SEPARATE_INTERNAL_REVIEWER", REVIEWER_ID, REVIEWER_TASK
            ),
        )
        and strict_json_equal(value.get("review_scope"), review_scope(context)),
        "recovery assignment differs",
    )
    assigner = value["assigner"]
    executor = value["executor"]
    reviewer = value["reviewer"]
    require(
        len({assigner["agent_instance_id"], executor["agent_instance_id"], reviewer["agent_instance_id"]}) == 3
        and len({assigner["canonical_task"], executor["canonical_task"], reviewer["canonical_task"]}) == 3,
        "recovery reviewer authority is not separate",
    )
    _parse_time(value.get("assigned_at"), "assigned_at")


def build_review_result(
    assignment_raw: bytes,
    *,
    reviewed_at: str,
    decision: str,
    findings: Mapping[str, Any],
) -> str:
    _parse_time(reviewed_at, "reviewed_at")
    assignment = _strict_json(assignment_raw, "recovery assignment")
    return json_text({
        "assignment_binding": binding(ASSIGNMENT_REL, assignment_raw),
        "decision": decision,
        "document_id": RESULT_DOCUMENT_ID,
        "evidence_type": "FP046_R002_SEQ84_85_RECOVERY_REVIEWER_AUTHORED_RESULT",
        "findings": copy.deepcopy(dict(findings)),
        "goal_id": GOAL_ID,
        "review_scope": copy.deepcopy(assignment["review_scope"]),
        "reviewed_at": reviewed_at,
        "reviewer": _expected_identity(
            "SEPARATE_INTERNAL_REVIEWER", REVIEWER_ID, REVIEWER_TASK
        ),
        "round_id": ROUND_ID,
        "schema_version": "1.0",
    })


def build_independent_review(assignment_raw: bytes, result_raw: bytes) -> str:
    result = _strict_json(result_raw, "recovery review result")
    return json_text({
        "assignment_provenance": binding(ASSIGNMENT_REL, assignment_raw),
        "decision": result["decision"],
        "document_id": INDEPENDENT_DOCUMENT_ID,
        "evidence_type": "FP046_R002_SEQ84_85_RECOVERY_INDEPENDENT_INTERNAL_REVIEW",
        "findings": copy.deepcopy(result["findings"]),
        "goal_id": GOAL_ID,
        "review_result_provenance": binding(RESULT_REL, result_raw),
        "review_scope": copy.deepcopy(result["review_scope"]),
        "reviewed_at": result["reviewed_at"],
        "reviewer": copy.deepcopy(result["reviewer"]),
        "round_id": ROUND_ID,
        "schema_version": "1.0",
        "status": "PASS" if result["decision"] == "APPROVED" else "REJECT",
    })


def _validate_findings(value: Any) -> dict[str, Any]:
    require(
        type(value) is dict
        and set(value) == {"blocking", "major_open", "minor_open"}
        and all(type(value[key]) is list for key in value),
        "review findings differ",
    )
    return value


def validate_review_result_candidate(
    result: Mapping[str, Any],
    result_raw: bytes,
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
    context: ReviewContext,
) -> None:
    validate_assignment(assignment, assignment_raw, context)
    require(result_raw == json_text(dict(result)).encode(), "review result is noncanonical")
    require(
        set(result)
        == {
            "assignment_binding",
            "decision",
            "document_id",
            "evidence_type",
            "findings",
            "goal_id",
            "review_scope",
            "reviewed_at",
            "reviewer",
            "round_id",
            "schema_version",
        }
        and result.get("schema_version") == "1.0"
        and result.get("document_id") == RESULT_DOCUMENT_ID
        and result.get("evidence_type") == "FP046_R002_SEQ84_85_RECOVERY_REVIEWER_AUTHORED_RESULT"
        and result.get("goal_id") == GOAL_ID
        and result.get("round_id") == ROUND_ID
        and strict_json_equal(result.get("reviewer"), assignment.get("reviewer"))
        and strict_json_equal(result.get("assignment_binding"), binding(ASSIGNMENT_REL, assignment_raw))
        and strict_json_equal(result.get("review_scope"), review_scope(context)),
        "review result identity or provenance differs",
    )
    findings = _validate_findings(result.get("findings"))
    decision = result.get("decision")
    require(decision in {"APPROVED", "REJECTED"}, "review result decision differs")
    require(
        decision == "REJECTED" or (not findings["blocking"] and not findings["major_open"]),
        "approved review retains open blocking or major findings",
    )
    require(
        _parse_time(result.get("reviewed_at"), "reviewed_at")
        >= _parse_time(assignment.get("assigned_at"), "assigned_at"),
        "review result predates assignment",
    )


def validate_independent_review_candidate(
    independent: Mapping[str, Any],
    independent_raw: bytes,
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
    result: Mapping[str, Any],
    result_raw: bytes,
    context: ReviewContext,
) -> None:
    validate_review_result_candidate(result, result_raw, assignment, assignment_raw, context)
    require(
        independent_raw == json_text(dict(independent)).encode(),
        "independent review is noncanonical",
    )
    require(
        set(independent)
        == {
            "assignment_provenance",
            "decision",
            "document_id",
            "evidence_type",
            "findings",
            "goal_id",
            "review_result_provenance",
            "review_scope",
            "reviewed_at",
            "reviewer",
            "round_id",
            "schema_version",
            "status",
        }
        and independent.get("schema_version") == "1.0"
        and independent.get("document_id") == INDEPENDENT_DOCUMENT_ID
        and independent.get("evidence_type") == "FP046_R002_SEQ84_85_RECOVERY_INDEPENDENT_INTERNAL_REVIEW"
        and independent.get("goal_id") == GOAL_ID
        and independent.get("round_id") == ROUND_ID
        and independent.get("status") == ("PASS" if result.get("decision") == "APPROVED" else "REJECT")
        and strict_json_equal(independent.get("decision"), result.get("decision"))
        and strict_json_equal(independent.get("findings"), result.get("findings"))
        and strict_json_equal(independent.get("reviewed_at"), result.get("reviewed_at"))
        and strict_json_equal(independent.get("reviewer"), assignment.get("reviewer"))
        and strict_json_equal(independent.get("assignment_provenance"), binding(ASSIGNMENT_REL, assignment_raw))
        and strict_json_equal(independent.get("review_result_provenance"), binding(RESULT_REL, result_raw))
        and strict_json_equal(independent.get("review_scope"), review_scope(context)),
        "independent review identity, findings, or provenance differs",
    )


def _validated_review(
    root: Path = ROOT,
    *,
    require_live_snapshot: bool = True,
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any], tuple[bytes, bytes, bytes]]:
    raw = tuple(_read_regular(root, path) for path in REVIEW_PATHS)
    assignment, result, independent = (
        _strict_json(value, path.as_posix()) for value, path in zip(raw, REVIEW_PATHS, strict=True)
    )
    scope = assignment.get("review_scope")
    catalog_bindings = (
        scope.get("source_catalog_bindings") if type(scope) is dict else None
    )
    require(
        type(catalog_bindings) is list
        and len(catalog_bindings) == len(CATALOG_PATHS),
        "review source catalog bindings differ",
    )
    context = prepare_review_context(
        root,
        require_live_snapshot=require_live_snapshot,
        source_catalog_bindings_override=catalog_bindings,
    )
    validate_assignment(assignment, raw[0], context)
    validate_review_result_candidate(result, raw[1], assignment, raw[0], context)
    validate_independent_review_candidate(
        independent, raw[2], assignment, raw[0], result, raw[1], context
    )
    require(
        result.get("decision") == "APPROVED"
        and result.get("findings") == {"blocking": [], "major_open": [], "minor_open": []}
        and independent.get("status") == "PASS"
        and independent.get("findings") == {"blocking": [], "major_open": [], "minor_open": []},
        "recovery review is not an approved finding-free triad",
    )
    return assignment, result, independent, raw


def transition_review_binding(
    root: Path = ROOT,
    *,
    require_live_snapshot: bool = True,
) -> dict[str, dict[str, Any]]:
    _, _, _, raw = _validated_review(
        root, require_live_snapshot=require_live_snapshot
    )
    return {
        "assignment": binding(ASSIGNMENT_REL, raw[0]),
        "review_result": binding(RESULT_REL, raw[1]),
        "independent_review": binding(INDEPENDENT_REL, raw[2]),
    }


def validated_reviewed_at(
    root: Path = ROOT,
    *,
    require_live_snapshot: bool = True,
) -> datetime:
    _, _, independent, _ = _validated_review(
        root, require_live_snapshot=require_live_snapshot
    )
    return datetime.fromisoformat(str(independent["reviewed_at"]))


def validate_post_review(
    root: Path = ROOT,
    *,
    require_live_snapshot: bool = True,
) -> ReviewContext:
    assignment, _, _, _ = _validated_review(
        root, require_live_snapshot=require_live_snapshot
    )
    return prepare_review_context(
        root,
        require_live_snapshot=require_live_snapshot,
        source_catalog_bindings_override=assignment["review_scope"][
            "source_catalog_bindings"
        ],
    )


class PostcommitUncertain(ReviewError):
    """The add-only name may be committed, so an automatic retry is unsafe."""


def _trusted_root(root: Path) -> Path:
    candidate = Path(os.path.abspath(os.fspath(root)))
    info = candidate.lstat()
    require(
        stat.S_ISDIR(info.st_mode)
        and not stat.S_ISLNK(info.st_mode)
        and info.st_uid == os.geteuid(),
        f"review root must be a current-owner non-symlink directory: {candidate}",
    )
    return candidate


def _directory_identity(info: os.stat_result) -> tuple[int, ...]:
    return (
        info.st_dev,
        info.st_ino,
        info.st_mode,
        info.st_nlink,
        info.st_uid,
        info.st_gid,
        info.st_size,
        info.st_mtime_ns,
        info.st_ctime_ns,
    )


def _file_identity(info: os.stat_result) -> tuple[int, ...]:
    return (
        info.st_dev,
        info.st_ino,
        info.st_mode,
        info.st_nlink,
        info.st_uid,
        info.st_gid,
        info.st_size,
    )


def _open_parent_directory(
    root: Path, relative: Path, *, create: bool, label: str
) -> int:
    _safe_relative(relative)
    trusted = _trusted_root(root)
    nofollow = getattr(os, "O_NOFOLLOW", 0)
    require(nofollow != 0, "O_NOFOLLOW support is required")
    flags = os.O_RDONLY | os.O_DIRECTORY | nofollow | getattr(os, "O_CLOEXEC", 0)
    root_info = trusted.lstat()
    descriptor = os.open(trusted, flags)
    try:
        require(
            _directory_identity(os.fstat(descriptor)) == _directory_identity(root_info),
            f"{label} root changed before open: {relative}",
        )
        for part in relative.parent.parts:
            created = False
            if create:
                try:
                    os.mkdir(part, 0o700, dir_fd=descriptor)
                    created = True
                    os.fsync(descriptor)
                except FileExistsError:
                    pass
            child: int | None = None
            try:
                child = os.open(part, flags, dir_fd=descriptor)
                info = os.fstat(child)
                require(
                    stat.S_ISDIR(info.st_mode)
                    and info.st_uid == os.geteuid()
                    and stat.S_IMODE(info.st_mode) in REVIEW_DIRECTORY_MODES,
                    f"unsafe {label} parent: {relative}",
                )
                if created:
                    os.fsync(child)
                previous = descriptor
                descriptor = child
                child = None
                os.close(previous)
            finally:
                if child is not None:
                    os.close(child)
        return descriptor
    except BaseException:
        os.close(descriptor)
        raise


def _live_parent_walk_identity(
    root: Path, relative: Path, *, label: str
) -> tuple[tuple[int, ...], ...]:
    _safe_relative(relative)
    trusted = _trusted_root(root)
    nofollow = getattr(os, "O_NOFOLLOW", 0)
    require(nofollow != 0, "O_NOFOLLOW support is required")
    flags = os.O_RDONLY | os.O_DIRECTORY | nofollow | getattr(os, "O_CLOEXEC", 0)
    root_info = trusted.lstat()
    descriptor = os.open(trusted, flags)
    try:
        require(
            _directory_identity(os.fstat(descriptor)) == _directory_identity(root_info),
            f"{label} root changed during live walk: {relative}",
        )
        identities = [_directory_identity(os.fstat(descriptor))]
        for part in relative.parent.parts:
            child = os.open(part, flags, dir_fd=descriptor)
            try:
                info = os.fstat(child)
                require(
                    stat.S_ISDIR(info.st_mode)
                    and info.st_uid == os.geteuid()
                    and stat.S_IMODE(info.st_mode) in REVIEW_DIRECTORY_MODES,
                    f"unsafe {label} parent: {relative}",
                )
                identities.append(_directory_identity(info))
                previous = descriptor
                descriptor = child
                child = -1
                os.close(previous)
            finally:
                if child >= 0:
                    os.close(child)
        return tuple(identities)
    finally:
        os.close(descriptor)


def _descriptor_bytes(descriptor: int) -> bytes:
    os.lseek(descriptor, 0, os.SEEK_SET)
    chunks: list[bytes] = []
    total = 0
    while True:
        chunk = os.read(descriptor, min(64 * 1024, MAXIMUM_EVIDENCE_BYTES + 1 - total))
        if not chunk:
            return b"".join(chunks)
        chunks.append(chunk)
        total += len(chunk)
        require(total <= MAXIMUM_EVIDENCE_BYTES, "staged review evidence exceeds size limit")


def _write_add_only(
    root: Path,
    relative: Path,
    content: bytes,
    *,
    precommit_guard: Callable[[], Callable[[], None] | None] | None = None,
) -> None:
    """R006-style retained-parent, no-replace add-only publication."""
    _safe_relative(relative)
    require(len(content) <= MAXIMUM_EVIDENCE_BYTES, "review output exceeds size limit")
    parent_fd = _open_parent_directory(root, relative, create=True, label="review output")
    parent_walk_before = _live_parent_walk_identity(root, relative, label="review output")
    require(
        parent_walk_before[-1] == _directory_identity(os.fstat(parent_fd)),
        f"review output parent changed before publication: {relative}",
    )
    stage_fd: int | None = None
    verification_fd: int | None = None
    committed = False
    primary_error: BaseException | None = None
    try:
        try:
            os.stat(relative.name, dir_fd=parent_fd, follow_symlinks=False)
        except FileNotFoundError:
            pass
        else:
            raise FileExistsError(f"review output already exists: {relative}")
        temporary_flag = getattr(os, "O_TMPFILE", 0)
        require(temporary_flag != 0, "O_TMPFILE support is required")
        stage_fd = os.open(
            ".",
            os.O_RDWR | temporary_flag | getattr(os, "O_CLOEXEC", 0),
            0o600,
            dir_fd=parent_fd,
        )
        os.fchmod(stage_fd, 0o600)
        view = memoryview(content)
        while view:
            written = os.write(stage_fd, view)
            require(written > 0, "review output write made no progress")
            view = view[written:]
        os.fsync(stage_fd)
        staged = os.fstat(stage_fd)
        require(
            stat.S_ISREG(staged.st_mode)
            and stat.S_IMODE(staged.st_mode) == 0o600
            and staged.st_uid == os.geteuid()
            and staged.st_nlink == 0
            and staged.st_size == len(content)
            and _descriptor_bytes(stage_fd) == content,
            "staged review evidence identity or bytes differ",
        )
        verification_fd = os.dup(stage_fd)
        os.close(stage_fd)
        stage_fd = None
        postpublish_guard = precommit_guard() if precommit_guard is not None else None
        require(postpublish_guard is None or callable(postpublish_guard), "invalid postpublish guard")
        require(
            _live_parent_walk_identity(root, relative, label="review precommit")
            == parent_walk_before
            and _directory_identity(os.fstat(parent_fd)) == parent_walk_before[-1],
            f"review output parent changed before commit: {relative}",
        )

        def mark_committed() -> None:
            nonlocal committed
            committed = True

        token = predecessor._PUBLICATION_COMMIT_CALLBACK.set(mark_committed)
        try:
            predecessor._link_fd_noreplace(verification_fd, parent_fd, relative.name)
            committed = True
        finally:
            predecessor._PUBLICATION_COMMIT_CALLBACK.reset(token)
        if postpublish_guard is not None:
            postpublish_guard()
        named = os.stat(relative.name, dir_fd=parent_fd, follow_symlinks=False)
        retained = os.fstat(verification_fd)
        require(
            _file_identity(named) == _file_identity(retained)
            and retained.st_nlink == 1
            and _descriptor_bytes(verification_fd) == content,
            "published review evidence identity or bytes differ",
        )
        os.fsync(parent_fd)
        published_walk = (*parent_walk_before[:-1], _directory_identity(os.fstat(parent_fd)))
        require(
            _live_parent_walk_identity(root, relative, label="published review evidence")
            == published_walk,
            f"published review parent changed: {relative}",
        )
        raw = _read_regular(root, relative, allowed_modes=frozenset({0o600}))
        require(raw == content, f"published review evidence differs: {relative}")
        if postpublish_guard is not None:
            postpublish_guard()
    except BaseException as exc:
        primary_error = exc
        if committed:
            raise PostcommitUncertain(f"POSTCOMMIT-UNCERTAIN: {relative}: {exc}") from exc
        raise
    finally:
        cleanup_error: BaseException | None = None
        for descriptor in (stage_fd, verification_fd, parent_fd):
            if descriptor is not None:
                try:
                    os.close(descriptor)
                except BaseException as exc:
                    cleanup_error = cleanup_error or exc
        if cleanup_error is not None:
            if committed:
                raise PostcommitUncertain(
                    f"POSTCOMMIT-UNCERTAIN: {relative}: {cleanup_error}"
                ) from cleanup_error
            if primary_error is None:
                raise cleanup_error


def write_assignment(
    root: Path = ROOT, *, assigned_at: str
) -> dict[str, Any]:
    context = prepare_review_context(root)
    raw = build_assignment(context, assigned_at=assigned_at).encode()
    value = _strict_json(raw, ASSIGNMENT_REL.as_posix())
    validate_assignment(value, raw, context)
    expected_scope = review_scope(context)

    def guard() -> Callable[[], None]:
        def verify() -> None:
            require(
                strict_json_equal(
                    review_scope(prepare_review_context(root)), expected_scope
                ),
                "review assignment authority changed during publication",
            )

        verify()
        return verify

    _write_add_only(root, ASSIGNMENT_REL, raw, precommit_guard=guard)
    return binding(ASSIGNMENT_REL, raw)


def publish_review_result_candidate(
    candidate_raw: bytes,
    root: Path = ROOT,
    *,
    actor_id: str,
    actor_task: str,
) -> dict[str, Any]:
    require(
        actor_id == REVIEWER_ID and actor_task == REVIEWER_TASK,
        "review-result publisher is not the assigned reviewer",
    )
    context = prepare_review_context(root)
    assignment_raw = _read_regular(root, ASSIGNMENT_REL)
    assignment = _strict_json(assignment_raw, ASSIGNMENT_REL.as_posix())
    validate_assignment(assignment, assignment_raw, context)
    candidate = _strict_json(candidate_raw, "review-result candidate")
    validate_review_result_candidate(
        candidate, candidate_raw, assignment, assignment_raw, context
    )
    expected_scope = review_scope(context)

    def guard() -> Callable[[], None]:
        def verify() -> None:
            live_context = prepare_review_context(root)
            live_assignment = _read_regular(root, ASSIGNMENT_REL)
            require(
                live_assignment == assignment_raw
                and strict_json_equal(review_scope(live_context), expected_scope),
                "review-result authority changed during publication",
            )

        verify()
        return verify

    _write_add_only(root, RESULT_REL, candidate_raw, precommit_guard=guard)
    return binding(RESULT_REL, candidate_raw)


def publish_independent_review_candidate(
    candidate_raw: bytes,
    root: Path = ROOT,
    *,
    actor_id: str,
    actor_task: str,
) -> dict[str, Any]:
    require(
        actor_id == REVIEWER_ID and actor_task == REVIEWER_TASK,
        "independent-review publisher is not the assigned reviewer",
    )
    context = prepare_review_context(root)
    assignment_raw = _read_regular(root, ASSIGNMENT_REL)
    assignment = _strict_json(assignment_raw, ASSIGNMENT_REL.as_posix())
    validate_assignment(assignment, assignment_raw, context)
    result_raw = _read_regular(root, RESULT_REL)
    result = _strict_json(result_raw, RESULT_REL.as_posix())
    candidate = _strict_json(candidate_raw, "independent-review candidate")
    validate_independent_review_candidate(
        candidate,
        candidate_raw,
        assignment,
        assignment_raw,
        result,
        result_raw,
        context,
    )
    expected_scope = review_scope(context)

    def guard() -> Callable[[], None]:
        def verify() -> None:
            live_context = prepare_review_context(root)
            require(
                _read_regular(root, ASSIGNMENT_REL) == assignment_raw
                and _read_regular(root, RESULT_REL) == result_raw
                and strict_json_equal(review_scope(live_context), expected_scope),
                "independent-review authority changed during publication",
            )

        verify()
        return verify

    _write_add_only(root, INDEPENDENT_REL, candidate_raw, precommit_guard=guard)
    return binding(INDEPENDENT_REL, candidate_raw)


def _read_candidate(path: Path) -> bytes:
    info = path.lstat()
    require(
        stat.S_ISREG(info.st_mode)
        and not stat.S_ISLNK(info.st_mode)
        and info.st_uid == os.geteuid()
        and info.st_nlink == 1
        and stat.S_IMODE(info.st_mode) in {0o600, 0o644}
        and info.st_size <= MAXIMUM_EVIDENCE_BYTES,
        f"unsafe reviewer candidate: {path}",
    )
    descriptor = os.open(
        path,
        os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0),
    )
    try:
        opened = os.fstat(descriptor)
        require(_file_identity(opened) == _file_identity(info), f"candidate changed before read: {path}")
        raw = _descriptor_bytes(descriptor)
        require(
            _file_identity(os.fstat(descriptor)) == _file_identity(opened)
            and len(raw) == opened.st_size,
            f"candidate changed while read: {path}",
        )
        return raw
    finally:
        os.close(descriptor)


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check-pre-review", action="store_true")
    mode.add_argument("--check-post-review", action="store_true")
    mode.add_argument("--write-assignment", action="store_true")
    mode.add_argument("--publish-review-result-candidate", type=Path)
    mode.add_argument("--publish-independent-candidate", type=Path)
    parser.add_argument("--allow-missing-future-controls", action="store_true")
    parser.add_argument("--assigned-at")
    parser.add_argument("--actor-id")
    parser.add_argument("--actor-task")
    parser.add_argument("--root", type=Path, default=ROOT)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        if args.write_assignment:
            require(args.assigned_at is not None, "--assigned-at is required")
            write_assignment(args.root, assigned_at=args.assigned_at)
        elif args.publish_review_result_candidate is not None:
            require(args.actor_id is not None and args.actor_task is not None, "reviewer actor is required")
            publish_review_result_candidate(
                _read_candidate(args.publish_review_result_candidate),
                args.root,
                actor_id=args.actor_id,
                actor_task=args.actor_task,
            )
        elif args.publish_independent_candidate is not None:
            require(args.actor_id is not None and args.actor_task is not None, "reviewer actor is required")
            publish_independent_review_candidate(
                _read_candidate(args.publish_independent_candidate),
                args.root,
                actor_id=args.actor_id,
                actor_task=args.actor_task,
            )
        elif args.check_post_review:
            validate_post_review(args.root)
        else:
            prepare_review_context(
                args.root,
                require_complete_control_cohort=not args.allow_missing_future_controls,
            )
    except (ReviewError, OSError, ValueError, TypeError, KeyError) as exc:
        print(f"FP046 R002 seq84/85 recovery review: FAIL: {exc}", file=sys.stderr)
        return 1
    print("FP046 R002 seq84/85 recovery review: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

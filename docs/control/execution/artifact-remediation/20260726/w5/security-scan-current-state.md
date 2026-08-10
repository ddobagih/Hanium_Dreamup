# W5 Security Scan Current State

- Document: `W5-SECURITY-SCAN-CURRENT-STATE-20260727-001`
- Correction closure: `W5-B02=CLOSED_IN_CURRENT_REGISTER_PENDING_INDEPENDENT_REVIEW`, `W5-M01=CLOSED_IN_CURRENT_REGISTER_PENDING_INDEPENDENT_REVIEW`
- Artifact status: `DLV-SEC-11` and `DLV-SEC-15` are independent-review `OK` candidates only.
- Authority boundary: `source_commit=null`, `build_id=null`, formal 279 `NOT_RUN`, release `NOT_ELIGIBLE`.
- Context acceptance is not a waiver or exception.

## Integrity and source binding

- Finding register: `docs/control/execution/artifact-remediation/20260726/w5/security-finding-register.json`
- Finding register file SHA-256: `fdc0cb5d5d5aef301816967bb0b5e6757f3d9edeed925ee1d07846ed1548c285` (123698 bytes)
- Finding register content SHA-256: `367cdce978098dae63fed8ece87e987a33ab4d244adb9b1aa0e7a36ab81e8c29`
- Current-state content SHA-256: `3c743e240a249c742450f9f0cac5ecd3835802b31d9836e070b2520ada14096e`
- Markdown projection SHA-256: `b468497a1422e02a020a7a48339594f1385023bd60c9c936478a0ec047dbb102`
- Canonicalization: object keys sorted, ASCII JSON escaping, no insignificant whitespace.
- Register content fingerprint excludes only top-level `fingerprints`.
- State content fingerprint excludes top-level `fingerprints` and `markdown_projection_manifest`.
- Markdown projection fingerprint hashes the exact `markdown_projection_manifest` object stored in the state JSON.

| Run | Execution summary path | SHA-256 | Bytes |
|---|---|---|---:|
| `W5-SECURITY-SCAN-20260727-001` | `docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/run-20260727-001/execution-summary.json` | `88c16018d88c5d5983d531c62b4908eea9ee1d1cbccd58841767668132f12455` | 15975 |
| `W5-SECURITY-SCAN-20260727-002` | `docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/run-20260727-002/execution-summary.json` | `47be8e6516f423381a51a616b6bbebf8b984bc57cca87b2eef74df20996beb0a` | 22246 |
| `W5-SECURITY-SCAN-20260727-003` | `docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/run-20260727-003/execution-summary.json` | `ce9e230afeabe591728de39f1e15a050023581751a6f03a660a57970c943e372` | 17078 |

Transitive contract `W5-SUMMARY-TO-RAW-TRANSITIVE-BINDING-V1` verifies summary path/hash/length first, then verifies each raw report path/hash/length recorded inside that summary. Any mismatch invalidates the finding binding.

OSV database baseline: `LIVE_SERVICE_NOT_SNAPSHOTTED`. Android evidence time is `2026-07-27T04:03:22+09:00` to `2026-07-27T04:03:25+09:00`; backend current-lock evidence time is `2026-07-27T04:46:22+09:00` to `2026-07-27T04:46:23+09:00`.

## Exact reconciliation

| Measure | Value |
|---|---:|
| Dependency instances | 526/526 |
| Dependency instances by ecosystem | Maven 438, npm 3, PyPI 85 |
| Root components excluded | 5 |
| SAST findings | 34 = 15 likely + 19 accepted-context non-waiver |
| SAST parse limitation | Kotlin partial 1 |
| Backend SCA | 4 coordinates, 15 OSV records, 8 logical clusters |
| Android SCA | 16 affected coordinates |
| Current-tree secret findings | 0 |
| Reviewed secret false-positive rows | 2 |
| Full repository history secret scan | NOT_RUN |

## SAST family register

| Stable ID | Rule family | Count | Disposition | Severity | Status |
|---|---|---:|---|---|---|
| `W5-SAST-XML-001` | `rule-family:use-defused-xml` | 13 | `LIKELY_REVIEW_REQUIRED` | `ERROR` | `OPEN` |
| `W5-SAST-URL-001` | `rule-family:dynamic-urllib-use-detected` | 2 | `LIKELY_REVIEW_REQUIRED` | `WARNING` | `OPEN` |
| `W5-SAST-URL-002` | `rule-family:dynamic-urllib-use-detected` | 5 | `ACCEPTED_CURRENT_INTERNAL_TOOL_CONTEXT` | `WARNING` | `OPEN_FOR_RELEASE_REVIEW` |
| `W5-SAST-EXEC-001` | `rule-family:exec-detected` | 10 | `ACCEPTED_STATIC_INTERNAL_TOOL_CODE_CONTEXT` | `WARNING` | `OPEN_FOR_RELEASE_REVIEW` |
| `W5-SAST-SHA1-001` | `rule-family:insecure-hash-algorithm-sha1` | 3 | `ACCEPTED_NON_CRYPTO_IDENTIFIER_OR_FORMAT_COMPATIBILITY` | `WARNING` | `OPEN_FOR_RELEASE_REVIEW` |
| `W5-SAST-TLS-001` | `rule-family:httpsconnection-detected` | 1 | `ACCEPTED_GUARDED_SECURE_FACTORY` | `WARNING` | `OPEN_FOR_RELEASE_REVIEW` |

Every family and coordinate has explicit asset, version, environment, severity, exploitability, owner, due, status, mitigation/update, exception/expiry, retest, and release-impact fields in the fingerprint-bound JSON register.

## SAST coordinate manifest

| Stable ID | Family | Source coordinate | Status |
|---|---|---|---|
| `W5-SAST-XML-001-C001` | `W5-SAST-XML-001` | `scripts/build_design_documents_20260710.py:41:41` | `OPEN` |
| `W5-SAST-XML-001-C002` | `W5-SAST-XML-001` | `scripts/build_latest_model_report_20260710.py:21:21` | `OPEN` |
| `W5-SAST-XML-001-C003` | `W5-SAST-XML-001` | `scripts/build_midterm_design_ppt_20260712.py:19:19` | `OPEN` |
| `W5-SAST-XML-001-C004` | `W5-SAST-XML-001` | `scripts/build_midterm_design_ppt_template_faithful_20260712.py:18:18` | `OPEN` |
| `W5-SAST-XML-001-C005` | `W5-SAST-XML-001` | `scripts/build_walksafe_fp012_multi_device_session_ledger_trace_20260726.py:17:17` | `OPEN` |
| `W5-SAST-XML-001-C006` | `W5-SAST-XML-001` | `scripts/build_walksafe_fp014_permission_denial_revocation_trace_20260726.py:17:17` | `OPEN` |
| `W5-SAST-XML-001-C007` | `W5-SAST-XML-001` | `scripts/build_walksafe_fp015_withdrawal_account_deletion_trace_20260725.py:15:15` | `OPEN` |
| `W5-SAST-XML-001-C008` | `W5-SAST-XML-001` | `scripts/build_walksafe_fp016_camera_centered_buttonless_screen_trace_20260726.py:17:17` | `OPEN` |
| `W5-SAST-XML-001-C009` | `W5-SAST-XML-001` | `scripts/build_walksafe_fp047_user_admin_login_authorization_separation_trace_20260726.py:17:17` | `OPEN` |
| `W5-SAST-XML-001-C010` | `W5-SAST-XML-001` | `scripts/build_walksafe_full_rc_20260713.py:47:47` | `OPEN` |
| `W5-SAST-XML-001-C011` | `W5-SAST-XML-001` | `scripts/build_walksafe_w3_engineering_evidence_20260726.py:49:49` | `OPEN` |
| `W5-SAST-XML-001-C012` | `W5-SAST-XML-001` | `scripts/validate_submission_forms_20260710.py:35:35` | `OPEN` |
| `W5-SAST-XML-001-C013` | `W5-SAST-XML-001` | `scripts/validate_walksafe_full_rc_20260713.py:39:39` | `OPEN` |
| `W5-SAST-URL-001-C001` | `W5-SAST-URL-001` | `scripts/capture_submission_ui_20260710.py:44:44` | `OPEN` |
| `W5-SAST-URL-001-C002` | `W5-SAST-URL-001` | `scripts/capture_submission_ui_20260710.py:375:375` | `OPEN` |
| `W5-SAST-URL-002-C001` | `W5-SAST-URL-002` | `scripts/check_pwa_release_update_20260717.py:1103:1103` | `OPEN_FOR_RELEASE_REVIEW` |
| `W5-SAST-URL-002-C002` | `W5-SAST-URL-002` | `scripts/check_pwa_server_e2e.py:43:43` | `OPEN_FOR_RELEASE_REVIEW` |
| `W5-SAST-URL-002-C003` | `W5-SAST-URL-002` | `scripts/check_pwa_server_e2e.py:53:53` | `OPEN_FOR_RELEASE_REVIEW` |
| `W5-SAST-URL-002-C004` | `W5-SAST-URL-002` | `scripts/check_voice_contract.py:84:84` | `OPEN_FOR_RELEASE_REVIEW` |
| `W5-SAST-URL-002-C005` | `W5-SAST-URL-002` | `scripts/check_walksafe_remote_field_browser_20260711.py:165:165` | `OPEN_FOR_RELEASE_REVIEW` |
| `W5-SAST-EXEC-001-C001` | `W5-SAST-EXEC-001` | `scripts/build_walksafe_full_rc_20260713.py:89:89` | `OPEN_FOR_RELEASE_REVIEW` |
| `W5-SAST-EXEC-001-C002` | `W5-SAST-EXEC-001` | `scripts/check_pwa_release_update_20260717.py:213:213` | `OPEN_FOR_RELEASE_REVIEW` |
| `W5-SAST-EXEC-001-C003` | `W5-SAST-EXEC-001` | `scripts/check_walksafe_release_evidence_20260711.py:113:113` | `OPEN_FOR_RELEASE_REVIEW` |
| `W5-SAST-EXEC-001-C004` | `W5-SAST-EXEC-001` | `scripts/run_walksafe_isolated_python_20260713.py:783:783` | `OPEN_FOR_RELEASE_REVIEW` |
| `W5-SAST-EXEC-001-C005` | `W5-SAST-EXEC-001` | `scripts/run_walksafe_product_quality_20260713.py:79:79` | `OPEN_FOR_RELEASE_REVIEW` |
| `W5-SAST-EXEC-001-C006` | `W5-SAST-EXEC-001` | `scripts/run_walksafe_submission_python_20260714.py:123:123` | `OPEN_FOR_RELEASE_REVIEW` |
| `W5-SAST-EXEC-001-C007` | `W5-SAST-EXEC-001` | `scripts/validate_walksafe_full_rc_20260713.py:85:85` | `OPEN_FOR_RELEASE_REVIEW` |
| `W5-SAST-EXEC-001-C008` | `W5-SAST-EXEC-001` | `scripts/verify_walksafe_operator_attestation_20260713.py:71:71` | `OPEN_FOR_RELEASE_REVIEW` |
| `W5-SAST-EXEC-001-C009` | `W5-SAST-EXEC-001` | `scripts/verify_walksafe_signed_android_release_20260713.py:72:72` | `OPEN_FOR_RELEASE_REVIEW` |
| `W5-SAST-EXEC-001-C010` | `W5-SAST-EXEC-001` | `scripts/walksafe_backup_integrity.py:72:72` | `OPEN_FOR_RELEASE_REVIEW` |
| `W5-SAST-SHA1-001-C001` | `W5-SAST-SHA1-001` | `scripts/audit_project_classification_20260708.py:297:297` | `OPEN_FOR_RELEASE_REVIEW` |
| `W5-SAST-SHA1-001-C002` | `W5-SAST-SHA1-001` | `scripts/run_walksafe_submission_python_20260714.py:737:737` | `OPEN_FOR_RELEASE_REVIEW` |
| `W5-SAST-SHA1-001-C003` | `W5-SAST-SHA1-001` | `scripts/walksafe_android_dex_binding.py:103:103` | `OPEN_FOR_RELEASE_REVIEW` |
| `W5-SAST-TLS-001-C001` | `W5-SAST-TLS-001` | `scripts/check_walksafe_trusted_proxy_20260716.py:145:150` | `OPEN_FOR_RELEASE_REVIEW` |

## Backend SCA logical clusters

| Stable ID | Package-version coordinate | Advisory aliases | Severity | Reachability | Status |
|---|---|---:|---|---|---|
| `W5-SCA-PYPI-STARLETTE-001` | `pkg:pypi/starlette@0.52.1` | 4 | `MODERATE` | `NOT_ASSESSED` | `OPEN_REMEDIATION_OR_FORMAL_EXCEPTION_REQUIRED` |
| `W5-SCA-PYPI-STARLETTE-002` | `pkg:pypi/starlette@0.52.1` | 3 | `MODERATE` | `NOT_ASSESSED` | `OPEN_REMEDIATION_OR_FORMAL_EXCEPTION_REQUIRED` |
| `W5-SCA-PYPI-STARLETTE-003` | `pkg:pypi/starlette@0.52.1` | 3 | `HIGH` | `NOT_ASSESSED` | `OPEN_REMEDIATION_OR_FORMAL_EXCEPTION_REQUIRED` |
| `W5-SCA-PYPI-STARLETTE-004` | `pkg:pypi/starlette@0.52.1` | 3 | `LOW` | `NOT_ASSESSED` | `OPEN_REMEDIATION_OR_FORMAL_EXCEPTION_REQUIRED` |
| `W5-SCA-PYPI-STARLETTE-005` | `pkg:pypi/starlette@0.52.1` | 3 | `HIGH` | `NOT_ASSESSED` | `OPEN_REMEDIATION_OR_FORMAL_EXCEPTION_REQUIRED` |
| `W5-SCA-PYPI-PYTEST-001` | `pkg:pypi/pytest@8.4.2` | 3 | `MODERATE` | `NOT_ASSESSED` | `OPEN_REMEDIATION_OR_FORMAL_EXCEPTION_REQUIRED` |
| `W5-SCA-PYPI-SETUPTOOLS-001` | `pkg:pypi/setuptools@81.0.0` | 4 | `MODERATE` | `NOT_ASSESSED` | `OPEN_REMEDIATION_OR_FORMAL_EXCEPTION_REQUIRED` |
| `W5-SCA-PYPI-TORCH-001` | `pkg:pypi/torch@2.11.0` | 4 | `LOW` | `NOT_ASSESSED` | `OPEN_REMEDIATION_OR_FORMAL_EXCEPTION_REQUIRED` |

## Android SCA affected coordinates

| Stable ID | Package-version coordinate | OSV records | Advisory aliases | Severity | Reachability | Status |
|---|---|---:|---:|---|---|---|
| `W5-SCA-MAVEN-001` | `pkg:maven/io.netty/netty-codec@4.1.110.Final` | 3 | 6 | `HIGH` | `NOT_ASSESSED` | `OPEN_REMEDIATION_OR_FORMAL_EXCEPTION_REQUIRED` |
| `W5-SCA-MAVEN-002` | `pkg:maven/io.netty/netty-codec@4.1.93.Final` | 3 | 6 | `HIGH` | `NOT_ASSESSED` | `OPEN_REMEDIATION_OR_FORMAL_EXCEPTION_REQUIRED` |
| `W5-SCA-MAVEN-003` | `pkg:maven/io.netty/netty-codec-http@4.1.110.Final` | 17 | 34 | `HIGH` | `NOT_ASSESSED` | `OPEN_REMEDIATION_OR_FORMAL_EXCEPTION_REQUIRED` |
| `W5-SCA-MAVEN-004` | `pkg:maven/io.netty/netty-codec-http@4.1.93.Final` | 18 | 36 | `HIGH` | `NOT_ASSESSED` | `OPEN_REMEDIATION_OR_FORMAL_EXCEPTION_REQUIRED` |
| `W5-SCA-MAVEN-005` | `pkg:maven/io.netty/netty-codec-http2@4.1.110.Final` | 7 | 14 | `HIGH` | `NOT_ASSESSED` | `OPEN_REMEDIATION_OR_FORMAL_EXCEPTION_REQUIRED` |
| `W5-SCA-MAVEN-006` | `pkg:maven/io.netty/netty-codec-http2@4.1.93.Final` | 8 | 15 | `HIGH` | `NOT_ASSESSED` | `OPEN_REMEDIATION_OR_FORMAL_EXCEPTION_REQUIRED` |
| `W5-SCA-MAVEN-007` | `pkg:maven/io.netty/netty-common@4.1.110.Final` | 2 | 4 | `MODERATE` | `NOT_ASSESSED` | `OPEN_REMEDIATION_OR_FORMAL_EXCEPTION_REQUIRED` |
| `W5-SCA-MAVEN-008` | `pkg:maven/io.netty/netty-common@4.1.93.Final` | 2 | 4 | `MODERATE` | `NOT_ASSESSED` | `OPEN_REMEDIATION_OR_FORMAL_EXCEPTION_REQUIRED` |
| `W5-SCA-MAVEN-009` | `pkg:maven/io.netty/netty-handler@4.1.110.Final` | 4 | 8 | `HIGH` | `NOT_ASSESSED` | `OPEN_REMEDIATION_OR_FORMAL_EXCEPTION_REQUIRED` |
| `W5-SCA-MAVEN-010` | `pkg:maven/io.netty/netty-handler@4.1.93.Final` | 5 | 10 | `HIGH` | `NOT_ASSESSED` | `OPEN_REMEDIATION_OR_FORMAL_EXCEPTION_REQUIRED` |
| `W5-SCA-MAVEN-011` | `pkg:maven/io.netty/netty-handler-proxy@4.1.110.Final` | 1 | 2 | `LOW` | `NOT_ASSESSED` | `OPEN_REMEDIATION_OR_FORMAL_EXCEPTION_REQUIRED` |
| `W5-SCA-MAVEN-012` | `pkg:maven/io.netty/netty-handler-proxy@4.1.93.Final` | 1 | 2 | `LOW` | `NOT_ASSESSED` | `OPEN_REMEDIATION_OR_FORMAL_EXCEPTION_REQUIRED` |
| `W5-SCA-MAVEN-013` | `pkg:maven/org.apache.commons/commons-lang3@3.16.0` | 1 | 2 | `MODERATE` | `NOT_ASSESSED` | `OPEN_REMEDIATION_OR_FORMAL_EXCEPTION_REQUIRED` |
| `W5-SCA-MAVEN-014` | `pkg:maven/org.apache.httpcomponents/httpclient@4.5.6` | 1 | 2 | `MODERATE` | `NOT_ASSESSED` | `OPEN_REMEDIATION_OR_FORMAL_EXCEPTION_REQUIRED` |
| `W5-SCA-MAVEN-015` | `pkg:maven/org.bouncycastle/bcpkix-jdk18on@1.79` | 1 | 2 | `MODERATE` | `NOT_ASSESSED` | `OPEN_REMEDIATION_OR_FORMAL_EXCEPTION_REQUIRED` |
| `W5-SCA-MAVEN-016` | `pkg:maven/org.bouncycastle/bcprov-jdk18on@1.79` | 2 | 4 | `CRITICAL` | `NOT_ASSESSED` | `OPEN_REMEDIATION_OR_FORMAL_EXCEPTION_REQUIRED` |

## Secret false-positive register

| Stable ID | Asset | Rule | Classification | Status | Secret value recorded |
|---|---|---|---|---|---|
| `W5-SECRET-001` | `scripts/build_latest_model_report_20260710.py` | `generic-api-key` | `FALSE_POSITIVE_STATIC_CATALOG_METADATA` | `CLOSED_FALSE_POSITIVE_FOR_CURRENT_TREE` | `false` |
| `W5-SECRET-002` | `backend/tests/test_admin_security.py` | `generic-api-key` | `FALSE_POSITIVE_TEST_FIXTURE` | `CLOSED_FALSE_POSITIVE_FOR_CURRENT_TREE` | `false` |

No raw secret value or advisory prose is reproduced here or in the finding register. Advisory identifiers and lifecycle metadata are retained only as structured evidence.

## Remaining authority boundaries

- `DLV-SEC-10` remains an internal gap because Kotlin parsing is partial, source commit/build identity is absent, and no named release rerun exists.
- `DLV-SEC-12` remains an internal gap because the full repository history secret scan is `NOT_RUN`.
- Open backend and Android vulnerabilities are not waived. Reachability/exploitability remain `NOT_ASSESSED` where no evidence exists.
- No release, signing, deployment, legal, or formal 279 completion claim is made.

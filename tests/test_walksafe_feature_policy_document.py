from __future__ import annotations

import copy
from html.parser import HTMLParser
import json
from pathlib import Path
import unittest

from scripts import build_walksafe_feature_policy_document as builder


EXPECTED_CROSS_FEATURE_POLICY_IDS = [
    *(f"GP-{index:02d}" for index in range(1, 8)),
    *(f"SP-{index:02d}" for index in range(1, 16)),
]
EXPECTED_REVIEW_IDS = [
    *EXPECTED_CROSS_FEATURE_POLICY_IDS,
    *(f"FP-{index:03d}" for index in range(1, 55)),
]


class ReportParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.ids: list[str] = []
        self.feature_articles = 0
        self.common_cards = 0
        self.cross_policy_cards = 0
        self.gate_cards = 0
        self.external_scripts: list[str] = []
        self.feature_search: dict[str, str] = {}
        self.review_unit_ids: list[str] = []
        self.table_count = 0
        self.caption_count = 0
        self.table_header_scopes: list[str | None] = []
        self.tabindex_values: list[str] = []
        self.file_input_accepts: list[str] = []
        self.review_radio_values: list[str] = []
        self.checked_review_radio_count = 0
        self.review_note_count = 0
        self._ignored_depth = 0
        self.visible_parts: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
        if values.get("id"):
            self.ids.append(values["id"] or "")
        classes = set((values.get("class") or "").split())
        if tag == "article" and "feature" in classes:
            self.feature_articles += 1
            if values.get("data-feature-id"):
                self.feature_search[values["data-feature-id"] or ""] = values.get("data-search") or ""
        if tag == "article" and "common-card" in classes:
            if values.get("data-review-unit-id"):
                self.common_cards += 1
            else:
                self.cross_policy_cards += 1
        if tag == "article" and "gate-card" in classes:
            self.gate_cards += 1
        if values.get("data-review-unit-id"):
            self.review_unit_ids.append(values["data-review-unit-id"] or "")
        if tag == "table":
            self.table_count += 1
        if tag == "caption":
            self.caption_count += 1
        if tag == "th":
            self.table_header_scopes.append(values.get("scope"))
        if values.get("tabindex") is not None:
            self.tabindex_values.append(values["tabindex"] or "")
        if tag == "input" and values.get("type") == "file":
            self.file_input_accepts.append(values.get("accept") or "")
        if tag == "input" and values.get("type") == "radio" and (values.get("name") or "").startswith("review-"):
            self.review_radio_values.append(values.get("value") or "")
            self.checked_review_radio_count += int("checked" in values)
        if tag == "textarea" and "data-review-note" in values:
            self.review_note_count += 1
        if tag == "script" and values.get("src"):
            self.external_scripts.append(values["src"] or "")
        if tag in {"script", "style"}:
            self._ignored_depth += 1

    def handle_endtag(self, tag: str) -> None:
        if tag in {"script", "style"} and self._ignored_depth:
            self._ignored_depth -= 1

    def handle_data(self, data: str) -> None:
        if not self._ignored_depth and data.strip():
            self.visible_parts.append(data.strip())


class FeaturePolicyDocumentTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.document = builder.build_document()
        cls.html = builder.render_html(cls.document)
        cls.rules = json.loads(builder.RULES_PATH.read_text(encoding="utf-8"))
        cls.resolution = json.loads(builder.RESOLUTION_PATH.read_text(encoding="utf-8"))
        cls.proposals = json.loads(builder.PROPOSALS_PATH.read_text(encoding="utf-8"))
        cls.register = json.loads(builder.REGISTER_PATH.read_text(encoding="utf-8"))
        cls.parser = ReportParser()
        cls.parser.feed(cls.html)
        cls.visible_text = " ".join(cls.parser.visible_parts)

    def _refresh_digest(self, document: dict) -> None:
        document["document_content_sha256"] = builder._object_sha256(
            {key: value for key, value in document.items() if key != "document_content_sha256"}
        )

    def _assert_tampering_rejected(self, document: dict, label: str) -> None:
        self._refresh_digest(document)
        with self.subTest(label=label):
            with self.assertRaises(builder.FeaturePolicyDocumentError):
                builder.validate_document(document)

    def test_document_has_exact_controlled_coverage(self) -> None:
        summary = self.document["summary"]
        self.assertEqual(summary["area_count"], 18)
        self.assertEqual(summary["feature_count"], 54)
        self.assertEqual(summary["review_count"], 76)
        self.assertEqual(summary["cross_feature_policy_count"], 22)
        self.assertEqual(summary["normalized_constant_count"], 9)
        self.assertEqual(summary["cascade_group_count"], 6)
        self.assertEqual(summary["replacement_count"], 8)
        self.assertEqual(summary["remaining_gate_count"], 5)
        self.assertEqual(summary["gate_feature_edge_count"], 42)
        self.assertEqual(summary["decision_count"], 135)
        self.assertEqual(summary["decision_feature_edge_count"], 428)
        self.assertEqual(summary["suppressed_detail_decision_count"], 17)
        self.assertEqual(summary["evidence_pending_detail_decision_count"], 105)
        self.assertEqual(summary["implementation_revalidation_feature_count"], 50)
        self.assertEqual(summary["remaining_policy_conflict_count"], 0)
        self.assertEqual([item["id"] for item in self.document["features"]], builder.EXPECTED_FEATURE_IDS)

    def test_document_keeps_approval_and_delivery_boundaries(self) -> None:
        metadata = self.document["metadata"]
        self.assertEqual(metadata["lifecycle_status"], "IN_REVIEW")
        self.assertEqual(metadata["baseline_status"], "NOT_APPROVED")
        self.assertEqual(metadata["release_status"], "NOT_ELIGIBLE")
        self.assertEqual(metadata["html_report_generation_status"], "GENERATED")
        self.assertEqual(metadata["formal_deliverable_generation_status"], "NOT_RUN")
        approval_boundary = self.document["approval_boundary"]
        self.assertEqual(approval_boundary["document_status"], "IN_REVIEW")
        self.assertEqual(approval_boundary["baseline_status"], "NOT_APPROVED")
        self.assertEqual(approval_boundary["release_status"], "NOT_ELIGIBLE")
        self.assertEqual(
            approval_boundary["formal_deliverable_generation_status"],
            "NOT_RUN",
        )
        self.assertFalse(approval_boundary["baseline_approval_recorded"])
        self.assertIsNone(approval_boundary["approved_by"])
        self.assertIsNone(approval_boundary["approved_at"])
        self.assertIn("설계 기준 공식 승인 전", self.visible_text)
        self.assertIn("0~6 정식 산출물 미작성", self.visible_text)

    def test_visible_policy_text_uses_plain_korean_without_broken_replacements(self) -> None:
        for broken in (
            "기능가",
            "판단확실성와",
            "판단 확실성와",
            "결과 결과",
            "화면는",
            "음성인식가",
            "시험는",
            "시험로",
            "기준값값",
            "저장소소",
            "휴대전화 휴대전화",
            "후속 확정:",
            "검증된 이전 정상판가",
            "물체 저장소 파일 보관 공간",
            "서울 서버 지역 서버",
            "로그인 상태을",
            "로그인 수단를",
            "휴대전화은",
            "휴대전화이",
            "휴대전화을",
            "수단가",
            "조건가",
            "측정값가",
            "명령 글와",
            "음성와",
            "전송가",
            "수단와",
            "수단로",
            "번호으로",
            "과거 Web 결과",
            "지원 지원표",
            "전체 파일 서버 저장 완료과",
            "학습 학습자료 묶음",
            "회전센서 센서",
            "로그인 로그인 증명",
            "안드로이드 안드로이드 화면읽기 기능",
            "임시 임시자료",
            "서버 서버 전송",
            "독립 독립 시험자료",
            "배포 파일 파일 지문",
            "작은 기능·기능 묶음",
            "처리 대기으로",
            "실행 환경 파일 지문가",
            "인터넷이 없을 때이거나",
        ):
            self.assertNotIn(broken, self.visible_text)
        names = {feature["id"]: feature["name"] for feature in self.document["features"]}
        self.assertEqual(names["FP-009"], "지원 기기와 과거 웹 버전")
        self.assertEqual(names["FP-039"], "모델 등록·교체·이전 정상 모델 복구")
        self.assertEqual(names["FP-049"], "출시 전에 반드시 통과할 기능·안전·접근성 시험")
        self.assertEqual(names["FP-050"], "휴대전화 전체 성능·현장 사용자 시험")
        cross = {
            item["source_review_id"]: item
            for item in self.document["reviewed_cross_feature_policies"]
        }
        self.assertEqual(cross["GP-04"]["title"], "동의한 활성 보행의 원본 수집 범위")
        implementation_overrides = self.rules["implementation_summary_overrides"]
        by_id = {feature["id"]: feature for feature in self.document["features"]}
        for feature_id, expected in implementation_overrides.items():
            with self.subTest(feature_id=feature_id):
                self.assertEqual(
                    by_id[feature_id]["implementation"]["plain_status_before_review"],
                    expected,
                )

    def test_generated_html_is_static_accessible_and_complete(self) -> None:
        self.assertEqual(self.parser.feature_articles, 54)
        self.assertEqual(self.parser.common_cards, 9)
        self.assertEqual(self.parser.cross_policy_cards, 22)
        self.assertEqual(self.parser.gate_cards, 5)
        self.assertEqual(len(self.parser.ids), len(set(self.parser.ids)))
        self.assertEqual(self.parser.external_scripts, [])
        self.assertIn('class="skip-link"', self.html)
        self.assertIn('aria-live="polite"', self.html)
        self.assertIn(":focus-visible", self.html)
        self.assertIn("prefers-reduced-motion", self.html)
        self.assertIn("scroll-margin-top", self.html)
        self.assertNotIn("user-scalable=no", self.html)
        self.assertNotIn("transition: all", self.html)
        self.assertNotIn("replaceChildren", self.html)
        self.assertNotIn("content-visibility", self.html)
        self.assertNotIn("\n      .toolbar button:not(#print-report) { display: none; }", self.html)
        self.assertIn(".topbar .toolbar button:not(#print-report) { display: none; }", self.html)
        self.assertIn("#policy-review .toolbar { flex-direction: column;", self.html)
        for feature_id in builder.EXPECTED_FEATURE_IDS:
            self.assertIn(f'id="{feature_id.lower()}"', self.html)

    def test_html_has_persistent_review_and_json_transfer_controls(self) -> None:
        for control_id in (
            "reviewer-name",
            "review-progress",
            "review-status",
            "export-review",
            "import-review-file",
            "clear-review",
        ):
            self.assertIn(f'id="{control_id}"', self.html)
        expected_review_units = [
            *(item["id"] for item in self.document["common_policies"]),
            *(item["id"] for item in self.document["features"]),
        ]
        self.assertEqual(self.parser.review_unit_ids, expected_review_units)
        self.assertEqual(len(set(self.parser.review_unit_ids)), 63)
        self.assertEqual(len(self.parser.review_radio_values), 63 * 3)
        self.assertEqual(
            {value: self.parser.review_radio_values.count(value) for value in set(self.parser.review_radio_values)},
            {"confirm": 63, "revise": 63, "hold": 63},
        )
        self.assertEqual(self.parser.checked_review_radio_count, 0)
        self.assertEqual(self.parser.review_note_count, 63)
        self.assertIn("localStorage.getItem", self.html)
        self.assertIn("localStorage.setItem", self.html)
        self.assertIn("JSON.parse", self.html)
        self.assertIn("JSON.stringify", self.html)
        self.assertIn('type="file"', self.html)
        self.assertTrue(
            any("application/json" in value or ".json" in value for value in self.parser.file_input_accepts)
        )

        self.assertGreater(self.parser.table_count, 0)
        self.assertEqual(self.parser.caption_count, self.parser.table_count)
        self.assertTrue(self.parser.table_header_scopes)
        self.assertTrue(all(scope in {"col", "row"} for scope in self.parser.table_header_scopes))
        self.assertTrue(self.parser.tabindex_values)
        self.assertTrue(all(value in {"-1", "0"} for value in self.parser.tabindex_values))
        self.assertNotIn("replaceChildren", self.html)

    def test_every_feature_has_complete_operating_policy_and_search_text(self) -> None:
        expected_data_keys = {
            "stored_on_device",
            "sent_to_server",
            "delete_from_device",
            "server_retention",
        }
        for feature in self.document["features"]:
            with self.subTest(feature_id=feature["id"]):
                self.assertTrue(feature["normal_flow"])
                self.assertTrue(feature["failure_behavior"])
                self.assertEqual(set(feature["data_handling"]["current_policy"]), expected_data_keys)
                self.assertTrue(
                    all(
                        isinstance(feature["data_handling"]["current_policy"][key], list)
                        for key in expected_data_keys
                    )
                )
                self.assertIn(feature["name"], feature["search_text"])
                self.assertIn(feature["name"], self.parser.feature_search[feature["id"]])
                self.assertEqual(
                    feature["policy_state"]["policy_conflict_status"],
                    "CALCULATED_NONE",
                )

    def test_auto_report_data_rules_do_not_replace_general_original_lifecycle(self) -> None:
        by_id = {feature["id"]: feature for feature in self.document["features"]}
        for feature_id in ("FP-031", "FP-034", "FP-035", "FP-036", "FP-043", "FP-046", "FP-053"):
            with self.subTest(feature_id=feature_id):
                current = by_id[feature_id]["data_handling"]["current_policy"]
                sent = " ".join(current["sent_to_server"])
                deleted = " ".join(current["delete_from_device"])
                self.assertIn("가리지 않은 수집 원본", sent)
                self.assertIn("자동신고 후보에 한해서", sent)
                self.assertIn("미전송 원본은 30일", deleted)
                self.assertIn("이미 서버에 저장된 자동신고 원본", deleted)
                self.assertIn("일반 활동 원본은 자동신고를 껐다는 이유만으로 삭제하지 않고", deleted)

    def test_account_permission_controls_do_not_claim_to_create_walk_originals(self) -> None:
        by_id = {feature["id"]: feature for feature in self.document["features"]}
        for feature_id in ("FP-010", "FP-011", "FP-013", "FP-014", "FP-015", "FP-047"):
            with self.subTest(feature_id=feature_id):
                self.assertTrue(by_id[feature_id]["data_handling"]["policy_control_only"])
                current = by_id[feature_id]["data_handling"]["current_policy"]
                stored = " ".join(current["stored_on_device"])
                sent = " ".join(current["sent_to_server"])
                self.assertIn("원본을 직접 만들지 않는다", stored)
                self.assertNotIn("이 기능이 직접 만들거나 전달·보관하는 영상", stored)
                self.assertNotIn("가리지 않은 수집 원본", sent)
        self.assertIn("이 기능은 보행 원본을 직접 만들지 않습니다.", self.visible_text)

    def test_all_cross_feature_policies_and_followup_notes_are_preserved(self) -> None:
        policies = self.document["reviewed_cross_feature_policies"]
        self.assertEqual(
            [item["source_review_id"] for item in policies],
            EXPECTED_CROSS_FEATURE_POLICY_IDS,
        )
        resolution_by_id = {item["review_id"]: item for item in self.resolution["review_records"]}
        for policy in policies:
            review_id = policy["source_review_id"]
            source = resolution_by_id[review_id]
            with self.subTest(policy_id=review_id):
                self.assertEqual(policy["id"], f"{review_id}-POLICY-001")
                self.assertEqual(policy["affected_feature_ids"], source["affected_feature_ids"])
                self.assertTrue(policy["current_policy"].strip())

        sp06 = next(item for item in policies if item["source_review_id"] == "SP-06")
        source_sp06 = resolution_by_id["SP-06"]
        self.assertEqual(sp06["current_policy"], source_sp06["proposal_text"])
        self.assertEqual(sp06["followup_note"], source_sp06["reviewer_note"].strip())

    def test_all_76_reviews_are_applied_to_real_feature_policy_refs(self) -> None:
        log = self.document["review_application_log"]
        self.assertEqual([item["review_id"] for item in log], EXPECTED_REVIEW_IDS)
        self.assertEqual(len({item["review_id"] for item in log}), 76)

        real_refs = {
            *(feature["policy_clause_id"] for feature in self.document["features"]),
            *(item["id"] for item in self.document["reviewed_cross_feature_policies"]),
        }
        resolution_by_id = {item["review_id"]: item for item in self.resolution["review_records"]}
        for application in log:
            review_id = application["review_id"]
            expected_refs = (
                [f"{review_id}-POLICY-001"]
                if review_id in EXPECTED_CROSS_FEATURE_POLICY_IDS
                else [
                    f"{feature_id}-POLICY-001"
                    for feature_id in resolution_by_id[review_id]["affected_feature_ids"]
                ]
            )
            with self.subTest(review_id=review_id):
                self.assertEqual(application["active_policy_refs"], expected_refs)
                self.assertTrue(set(application["active_policy_refs"]) <= real_refs)
                self.assertEqual(
                    application["feature_policy_refs"],
                    expected_refs if review_id.startswith("FP-") else [],
                )

        feature_resolution = {
            item["feature_id"]: item for item in self.resolution["feature_resolutions"]
        }
        for feature in self.document["features"]:
            self.assertEqual(
                feature["traceability"]["applicable_review_ids"],
                feature_resolution[feature["id"]]["applicable_review_ids"],
                feature["id"],
            )

    def test_object_detection_responsibility_chain_is_explicit(self) -> None:
        fp019 = next(item for item in self.document["features"] if item["id"] == "FP-019")
        fp020 = next(item for item in self.document["features"] if item["id"] == "FP-020")
        self.assertIn("물체 후보와 관측 근거만", fp019["effective_policy_summary"])
        self.assertIn("위험 단계와 행동은 FP-020", fp019["effective_policy_summary"])
        self.assertNotIn("위험 단계", " ".join(fp019["outputs"]))
        self.assertNotIn("사용자 행동", " ".join(fp019["outputs"]))
        fp019_guidance = json.dumps(fp019["user_guidance"], ensure_ascii=False)
        self.assertNotIn("위험 객체와 해야 할 행동", fp019_guidance)
        self.assertNotIn("중대 위험 보조", fp019_guidance)
        self.assertIn("FP-020이 확정한", fp019_guidance)
        self.assertIn("후보를 안전 판단처럼 말하지 않는다", fp019_guidance)

        self.assertIn("FP-020만", fp020["effective_policy_summary"])
        self.assertIn("위험 단계와 행동", fp020["effective_policy_summary"])
        fp020_guidance = json.dumps(fp020["user_guidance"], ensure_ascii=False)
        self.assertIn("멈추세요. 주변을 확인하세요", fp020_guidance)
        self.assertNotIn("오른쪽으로", fp020_guidance)
        self.assertNotIn("왼쪽으로", fp020_guidance)
        self.assertTrue(
            any(
                "별도로" in item and "왼쪽" in item and "오른쪽" in item
                for item in fp020["prohibited_behaviors"]
            )
        )
        self.assertIn("FP-021 영상 사전검사", self.visible_text)
        self.assertIn("FP-019 물체 후보 관측", self.visible_text)
        self.assertIn("FP-020 위험 단계와 행동 결정", self.visible_text)
        self.assertIn("FP-027 음성·진동 안내", self.visible_text)

    def test_raw_collection_and_lifecycle_values_are_current(self) -> None:
        policies = {item["id"]: item for item in self.document["common_policies"]}
        raw = policies["NPC-RAW-ORIGINAL-COLLECTION"]
        lifecycle = policies["NPC-DATA-LIFECYCLE"]
        self.assertIn("얼굴·번호판·목소리도 가리지 않는다", raw["summary"])
        combined = " ".join(lifecycle["rules"])
        for expected in ("24시간", "30일", "14일", "180일", "3년", "35일"):
            self.assertIn(expected, combined)
        fp034 = next(item for item in self.document["features"] if item["id"] == "FP-034")
        self.assertIn("주변인의 얼굴·차량 번호판·목소리도 가리지 않은", fp034["effective_policy_summary"])

    def test_server_and_phone_capacity_are_never_mixed(self) -> None:
        policies = {item["id"]: item for item in self.document["common_policies"]}
        server = " ".join(policies["NPC-SERVER-STORAGE-CAPACITY"]["rules"])
        phone = " ".join(policies["NPC-PHONE-QUEUE-CAPACITY"]["rules"])
        for expected in ("300 GiB", "70%", "85%", "95%", "100%", "30,000원"):
            self.assertIn(expected, server)
        self.assertIn("휴대전화에는 서버의 300 GiB", phone)
        self.assertIn("적용하지 않는다", phone)
        self.assertIn("실제 바이트 상한", policies["NPC-PHONE-QUEUE-CAPACITY"]["summary"])

    def test_navigation_and_auto_report_final_choices_are_present(self) -> None:
        policies = {item["id"]: item for item in self.document["common_policies"]}
        navigation = " ".join(policies["NPC-NAVIGATION-ROUTE-DIRECTION"]["rules"])
        auto_report = " ".join(policies["NPC-AUTO-REPORT"]["rules"])
        self.assertIn("보폭은 보조 입력일 뿐 위치나 방향을 대신 판단하지 않는다", navigation)
        self.assertIn("새 경로를 선택했을 때만", navigation)
        self.assertIn("후보별 음성·진동·푸시 알림과 개별 취소는 제공하지 않는다", auto_report)
        self.assertIn("미전송 후보를 24시간 안에 삭제", auto_report)
        self.assertIn("서버 원본은 7일 안에 삭제", auto_report)

    def test_superseded_clauses_are_accounted_for_but_not_active(self) -> None:
        summary = self.document["summary"]
        suppressed_open = sum(
            sum(item["section"] == "open_item_proposals" for item in feature["superseded_source_clauses"])
            for feature in self.document["features"]
        )
        self.assertEqual(summary["active_detail_decision_count"] + suppressed_open, 151)
        self.assertEqual(summary["suppressed_detail_decision_count"], suppressed_open)
        active = json.dumps(
            {
                "cross_feature_policies": [
                    item["current_policy"]
                    for item in self.document["reviewed_cross_feature_policies"]
                ],
                "common_policies": self.document["common_policies"],
                "flows": self.document["end_to_end_flows"],
                "features": [
                    {
                        "summary": feature["effective_policy_summary"],
                        "normal_flow": feature["normal_flow"],
                        "failure_behavior": feature["failure_behavior"],
                        "inputs": feature["inputs"],
                        "outputs": feature["outputs"],
                        "design": feature["design_rules"],
                        "guidance": feature["user_guidance"],
                        "data": feature["data_handling"]["current_policy"],
                        "prohibited": feature["prohibited_behaviors"],
                        "verification": feature["verification_scenarios"],
                        "details": feature["detailed_decisions"],
                    }
                    for feature in self.document["features"]
                ],
            },
            ensure_ascii=False,
        )
        for stale in (
            "임시 메모리에서 분석한 뒤 바로 버리며",
            "후보 생성 즉시 음성으로 알리고",
            "자동으로 최대 두 번 새 경로",
            "두 명의 독립 확인자가 복구",
            "서로 다른 두 승인자",
            "상한이 0원이면 원본 학습자료 수집을 끄고",
            "내부 감사기록에만 남긴다",
        ):
            self.assertNotIn(stale, active)

        proposals_by_id = {item["feature_id"]: item for item in self.proposals["features"]}
        for suppression in self.rules["clause_suppressions"]:
            for index in suppression["indexes"]:
                source = proposals_by_id[suppression["feature_id"]][suppression["section"]][index]
                stale_clause = source["recommended_answer"] if isinstance(source, dict) else source
                with self.subTest(
                    feature_id=suppression["feature_id"],
                    section=suppression["section"],
                    source_index=index,
                ):
                    self.assertNotIn(stale_clause, active)

        for replacement in self.rules.get("clause_replacements", []):
            source = proposals_by_id[replacement["feature_id"]][replacement["section"]][
                replacement["index"]
            ]
            stale_clause = source["recommended_answer"] if isinstance(source, dict) else source
            with self.subTest(
                feature_id=replacement["feature_id"],
                section=replacement["section"],
                source_index=replacement["index"],
            ):
                self.assertNotIn(stale_clause, active)

        for review in self.resolution["review_records"]:
            if review["proposal_text_role"] == "ACTIVE_BASE":
                continue
            with self.subTest(review_id=review["review_id"]):
                self.assertNotIn(review["proposal_text"], active)

    def test_all_evidence_pending_details_have_an_explicit_interim_rule(self) -> None:
        pending = [
            detail
            for feature in self.document["features"]
            for detail in feature["detailed_decisions"]
            if detail["status"] == "POLICY_METHOD_DECIDED_EVIDENCE_PENDING"
        ]
        self.assertEqual(len(pending), 105)
        self.assertTrue(all(item["interim_rule"].strip() for item in pending))
        self.assertTrue(
            all(
                "interim_rule" in item
                for feature in self.document["features"]
                for item in feature["detailed_decisions"]
            )
        )

    def test_gate_links_are_bidirectionally_exact(self) -> None:
        expected = {item["id"]: item["affected_feature_ids"] for item in self.document["remaining_gates"]}
        actual = {
            gate_id: [
                feature["id"]
                for feature in self.document["features"]
                if gate_id in {gate["id"] for gate in feature["remaining_gates"]}
            ]
            for gate_id in expected
        }
        self.assertEqual(actual, expected)

    def test_decision_catalog_is_explicitly_pre_review_and_not_formally_updated(self) -> None:
        catalog = self.document["decision_catalog"]
        self.assertEqual(len(catalog), 135)
        self.assertEqual(len({item["decision_id"] for item in catalog}), 135)
        self.assertEqual(len({item["canonical_decision_id"] for item in catalog}), 135)
        self.assertTrue(all(item["mapping_granularity"] == "FEATURE_POLICY_BUNDLE" for item in catalog))
        self.assertTrue(all(item["feature_policy_refs"] for item in catalog))
        self.assertTrue(all(item["formal_register_update_status"] == "NOT_APPLIED" for item in catalog))
        self.assertIn("검토 전 결정대장의 상태", self.visible_text)

    def test_all_135_decisions_map_bidirectionally_to_exact_feature_bundles(self) -> None:
        register_by_id = {item["decision_id"]: item for item in self.register["decisions"]}
        catalog_by_id = {item["decision_id"]: item for item in self.document["decision_catalog"]}
        self.assertEqual(list(catalog_by_id), list(register_by_id))

        for decision_id, decision in catalog_by_id.items():
            source = register_by_id[decision_id]
            expected_feature_ids = source["affected_feature_ids"]
            expected_refs = [f"{feature_id}-POLICY-001" for feature_id in expected_feature_ids]
            with self.subTest(decision_id=decision_id):
                self.assertEqual(decision["affected_feature_ids"], expected_feature_ids)
                self.assertEqual(decision["feature_policy_refs"], expected_refs)

        for feature in self.document["features"]:
            expected_decision_ids = {
                item["decision_id"]
                for item in self.document["decision_catalog"]
                if feature["policy_clause_id"] in item["feature_policy_refs"]
            }
            self.assertEqual(
                set(feature["traceability"]["decision_ids"]),
                expected_decision_ids,
                feature["id"],
            )

    def test_source_bindings_and_content_digest_are_current(self) -> None:
        self.assertEqual(
            self.document["source_binding_sha256"],
            builder._object_sha256(self.document["source_bindings"]),
        )
        self.assertEqual(
            self.document["source_bindings"]["resolution"]["sha256"],
            builder._file_sha256(builder.RESOLUTION_PATH),
        )
        for binding_id, binding in self.document["source_bindings"].items():
            source_path = builder.REPO_ROOT / Path(binding["path"])
            with self.subTest(binding_id=binding_id):
                self.assertTrue(source_path.is_file())
                self.assertEqual(binding["sha256"], builder._file_sha256(source_path))
        proposals_by_id = {item["feature_id"]: item for item in self.proposals["features"]}
        self.assertEqual(
            self.rules["clause_selection_source_sha256"],
            builder._clause_selection_digest(self.rules, proposals_by_id),
        )
        payload = copy.deepcopy(self.document)
        digest = payload.pop("document_content_sha256")
        self.assertEqual(builder._object_sha256(payload), digest)

    def test_tampered_document_is_rejected(self) -> None:
        tampered = copy.deepcopy(self.document)
        tampered["metadata"]["baseline_status"] = "APPROVED"
        tampered["document_content_sha256"] = builder._object_sha256(
            {key: value for key, value in tampered.items() if key != "document_content_sha256"}
        )
        with self.assertRaisesRegex(builder.FeaturePolicyDocumentError, "baseline approval"):
            builder.validate_document(tampered)

        boundary_tampered = copy.deepcopy(self.document)
        boundary_tampered["approval_boundary"]["baseline_status"] = "APPROVED"
        boundary_tampered["approval_boundary"]["baseline_approval_recorded"] = True
        self._assert_tampering_rejected(boundary_tampered, "forged approval boundary")

    def test_every_source_binding_tamper_is_rejected(self) -> None:
        binding_ids = list(self.document["source_bindings"])
        for index, binding_id in enumerate(binding_ids):
            tampered = copy.deepcopy(self.document)
            tampered["source_bindings"][binding_id]["sha256"] = "0" * 64
            tampered["source_binding_sha256"] = builder._object_sha256(tampered["source_bindings"])
            self._assert_tampering_rejected(tampered, f"source binding hash: {binding_id}")

            other_id = binding_ids[(index + 1) % len(binding_ids)]
            path_tampered = copy.deepcopy(self.document)
            path_tampered["source_bindings"][binding_id] = copy.deepcopy(
                path_tampered["source_bindings"][other_id]
            )
            path_tampered["source_binding_sha256"] = builder._object_sha256(
                path_tampered["source_bindings"]
            )
            self._assert_tampering_rejected(
                path_tampered,
                f"source binding path: {binding_id}",
            )

    def test_bogus_policy_trace_and_gate_references_are_rejected(self) -> None:
        common_policy = copy.deepcopy(self.document)
        common_policy["common_policies"][0]["affected_feature_ids"].append("FP-999")
        self._assert_tampering_rejected(common_policy, "common policy bogus feature")

        common_policy_review = copy.deepcopy(self.document)
        common_policy_review["common_policies"][0]["source_review_ids"].append("GP-999")
        self._assert_tampering_rejected(
            common_policy_review,
            "common policy bogus review",
        )

        gate = copy.deepcopy(self.document)
        gate["remaining_gates"][0]["affected_feature_ids"].append("FP-999")
        self._assert_tampering_rejected(gate, "gate bogus feature")

        feature_trace = copy.deepcopy(self.document)
        feature_trace["features"][0]["traceability"]["applicable_review_ids"].append("FP-999")
        self._assert_tampering_rejected(feature_trace, "feature bogus review")

        decision = copy.deepcopy(self.document)
        decision["decision_catalog"][0]["feature_policy_refs"].append("FP-999-POLICY-001")
        self._assert_tampering_rejected(decision, "decision bogus policy ref")

    def test_unverified_implementation_cannot_be_forged_as_verified(self) -> None:
        tampered = copy.deepcopy(self.document)
        feature = next(
            item
            for item in tampered["features"]
            if item["implementation"]["alignment_status"] != "VERIFIED"
        )
        feature["implementation"]["alignment_status"] = "VERIFIED"
        feature["implementation"]["alignment_label"] = "검증 완료"
        self._assert_tampering_rejected(tampered, "forged implementation verification")

    def test_checked_in_outputs_are_byte_for_byte_current(self) -> None:
        expected_json = (json.dumps(self.document, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
        self.assertEqual(builder.JSON_OUTPUT_PATH.read_bytes(), expected_json)
        self.assertEqual(builder.HTML_OUTPUT_PATH.read_text(encoding="utf-8"), self.html)


if __name__ == "__main__":
    unittest.main()

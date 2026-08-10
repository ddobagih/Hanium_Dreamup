#!/usr/bin/env python3
"""Verify exact project copies before deleting project documents from Downloads."""

from __future__ import annotations

import hashlib
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DOWNLOADS = Path("/home/ddobagi/Downloads")

# source path, repository path, expected SHA-256
FILES = (
    ("AIHub183_escooter_obstruction_review_20260625_metadata/FOUR_ROUND_REVIEW_PLAN.md", "docs/inventory/downloads_imported/2026-07-10/aihub183_review_history/FOUR_ROUND_REVIEW_PLAN.md", "31959d634403b29fbf17451efedc1fc2717bcd898a5a0dd72bba6961f966d8c2"),
    ("AIHub183_escooter_obstruction_review_20260625_metadata/review_template.csv", "docs/inventory/downloads_imported/2026-07-10/aihub183_review_history/review_template.csv", "9995fb59ecf9e3a9491386be27964fd24f5dd509cd01dedbd096028e04fd2497"),
    ("AIHub183_escooter_obstruction_review_20260625_metadata/summary.json", "docs/inventory/downloads_imported/2026-07-10/aihub183_review_history/summary.json", "7c20f87a7dfb1951d2a674e3da4545d3e6340d7c71e5ebb29f6ba5fe99d48e4b"),
    ("escooter_obstruction_review_fullres_rechecked.csv", "docs/inventory/downloads_imported/2026-07-10/escooter_obstruction_review_fullres_rechecked.csv", "9f38b97256d4f864d353460cb91f51581d928875ad07142e5814c445d7f2360f"),
    ("walksafe_dataset_download_inventory_20260628.csv", "docs/inventory/downloads_imported/2026-07-10/walksafe_dataset_download_inventory_20260628.csv", "0e705071477383420ff3312643912baf77704f3e88fb4f1c28f66d3c8ce939df"),
    ("walksafe_team_aihub_used_data_training_brief_20260628.md", "docs/inventory/downloads_imported/2026-07-10/walksafe_team_aihub_used_data_training_brief_20260628.md", "01eed98ca34ad57fbd8beaa406f87ba61826774bcf50791e8187f47844a36c49"),
    ("walksafe_training_reference_index_20260708.md", "docs/inventory/downloads_imported/2026-07-10/walksafe_training_reference_index_20260708.md", "0e61801869698fda1a89b38ea277098ba6d96ca75306cffa5eadcefb07b34cea"),
    ("abc.html", "docs/inventory/downloads_imported/2026-07-10/superseded_policy_board_abc.html", "af446fdaaeaad8606dc426df973ad457ba98c0c3cf33462188258a784ffae59e"),
    ("abcd.md", "docs/inventory/downloads_imported/2026-07-10/previous_session_handoff_abcd.md", "c33e75de5ef5e15089e5485d753e87d1cc54734f39425270da0a1124af9a033d"),
    ("walksafe_final_asset_classification_20260708.md", "reports/storage_cleanup_20260708/final_asset_classification_20260708.md", "d57d1e5e47487f4ef206175f547020f99bd49a356c20f5211617ca0b876dfe3b"),
    ("walksafe_overlay_fourth_review_151.md", "docs/inventory/downloads_imported/2026-07-08/walksafe_overlay_fourth_review_151.md", "623cb2c6e64c681d97135789793184aa1f10917cab9097b6e2bc67e6c6000087"),
    ("그래프/01_metrics_overview.png", "reports/walksafe_best_eval_20260708/training_graphs_20260708/01_metrics_overview.png", "18aed3d4d34c6a186e0279a85dcd075888faeaa307107374110e5f9abb35ee52"),
    ("그래프/02_map_trend_recent.png", "reports/walksafe_best_eval_20260708/training_graphs_20260708/02_map_trend_recent.png", "196e2e52d2fe37568be91ab69d23369783b3f6cde8d70c9297cd08b76225b52c"),
    ("그래프/03_losses_train_val.png", "reports/walksafe_best_eval_20260708/training_graphs_20260708/03_losses_train_val.png", "41120e0eb85f56104aa35e2b842a3fafb4bd41d28dc8af6176c5239874c7fef7"),
    ("그래프/04_overfit_diagnostic.png", "reports/walksafe_best_eval_20260708/training_graphs_20260708/04_overfit_diagnostic.png", "b2b5d89434155d8a76d82ed19317cda427c3761193bff7055c99a258cef385f3"),
    ("그래프/05_lr_schedule.png", "reports/walksafe_best_eval_20260708/training_graphs_20260708/05_lr_schedule.png", "73527517b11f823c9c0699eba8dc7936501c0ac47668536f9ba7a986828661ea"),
    ("그래프/06_epoch_time.png", "reports/walksafe_best_eval_20260708/training_graphs_20260708/06_epoch_time.png", "25ea1e3dc45eacf6ec60c7a911c310df408b0f5ea0643bc0fa4d7adfafe43f99"),
    ("그래프/training_metrics_snapshot.csv", "reports/walksafe_best_eval_20260708/training_graphs_20260708/training_metrics_snapshot.csv", "1dc4b32335db04910ed51746f4bb3e7955d3f545c25dbd5dd4c44253b9d62dcc"),
    ("그래프/학습_그래프_요약.md", "reports/walksafe_best_eval_20260708/training_graphs_20260708/학습_그래프_요약.md", "e77c3dd861696e785d748b7107221e6d96659fae6c13a84cfd6df0545ca79509"),
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    errors: list[str] = []
    for source_rel, destination_rel, expected in FILES:
        destination = ROOT / destination_rel
        if not destination.is_file():
            errors.append(f"missing repository copy: {destination_rel}")
            continue
        destination_hash = sha256(destination)
        if destination_hash != expected:
            errors.append(
                f"repository hash mismatch: {destination_rel} "
                f"expected={expected} actual={destination_hash}"
            )

        source = DOWNLOADS / source_rel
        if source.exists() and sha256(source) != expected:
            errors.append(f"Downloads source changed after audit: {source_rel}")

    if errors:
        print("FAIL")
        for error in errors:
            print(f"- {error}")
        return 1

    print("PASS")
    print(f"verified files: {len(FILES)}")
    print("Downloads source files may be absent after user deletion.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

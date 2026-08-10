"""add report data integrity constraints

Revision ID: 202607110002
Revises: 202607110001
Create Date: 2026-07-11
"""

from __future__ import annotations

from alembic import op


revision = "202607110002"
down_revision = "202607110001"
branch_labels = None
depends_on = None


CONSTRAINTS = {
    "ck_reports_status": "status IN ('new', 'reviewed', 'resolved')",
    "ck_reports_class_id_nonnegative": "class_id >= 0",
    "ck_reports_confidence": "confidence >= 0 AND confidence <= 1",
    "ck_reports_bbox": (
        "bbox_x >= 0 AND bbox_y >= 0 AND bbox_width > 0 AND bbox_height > 0 "
        "AND bbox_x + bbox_width <= 1 AND bbox_y + bbox_height <= 1"
    ),
    "ck_reports_source": "source IN ('fake', 'onnx', 'server', 'android')",
    "ck_reports_accuracy": "accuracy_m IS NULL OR accuracy_m >= 0",
    "ck_reports_heading": "heading IS NULL OR (heading >= 0 AND heading < 360)",
    "ck_reports_location_consistency": (
        "(latitude IS NULL AND longitude IS NULL AND location IS NULL) OR "
        "(latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180 "
        "AND location IS NOT NULL AND ST_SRID(location) = 4326 "
        "AND ST_X(location) = longitude AND ST_Y(location) = latitude)"
    ),
}


def upgrade() -> None:
    # NOT VALID avoids a long exclusive table scan while each constraint is
    # installed. VALIDATE still fails closed if historical rows are corrupt.
    for name, expression in CONSTRAINTS.items():
        op.execute(f"ALTER TABLE reports ADD CONSTRAINT {name} CHECK ({expression}) NOT VALID")
    for name in CONSTRAINTS:
        op.execute(f"ALTER TABLE reports VALIDATE CONSTRAINT {name}")


def downgrade() -> None:
    for name in reversed(CONSTRAINTS):
        op.execute(f"ALTER TABLE reports DROP CONSTRAINT IF EXISTS {name}")

"""create reports table

Revision ID: 202605120001
Revises:
Create Date: 2026-05-12
"""

from __future__ import annotations

from alembic import op


revision = "202605120001"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("CREATE EXTENSION IF NOT EXISTS postgis")
    op.execute(
        """
        CREATE TABLE reports (
            id UUID PRIMARY KEY,
            status VARCHAR(16) NOT NULL DEFAULT 'new',
            class_id INTEGER NOT NULL,
            class_name VARCHAR(64) NOT NULL,
            confidence DOUBLE PRECISION NOT NULL,
            bbox_x DOUBLE PRECISION NOT NULL,
            bbox_y DOUBLE PRECISION NOT NULL,
            bbox_width DOUBLE PRECISION NOT NULL,
            bbox_height DOUBLE PRECISION NOT NULL,
            captured_at TIMESTAMPTZ NOT NULL,
            source VARCHAR(16) NOT NULL,
            latitude DOUBLE PRECISION,
            longitude DOUBLE PRECISION,
            accuracy_m DOUBLE PRECISION,
            heading DOUBLE PRECISION,
            location geometry(Point, 4326),
            image_path VARCHAR(255) NOT NULL,
            image_content_type VARCHAR(128) NOT NULL,
            metadata JSONB NOT NULL,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
        )
        """
    )
    op.execute("CREATE INDEX ix_reports_status ON reports (status)")
    op.execute("CREATE INDEX ix_reports_class_name ON reports (class_name)")
    op.execute("CREATE INDEX ix_reports_created_at ON reports (created_at)")
    op.execute("CREATE INDEX ix_reports_location ON reports USING GIST (location)")


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS reports")

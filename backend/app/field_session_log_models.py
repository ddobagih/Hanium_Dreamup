"""Persisted test-diagnostic preference, session and bounded metadata batches."""
from sqlalchemy import BigInteger, Boolean, CheckConstraint, Column, DateTime, ForeignKeyConstraint, Index, JSON, String
from sqlalchemy.dialects.postgresql import JSONB
from backend.app.models import Base

class GuidanceDiagnosticPreference(Base):
    __tablename__ = "guidance_diagnostic_preferences"
    __table_args__ = (CheckConstraint("account_generation > 0 AND revision > 0", name="ck_guidance_diag_preference_revision"),)
    privacy_subject_hmac = Column(String(64), primary_key=True)
    account_generation = Column(BigInteger, primary_key=True)
    enabled = Column(Boolean, nullable=False)
    revision = Column(BigInteger, nullable=False)
    updated_at = Column(DateTime(timezone=True), nullable=False)


class GuidanceDiagnosticSession(Base):
    __tablename__ = "guidance_diagnostic_sessions"
    __table_args__ = (
        ForeignKeyConstraint(["privacy_subject_hmac", "account_generation"], ["guidance_diagnostic_preferences.privacy_subject_hmac", "guidance_diagnostic_preferences.account_generation"]),
        CheckConstraint("account_generation > 0", name="ck_guidance_diag_session_generation"),
        Index("ix_guidance_diag_session_owner", "privacy_subject_hmac", "account_generation"),
        Index("ix_guidance_diag_session_retention", "last_received_at"),
    )
    session_id = Column(String(36), primary_key=True)
    privacy_subject_hmac = Column(String(64), nullable=False)
    account_generation = Column(BigInteger, nullable=False)
    build_id = Column(String(64), nullable=False)
    created_at = Column(DateTime(timezone=True), nullable=False)
    last_received_at = Column(DateTime(timezone=True), nullable=False)


class GuidanceDiagnosticBatch(Base):
    __tablename__ = "guidance_diagnostic_batches"
    __table_args__ = (
        ForeignKeyConstraint(["session_id"], ["guidance_diagnostic_sessions.session_id"], ondelete="CASCADE"),
        CheckConstraint("payload_bytes > 0 AND payload_bytes <= 262144", name="ck_guidance_diag_batch_bytes"),
        CheckConstraint("event_count > 0 AND event_count <= 100", name="ck_guidance_diag_batch_count"),
        Index("ix_guidance_diag_batch_retention", "received_at"),
    )
    session_id = Column(String(36), primary_key=True)
    batch_id = Column(String(36), primary_key=True)
    payload_sha256 = Column(String(64), nullable=False)
    payload_bytes = Column(BigInteger, nullable=False)
    event_count = Column(BigInteger, nullable=False)
    events = Column(JSON().with_variant(JSONB, "postgresql"), nullable=False)
    received_at = Column(DateTime(timezone=True), nullable=False)



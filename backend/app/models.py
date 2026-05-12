from __future__ import annotations

import uuid

from geoalchemy2 import Geometry
from sqlalchemy import Column, DateTime, Float, Integer, String, func
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import declarative_base


Base = declarative_base()


class Report(Base):
    __tablename__ = "reports"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    status = Column(String(16), nullable=False, default="new", index=True)
    class_id = Column(Integer, nullable=False)
    class_name = Column(String(64), nullable=False, index=True)
    confidence = Column(Float, nullable=False)
    bbox_x = Column(Float, nullable=False)
    bbox_y = Column(Float, nullable=False)
    bbox_width = Column(Float, nullable=False)
    bbox_height = Column(Float, nullable=False)
    captured_at = Column(DateTime(timezone=True), nullable=False)
    source = Column(String(16), nullable=False)
    latitude = Column(Float, nullable=True)
    longitude = Column(Float, nullable=True)
    accuracy_m = Column(Float, nullable=True)
    heading = Column(Float, nullable=True)
    location = Column(Geometry("POINT", srid=4326, spatial_index=False), nullable=True)
    image_path = Column(String(255), nullable=False)
    image_content_type = Column(String(128), nullable=False)
    payload = Column("metadata", JSONB, nullable=False)
    created_at = Column(DateTime(timezone=True), nullable=False, server_default=func.now())
    updated_at = Column(DateTime(timezone=True), nullable=False, server_default=func.now(), onupdate=func.now())

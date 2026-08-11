export type TwoModelKey = "custom_tactile" | "coco_general" | "unified_walksafe";

export type KnownTwoModelClassName =
  | "tactile_damage_area"
  | "damaged_tactile_block"
  | "car"
  | "bus"
  | "truck"
  | "motorcycle"
  | "person"
  | "bicycle"
  | "traffic light"
  | "normal_tactile_block"
  | "crosswalk"
  | "curb_step"
  | "uneven_sidewalk"
  | "e_scooter_obstruction"
  | "bench";

export type TwoModelClassName = KnownTwoModelClassName | (string & {});

export type KnownTwoModelCategory =
  | "tactile_damage"
  | "tactile_normal"
  | "vehicle"
  | "vulnerable_road_user"
  | "traffic_signal"
  | "street_furniture"
  | "path_guidance"
  | "surface_hazard"
  | "obstruction"
  | "unknown";

export type TwoModelCategory = KnownTwoModelCategory | (string & {});

export type NormalizedBBoxV2 = {
  x: number;
  y: number;
  width: number;
  height: number;
};

export type GpsFixV2 = {
  latitude: number;
  longitude: number;
  accuracy_m?: number | null;
  speed_mps?: number | null;
};

export type DetectionDistanceSource = "sensor_depth" | "manual_fixture" | "model_estimate" | "unknown";
export type DetectionApproachState = "approaching" | "stable" | "receding" | "unknown";
export type DetectV2ParserDropReason =
  | "invalid_detection_schema"
  | "invalid_model_key"
  | "missing_source_model"
  | "invalid_model_class_id"
  | "missing_class_name"
  | "missing_category"
  | "invalid_confidence"
  | "invalid_bbox"
  | "invalid_threshold";

export type DetectV2ParserDropSummary = {
  reason: DetectV2ParserDropReason;
  count: number;
};

export type DetectV2ParserAudit = {
  raw_detection_count: number;
  parsed_detection_count: number;
  parser_drop_count: number;
  parser_drop_reasons: DetectV2ParserDropSummary[];
};

export type DetectV2RequestAudit = {
  request_id: string;
  latency_ms: number;
  http_status: number | null;
  raw_detection_count: number | null;
  parsed_detection_count: number;
  parser_drop_count: number;
  parser_drop_reasons: DetectV2ParserDropSummary[];
  error_code: string | null;
  error_message: string | null;
};

export type TwoModelDetection = {
  schema_version: "detect.v2";
  model_key: TwoModelKey;
  source_model: string;
  model_class_id: number;
  class_name: TwoModelClassName;
  category: TwoModelCategory;
  confidence: number;
  bbox: NormalizedBBoxV2;
  distance_m?: number | null;
  distance_source?: DetectionDistanceSource | null;
  distance_confidence?: number | null;
  approach_state?: DetectionApproachState | null;
  threshold_used: number;
  captured_at: string;
  gps?: GpsFixV2 | null;
  heading?: number | null;
};

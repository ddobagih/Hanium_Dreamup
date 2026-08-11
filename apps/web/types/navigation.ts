export type WalkingRouteProvider = "tmap_pedestrian" | "kakao_mobility";
export type DestinationSearchProvider = "tmap_poi";
export type WalkingRoutePriority = "RECOMMEND" | "MAIN_STREET" | "DISTANCE" | "STAIR_AVOID";

export type RoutePoint = {
  latitude: number;
  longitude: number;
  name?: string | null;
};


export type DestinationSearchResult = {
  id: string;
  name: string;
  point: RoutePoint;
  address?: string | null;
  road_address?: string | null;
  category?: string | null;
  result_type?: "poi" | "address" | "alias";
  distance_m?: number | null;
};

export type DestinationSearchResponse = {
  schema_version: "walksafe.destination_search.v1";
  provider: DestinationSearchProvider;
  query: string;
  results: DestinationSearchResult[];
};

export type WalkingRouteRequest = {
  origin: RoutePoint;
  destination: RoutePoint;
  waypoints?: RoutePoint[];
  priority?: WalkingRoutePriority;
  radius_m?: number;
  default_speed?: number | null;
};

export type WalkingRouteSummary = {
  distance_m: number;
  duration_s: number;
};

export type WalkingRouteStep = {
  index: number;
  distance_m: number;
  duration_s: number;
  points: RoutePoint[];
  instruction?: string | null;
  road_name?: string | null;
  turn_type?: number | null;
  facility_type?: number | null;
};

export type WalkingRouteGuidePoint = {
  index: number;
  point: RoutePoint;
  instruction?: string | null;
  turn_type?: number | null;
  point_type?: string | null;
  facility_type?: number | null;
  distance_from_start_m?: number | null;
  remaining_distance_m?: number | null;
};

export type WalkingRouteResponse = {
  schema_version: "walksafe.walking_route.v1";
  provider: WalkingRouteProvider;
  provider_route_id?: string | null;
  priority: WalkingRoutePriority;
  summary: WalkingRouteSummary;
  polyline: RoutePoint[];
  steps: WalkingRouteStep[];
  guide_points: WalkingRouteGuidePoint[];
  provider_result_code: number;
  provider_result_message: string;
};

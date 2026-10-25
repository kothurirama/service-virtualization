// Wire types of the platform's REST API (snake_case, as returned by the server).

export type StubSource = 'MANUAL' | 'SEED' | 'RECORDED';

export interface RequestMatcher {
  protocol?: string;
  method: string;
  path: string;
  headers?: Record<string, unknown>;
  query?: Record<string, unknown>;
  body_jsonpath?: Record<string, unknown>;
}

export interface ResponseTemplate {
  status_code?: number;
  headers?: Record<string, string>;
  body?: unknown;
}

export interface StateAction {
  op: string;
  collection: string;
  key?: string;
  value?: unknown;
}

export interface StubDefinition {
  stub_id: string;
  description?: string;
  enabled: boolean;
  priority: number;
  tags: string[];
  source: StubSource;
  request_matcher: RequestMatcher;
  response_template?: ResponseTemplate;
  state_action?: StateAction;
  not_found_response?: ResponseTemplate;
}

export interface StubView {
  stub: StubDefinition;
  route: string;
  hit_count: number;
  last_hit_at?: string;
  created_at: string;
  updated_at: string;
}

export interface TrafficEntry {
  seq: number;
  timestamp_millis: number;
  session_id: string;
  method: string;
  path: string;
  matched_stub_id?: string;
  status_code: number;
  engine_latency_ms: number;
  injected_delay_ms: number;
  fault_injected: boolean;
  state_mutated: boolean;
  request_body?: unknown;
  response_body?: unknown;
}

export interface MetricsView {
  traffic: {
    total_requests: number;
    matched: number;
    unmatched: number;
    faults_injected: number;
    state_mutations: number;
    sample_size: number;
    p50_ms: number;
    p95_ms: number;
    p99_ms: number;
    max_ms: number;
  };
  stubs: number;
  enabled_stubs: number;
  sessions: number;
  uptime_seconds: number;
}

export interface SessionView {
  session_id: string;
  created_at: string;
  last_access_at: string;
  ttl_seconds: number;
  explicit: boolean;
  labels: Record<string, string>;
  entry_count: number;
  request_count: number;
  mutation_count: number;
  collections: Record<string, number>;
}

export interface StateView {
  session_id: string;
  entry_count: number;
  collections: Record<string, Record<string, unknown>>;
}

export type Distribution = 'FIXED' | 'UNIFORM' | 'GAUSSIAN';

export interface DriftProfile {
  enabled: boolean;
  latency_ms: number;
  jitter_ms: number;
  distribution: Distribution;
  error_rate: number;
  error_status: number;
}

export interface DriftView {
  global: DriftProfile;
  routes: Record<string, DriftProfile>;
  known_routes: string[];
}

export interface ReviewItem {
  field_name: string;
  log_index?: number;
  stub_id?: string;
  detected_type?: string;
  confidence?: number;
  reason: string;
  suggested_action: string;
}

export interface FieldDetection {
  log_index: number;
  field: string;
  type: string;
  category: 'PCI' | 'PII' | 'SECRET';
  confidence: number;
  detector: string;
  reason: string;
  masked_as: string;
}

export type RecordingStatus = 'PUBLISHED' | 'PENDING_REVIEW' | 'DRAFT' | 'REJECTED';

export interface RecordingView {
  stub_config_id: string;
  generated_by: string;
  generated_at: string;
  service_name: string;
  sanitization_summary: {
    total_fields_inspected: number;
    pci_fields_masked: number;
    pii_fields_masked: number;
    secret_fields_masked: number;
    requires_human_review: boolean;
    logs_processed: number;
    stubs_generated: number;
    lowest_confidence?: number;
  };
  virtual_stubs: StubDefinition[];
  review_queue: ReviewItem[];
  field_detections: FieldDetection[];
  status: RecordingStatus;
  published_stub_ids: string[];
  status_reason?: string;
  decided_at?: string;
}

export interface VirtualRequest {
  session_id?: string;
  protocol?: string;
  method: string;
  path: string;
  headers?: Record<string, string>;
  body?: unknown;
}

export interface VirtualResponse {
  matched_stub_id: string | null;
  status_code: number;
  headers?: Record<string, string>;
  body?: unknown;
  state_mutated: boolean;
  execution_latency_ms: number;
  matched_rules: string[];
  session_id?: string;
  injected_delay_ms: number;
  fault_injected: boolean;
  near_misses?: { stub_id: string; failed_rule: string; detail: string }[];
}

export interface ApiError {
  error: string;
  message: string;
  details?: string[];
}

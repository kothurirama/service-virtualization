import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  ApiError, DriftProfile, DriftView, MetricsView, RecordingView, SessionView, StateView, StubDefinition,
  StubView, TrafficEntry, VirtualRequest, VirtualResponse,
} from './models';

/** Thin typed client over the platform REST API. The UI uses nothing else. */
@Injectable({ providedIn: 'root' })
export class Api {
  private readonly http = inject(HttpClient);
  private readonly base = '/api/v1';

  metrics(): Observable<MetricsView> {
    return this.http.get<MetricsView>(`${this.base}/metrics/summary`);
  }

  traffic(limit: number, afterSeq = -1, sessionId?: string, stubId?: string): Observable<TrafficEntry[]> {
    let params = new HttpParams().set('limit', limit).set('after_seq', afterSeq);
    if (sessionId) params = params.set('session_id', sessionId);
    if (stubId) params = params.set('stub_id', stubId);
    return this.http.get<TrafficEntry[]>(`${this.base}/traffic`, { params });
  }

  clearTraffic(): Observable<void> {
    return this.http.delete<void>(`${this.base}/traffic`);
  }

  execute(request: VirtualRequest): Observable<VirtualResponse> {
    return this.http.post<VirtualResponse>(`${this.base}/engine/execute`, request);
  }

  stubs(q = '', source = ''): Observable<StubView[]> {
    let params = new HttpParams();
    if (q) params = params.set('q', q);
    if (source) params = params.set('source', source);
    return this.http.get<StubView[]>(`${this.base}/stubs`, { params });
  }

  saveStub(stub: StubDefinition): Observable<StubView> {
    return this.http.post<StubView>(`${this.base}/stubs`, stub);
  }

  validateStub(stub: StubDefinition): Observable<{ valid: boolean; route: string; matched_rules: string[] }> {
    return this.http.post<{ valid: boolean; route: string; matched_rules: string[] }>(`${this.base}/stubs/validate`, stub);
  }

  setStubEnabled(id: string, enabled: boolean): Observable<StubView> {
    return this.http.patch<StubView>(`${this.base}/stubs/${encodeURIComponent(id)}/enabled`, { enabled });
  }

  deleteStub(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/stubs/${encodeURIComponent(id)}`);
  }

  sessions(): Observable<SessionView[]> {
    return this.http.get<SessionView[]>(`${this.base}/sessions`);
  }

  createSession(sessionId: string | null, ttlSeconds: number | null, labels: Record<string, string>): Observable<SessionView> {
    return this.http.post<SessionView>(`${this.base}/sessions`, {
      session_id: sessionId || undefined, ttl_seconds: ttlSeconds ?? undefined, labels,
    });
  }

  teardownSession(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/sessions/${encodeURIComponent(id)}`);
  }

  sessionState(id: string): Observable<StateView> {
    return this.http.get<StateView>(`${this.base}/sessions/${encodeURIComponent(id)}/state`);
  }

  clearSessionState(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/sessions/${encodeURIComponent(id)}/state`);
  }

  drift(): Observable<DriftView> {
    return this.http.get<DriftView>(`${this.base}/drift`);
  }

  setGlobalDrift(profile: DriftProfile): Observable<DriftView> {
    return this.http.put<DriftView>(`${this.base}/drift/global`, profile);
  }

  setRouteDrift(route: string, profile: DriftProfile): Observable<DriftView> {
    return this.http.put<DriftView>(`${this.base}/drift/routes`, { route, ...profile });
  }

  removeRouteDrift(route: string): Observable<DriftView> {
    return this.http.delete<DriftView>(`${this.base}/drift/routes`, { params: { route } });
  }

  resetDrift(): Observable<DriftView> {
    return this.http.post<DriftView>(`${this.base}/drift/reset`, {});
  }

  ingest(serviceName: string, autoPublish: boolean, logs: unknown[]): Observable<RecordingView> {
    return this.http.post<RecordingView>(`${this.base}/recording/ingest`, {
      service_name: serviceName || undefined, auto_publish: autoPublish, logs,
    });
  }

  recordings(): Observable<RecordingView[]> {
    return this.http.get<RecordingView[]>(`${this.base}/recording/configs`);
  }

  approveRecording(id: string): Observable<RecordingView> {
    return this.http.post<RecordingView>(`${this.base}/recording/configs/${encodeURIComponent(id)}/approve`, {});
  }

  rejectRecording(id: string): Observable<RecordingView> {
    return this.http.post<RecordingView>(`${this.base}/recording/configs/${encodeURIComponent(id)}/reject`, {});
  }
}

/** Human-readable message from any HTTP failure, including the server's validation details. */
export function errorMessage(err: unknown): string {
  if (err instanceof HttpErrorResponse) {
    if (err.status === 0) {
      return 'The platform is not reachable. Check that it is running on the same host.';
    }
    const body = err.error as Partial<ApiError> | null;
    if (body && typeof body === 'object' && body.message) {
      return body.details?.length ? `${body.message}: ${body.details.join('; ')}` : body.message;
    }
    return `${err.status} ${err.statusText}`;
  }
  return String(err);
}

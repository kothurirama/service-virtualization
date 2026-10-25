import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { KeyValuePipe, NgTemplateOutlet } from '@angular/common';
import { Api, errorMessage } from '../../core/api';
import { Distribution, DriftProfile, DriftView } from '../../core/models';

/** Editable copy of a profile; error rate is edited in percent. */
interface Draft {
  enabled: boolean;
  latency: number;
  jitter: number;
  distribution: Distribution;
  errorPct: number;
  status: number;
}

const toDraft = (p: DriftProfile): Draft => ({
  enabled: p.enabled, latency: p.latency_ms, jitter: p.jitter_ms, distribution: p.distribution,
  errorPct: Math.round(p.error_rate * 1000) / 10, status: p.error_status,
});

const toProfile = (d: Draft): DriftProfile => ({
  enabled: d.enabled, latency_ms: d.latency, jitter_ms: d.jitter, distribution: d.distribution,
  error_rate: d.errorPct / 100, error_status: d.status,
});

@Component({
  selector: 'svp-drift',
  imports: [FormsModule, KeyValuePipe, NgTemplateOutlet],
  templateUrl: './drift.html',
  styleUrl: './drift.css',
})
export class DriftPage {
  private readonly api = inject(Api);

  protected readonly view = signal<DriftView | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly note = signal<string | null>(null);

  protected global: Draft = toDraft({ enabled: false, latency_ms: 0, jitter_ms: 0, distribution: 'UNIFORM', error_rate: 0, error_status: 500 });
  protected route = '';
  protected routeDraft: Draft = { enabled: true, latency: 250, jitter: 50, distribution: 'UNIFORM', errorPct: 5, status: 503 };

  protected readonly distributions: { value: Distribution; label: string }[] = [
    { value: 'FIXED', label: 'Fixed' }, { value: 'UNIFORM', label: 'Uniform' }, { value: 'GAUSSIAN', label: 'Gaussian' },
  ];
  protected readonly statuses = [500, 502, 503, 504, 429];

  constructor() {
    this.load();
  }

  private load(): void {
    this.api.drift().subscribe({
      next: v => this.apply(v),
      error: e => this.error.set(errorMessage(e)),
    });
  }

  private apply(v: DriftView): void {
    this.view.set(v);
    this.global = toDraft(v.global);
    if (!this.route && v.known_routes.length) {
      this.route = v.known_routes.find(r => r.startsWith('POST /api/v1/payments')) ?? v.known_routes[0];
    }
  }

  protected describe(d: Draft): string {
    if (!d.enabled) return 'Off. Requests are answered as fast as the engine can.';
    const spread = d.distribution === 'FIXED' || d.jitter === 0 ? ''
      : d.distribution === 'UNIFORM' ? ` ± ${d.jitter} ms (uniform)` : ` with a ${d.jitter} ms standard deviation`;
    const delay = d.latency === 0 && !spread ? 'no added delay' : `${d.latency} ms${spread} of added delay`;
    const faults = d.errorPct > 0 ? `, and ${d.errorPct}% of requests fail with HTTP ${d.status}` : ', and no injected failures';
    return `Each request gets ${delay}${faults}.`;
  }

  protected saveGlobal(): void {
    this.api.setGlobalDrift(toProfile(this.global)).subscribe({
      next: v => { this.apply(v); this.note.set('Global profile applied to all routes without an override.'); this.error.set(null); },
      error: e => this.error.set(errorMessage(e)),
    });
  }

  protected saveRoute(): void {
    if (!this.route) return;
    this.api.setRouteDrift(this.route, toProfile(this.routeDraft)).subscribe({
      next: v => { this.apply(v); this.note.set(`Override saved for ${this.route}.`); this.error.set(null); },
      error: e => this.error.set(errorMessage(e)),
    });
  }

  protected toggleRoute(route: string, p: DriftProfile, enabled: boolean): void {
    this.api.setRouteDrift(route, { ...p, enabled }).subscribe({ next: v => this.apply(v), error: e => this.error.set(errorMessage(e)) });
  }

  protected editRoute(route: string, p: DriftProfile): void {
    this.route = route;
    this.routeDraft = toDraft(p);
  }

  protected removeRoute(route: string): void {
    this.api.removeRouteDrift(route).subscribe({ next: v => this.apply(v), error: e => this.error.set(errorMessage(e)) });
  }

  protected reset(): void {
    this.api.resetDrift().subscribe({
      next: v => { this.apply(v); this.note.set('All drift removed.'); },
      error: e => this.error.set(errorMessage(e)),
    });
  }

  protected summary(p: DriftProfile): string {
    return this.describe(toDraft(p));
  }
}

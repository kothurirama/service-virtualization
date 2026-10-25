import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, errorMessage } from '../../core/api';
import { TrafficEntry, VirtualResponse } from '../../core/models';
import { SessionChip } from '../../shared/session-chip';
import { clockTime, latency, pretty, statusClass } from '../../shared/format';

const MAX_ROWS = 400;

@Component({
  selector: 'svp-traffic',
  imports: [FormsModule, SessionChip],
  templateUrl: './traffic.html',
  styleUrl: './traffic.css',
})
export class TrafficPage {
  private readonly api = inject(Api);

  protected readonly rows = signal<TrafficEntry[]>([]);
  protected readonly selected = signal<TrafficEntry | null>(null);
  protected readonly live = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly sessionFilter = signal('');
  protected readonly stubFilter = signal('');

  protected readonly visible = computed(() => {
    const s = this.sessionFilter().trim();
    const st = this.stubFilter().trim().toLowerCase();
    return this.rows().filter(r =>
      (!s || r.session_id === s) &&
      (!st || (r.matched_stub_id ?? 'no match').toLowerCase().includes(st) || r.path.toLowerCase().includes(st)));
  });
  protected readonly sessionsSeen = computed(() => [...new Set(this.rows().map(r => r.session_id))].sort());

  // request composer
  protected composerOpen = signal(false);
  protected cMethod = 'POST';
  protected cPath = '/api/v1/accounts';
  protected cSession = 'console-demo';
  protected cHeaders = '{\n  "Content-Type": "application/json"\n}';
  protected cBody = '{\n  "account_holder": "Jane Doe",\n  "initial_deposit": 500.00,\n  "currency": "USD"\n}';
  protected readonly cResult = signal<VirtualResponse | null>(null);
  protected readonly cError = signal<string | null>(null);
  protected readonly sending = signal(false);

  protected readonly time = clockTime;
  protected readonly lat = latency;
  protected readonly pretty = pretty;
  protected readonly statusClass = statusClass;

  private lastSeq = -1;

  constructor() {
    this.poll();
    const timer = setInterval(() => this.live() && this.poll(), 1500);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  private poll(): void {
    this.api.traffic(MAX_ROWS, this.lastSeq).subscribe({
      next: fresh => {
        this.error.set(null);
        if (fresh.length === 0) return;
        this.lastSeq = fresh[0].seq;
        this.rows.update(old => [...fresh, ...old].slice(0, MAX_ROWS));
      },
      error: e => this.error.set(errorMessage(e)),
    });
  }

  protected toggleLive(): void {
    this.live.update(v => !v);
    if (this.live()) this.poll();
  }

  protected clear(): void {
    this.api.clearTraffic().subscribe(() => {
      this.rows.set([]);
      this.selected.set(null);
    });
  }

  protected select(row: TrafficEntry): void {
    this.selected.set(this.selected()?.seq === row.seq ? null : row);
  }

  protected send(): void {
    let headers: Record<string, string> | undefined;
    let body: unknown;
    try {
      headers = this.cHeaders.trim() ? JSON.parse(this.cHeaders) : undefined;
      body = this.cBody.trim() ? JSON.parse(this.cBody) : undefined;
    } catch (e) {
      this.cError.set(`Headers and body must be valid JSON (${(e as Error).message}).`);
      return;
    }
    this.sending.set(true);
    this.cError.set(null);
    this.api.execute({ session_id: this.cSession || undefined, protocol: 'HTTP', method: this.cMethod, path: this.cPath, headers, body })
      .subscribe({
        next: r => { this.cResult.set(r); this.sending.set(false); this.poll(); },
        error: e => { this.cError.set(errorMessage(e)); this.sending.set(false); },
      });
  }
}

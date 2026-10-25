import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { KeyValuePipe } from '@angular/common';
import { Api, errorMessage } from '../../core/api';
import { LiveMetrics } from '../../core/live-metrics';
import { SessionView, StateView } from '../../core/models';
import { SessionChip } from '../../shared/session-chip';
import { ago, pretty } from '../../shared/format';

@Component({
  selector: 'svp-sessions',
  imports: [FormsModule, SessionChip, KeyValuePipe],
  templateUrl: './sessions.html',
  styleUrl: './sessions.css',
})
export class SessionsPage {
  private readonly api = inject(Api);
  private readonly live = inject(LiveMetrics);

  protected readonly sessions = signal<SessionView[]>([]);
  protected readonly selectedId = signal<string | null>(null);
  protected readonly state = signal<StateView | null>(null);
  protected readonly openCollection = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly note = signal<string | null>(null);

  protected newId = '';
  protected newTtl: number | null = 1800;
  protected newLabel = '';

  protected readonly selected = computed(() => this.sessions().find(s => s.session_id === this.selectedId()) ?? null);
  protected readonly ago = ago;
  protected readonly pretty = pretty;

  constructor() {
    this.refresh();
    const timer = setInterval(() => this.refresh(), 3000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  protected refresh(): void {
    this.api.sessions().subscribe({
      next: list => { this.sessions.set(list); this.error.set(null); },
      error: e => this.error.set(errorMessage(e)),
    });
    const id = this.selectedId();
    if (id) {
      this.api.sessionState(id).subscribe({
        next: s => this.state.set(s),
        error: () => { this.state.set(null); this.selectedId.set(null); },
      });
    }
  }

  protected select(s: SessionView): void {
    this.selectedId.set(s.session_id);
    this.openCollection.set(Object.keys(s.collections)[0] ?? null);
    this.state.set(null);
    this.note.set(null);
    this.refresh();
  }

  protected create(): void {
    const labels: Record<string, string> = {};
    for (const pair of this.newLabel.split(',')) {
      const [k, v] = pair.split('=').map(x => x?.trim());
      if (k && v) labels[k] = v;
    }
    this.api.createSession(this.newId.trim() || null, this.newTtl, labels).subscribe({
      next: s => {
        this.note.set(`Session ${s.session_id} is ready. Send X-Session-Id: ${s.session_id} with each virtual call.`);
        this.newId = '';
        this.newLabel = '';
        this.selectedId.set(s.session_id);
        this.refresh();
        this.live.refresh();
      },
      error: e => this.error.set(errorMessage(e)),
    });
  }

  protected clearState(id: string): void {
    this.api.clearSessionState(id).subscribe({
      next: () => { this.note.set(`State of ${id} cleared; the session stays open.`); this.refresh(); },
      error: e => this.error.set(errorMessage(e)),
    });
  }

  protected teardown(id: string): void {
    if (!confirm(`Tear down ${id}? Its state is discarded.`)) return;
    this.api.teardownSession(id).subscribe({
      next: () => {
        this.note.set(`Session ${id} torn down.`);
        this.selectedId.set(null);
        this.state.set(null);
        this.refresh();
        this.live.refresh();
      },
      error: e => this.error.set(errorMessage(e)),
    });
  }

  protected ttlText(seconds: number): string {
    if (seconds === 0) return 'never expires';
    return seconds >= 3600 ? `expires after ${Math.round(seconds / 3600)}h idle` : `expires after ${Math.round(seconds / 60)}m idle`;
  }

  protected labelList(labels: Record<string, string>): string {
    return Object.entries(labels).map(([k, v]) => `${k}=${v}`).join(', ');
  }
}

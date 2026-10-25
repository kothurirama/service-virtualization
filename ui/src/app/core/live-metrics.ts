import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { Api } from './api';
import { MetricsView } from './models';

/** Polls the metrics endpoint once for the whole app (the top strip and nav counts share it). */
@Injectable({ providedIn: 'root' })
export class LiveMetrics {
  private readonly api = inject(Api);
  readonly metrics = signal<MetricsView | null>(null);
  readonly reachable = signal(true);
  private timer?: ReturnType<typeof setInterval>;

  start(destroyRef: DestroyRef): void {
    this.refresh();
    this.timer = setInterval(() => this.refresh(), 2500);
    destroyRef.onDestroy(() => clearInterval(this.timer));
  }

  refresh(): void {
    this.api.metrics().subscribe({
      next: m => { this.metrics.set(m); this.reachable.set(true); },
      error: () => this.reachable.set(false),
    });
  }
}

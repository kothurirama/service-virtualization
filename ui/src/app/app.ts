import { Component, DestroyRef, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { LiveMetrics } from './core/live-metrics';
import { latency } from './shared/format';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <div class="shell">
      <nav class="rail" aria-label="Sections">
        <a class="brand" routerLink="/traffic">
          <span class="mark" aria-hidden="true"><i></i><i></i><i></i></span>
          <span>Service<br />virtualization</span>
        </a>
        <ul>
          @for (item of nav; track item.path) {
            <li>
              <a [routerLink]="item.path" routerLinkActive="active" [attr.aria-label]="item.label">
                <span>{{ item.label }}</span>
                @if (item.count(); as c) { <small>{{ c }}</small> }
              </a>
            </li>
          }
        </ul>
        <p class="rail-foot">
          Point systems under test at <code>/virtual</code> and send <code>X-Session-Id</code> per pipeline.
        </p>
      </nav>

      <div class="main">
        <header class="strip" aria-label="Live engine metrics">
          @if (!live.reachable()) {
            <p class="offline">Platform unreachable. Retrying every few seconds.</p>
          } @else if (m(); as mm) {
            <dl>
              <div><dt>Requests</dt><dd>{{ mm.traffic.total_requests.toLocaleString() }}</dd></div>
              <div><dt>Matched</dt><dd>{{ matchedPct() }}</dd></div>
              <div><dt>Engine p50</dt><dd>{{ fmt(mm.traffic.p50_ms) }}</dd></div>
              <div><dt>Engine p99</dt><dd [class.hot]="mm.traffic.p99_ms >= 10">{{ fmt(mm.traffic.p99_ms) }}</dd></div>
              <div><dt>Faults injected</dt><dd>{{ mm.traffic.faults_injected.toLocaleString() }}</dd></div>
              <div><dt>Live sessions</dt><dd>{{ mm.sessions }}</dd></div>
            </dl>
          }
        </header>
        <main>
          <router-outlet />
        </main>
      </div>
    </div>
  `,
  styles: `
    .shell { display: grid; grid-template-columns: 13.5rem minmax(0, 1fr); min-height: 100vh; }
    .rail { background: var(--surface); border-right: 1px solid var(--rule); padding: 1.25rem 0.9rem;
      display: flex; flex-direction: column; gap: 1.5rem; position: sticky; top: 0; height: 100vh; }
    .brand { display: flex; align-items: center; gap: 0.7rem; color: var(--ink); text-decoration: none;
      font-weight: 600; line-height: 1.15; font-size: 0.98rem; padding: 0 0.4rem; }
    .mark { display: grid; grid-template-columns: repeat(3, 0.42rem); gap: 0.18rem; }
    .mark i { height: 1.4rem; border-radius: 2px; background: var(--accent); }
    .mark i:nth-child(2) { opacity: 0.6; transform: translateY(0.25rem); }
    .mark i:nth-child(3) { opacity: 0.32; transform: translateY(0.5rem); }
    ul { list-style: none; margin: 0; padding: 0; display: grid; gap: 0.15rem; }
    ul a { display: flex; justify-content: space-between; align-items: center; gap: 0.5rem; padding: 0.5rem 0.6rem;
      border-radius: var(--radius); color: var(--slate); text-decoration: none; font-weight: 500; }
    ul a:hover { color: var(--ink); background: var(--sunken); }
    ul a.active { color: var(--ink); background: var(--accent-soft); box-shadow: inset 3px 0 0 var(--accent); }
    ul small { font-size: 0.78rem; color: var(--faint); }
    .rail-foot { margin-top: auto; font-size: 0.78rem; color: var(--faint); padding: 0 0.4rem; line-height: 1.5; }
    .rail-foot code { font-size: 0.74rem; color: var(--slate); }
    .main { min-width: 0; display: flex; flex-direction: column; }
    .strip { border-bottom: 1px solid var(--rule); background: var(--surface); padding: 0.7rem 1.75rem; min-height: 3.6rem;
      position: sticky; top: 0; z-index: 2; }
    dl { display: flex; flex-wrap: wrap; gap: 0.4rem 2.25rem; margin: 0; }
    dl div { display: grid; gap: 0.05rem; }
    dt { font-size: 0.75rem; color: var(--slate); }
    dd { margin: 0; font-size: 1.05rem; font-weight: 600; }
    dd.hot { color: var(--bad); }
    .offline { color: var(--bad); font-weight: 500; padding-top: 0.5rem; }
    @media (max-width: 960px) {
      .shell { grid-template-columns: 1fr; }
      .rail { position: static; height: auto; flex-direction: row; flex-wrap: wrap; align-items: center;
        border-right: 0; border-bottom: 1px solid var(--rule); padding: 0.75rem 1rem; gap: 0.75rem; }
      ul { display: flex; flex-wrap: wrap; }
      .rail-foot { display: none; }
      .strip { position: static; padding: 0.7rem 1rem; }
      dl { gap: 0.4rem 1.25rem; }
    }
  `,
})
export class App {
  protected readonly live = inject(LiveMetrics);
  protected readonly m = this.live.metrics;
  protected readonly fmt = latency;

  protected readonly matchedPct = computed(() => {
    const t = this.m()?.traffic;
    if (!t || t.total_requests === 0) return '–';
    return `${((t.matched / t.total_requests) * 100).toFixed(1)}%`;
  });

  protected readonly nav = [
    { path: '/traffic', label: 'Traffic', count: () => null },
    { path: '/stubs', label: 'Stubs', count: () => this.m()?.stubs ?? null },
    { path: '/sessions', label: 'Sessions', count: () => this.m()?.sessions ?? null },
    { path: '/drift', label: 'Drift', count: () => null },
    { path: '/recording', label: 'Recording', count: () => null },
  ];

  constructor() {
    this.live.start(inject(DestroyRef));
  }
}

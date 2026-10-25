import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, errorMessage } from '../../core/api';
import { LiveMetrics } from '../../core/live-metrics';
import { StubDefinition, StubSource, StubView } from '../../core/models';
import { ago, pretty } from '../../shared/format';

const NEW_STUB: StubDefinition = {
  stub_id: 'stub_new_v1',
  description: 'Describe what this virtual endpoint simulates',
  enabled: true,
  priority: 0,
  tags: [],
  source: 'MANUAL',
  request_matcher: { protocol: 'HTTP', method: 'GET', path: '/api/v1/example/{id}' },
  response_template: { status_code: 200, body: { id: '{{request.path.id}}', status: 'OK' } },
};

@Component({
  selector: 'svp-stubs',
  imports: [FormsModule],
  templateUrl: './stubs.html',
  styleUrl: './stubs.css',
})
export class StubsPage {
  private readonly api = inject(Api);
  private readonly live = inject(LiveMetrics);

  protected readonly stubs = signal<StubView[]>([]);
  protected readonly query = signal('');
  protected readonly source = signal<'' | StubSource>('');
  protected readonly selectedId = signal<string | null>(null);
  protected readonly isNew = signal(false);
  protected readonly loadError = signal<string | null>(null);

  protected editorText = '';
  protected readonly editorError = signal<string | null>(null);
  protected readonly editorNote = signal<string | null>(null);
  protected readonly busy = signal(false);

  protected readonly selected = computed(() => this.stubs().find(s => s.stub.stub_id === this.selectedId()) ?? null);
  protected readonly counts = computed(() => {
    const all = this.stubs();
    return { total: all.length, enabled: all.filter(s => s.stub.enabled).length };
  });

  protected readonly ago = ago;
  protected readonly help: { t?: string; c?: string }[][] = [
    [{ t: 'Path: ' }, { c: '/accounts/{id}' }, { t: ' captures a segment, ' }, { c: '*' }, { t: ' matches one segment, ' },
      { c: '**' }, { t: ' the rest of the path.' }],
    [{ t: 'Matchers in headers, query and body_jsonpath: a literal, or ' }, { c: '{"matches": "^Bearer .+"}' }, { t: ', ' },
      { c: '{"exists": false}' }, { t: ', ' }, { c: '{"gt": 10000}' }, { t: ', ' }, { c: '{"contains": "x"}' }, { t: '.' }],
    [{ t: 'Templates: ' }, { c: '{{request.body.amount}}' }, { t: ', ' }, { c: '{{request.path.id}}' }, { t: ', ' },
      { c: '{{request.headers.name}}' }, { t: ', ' }, { c: '{{generate.id:ACC}}' }, { t: ', ' }, { c: "{{a ?: 'default'}}" }, { t: '.' }],
    [{ t: 'State: state_action.op is PUT, GET, MERGE, DELETE or LIST, always scoped to the caller\'s session.' }],
  ];
  protected readonly sources: { value: '' | StubSource; label: string }[] = [
    { value: '', label: 'All' }, { value: 'MANUAL', label: 'Manual' }, { value: 'SEED', label: 'Seed' },
    { value: 'RECORDED', label: 'Recorded' },
  ];

  constructor() {
    this.load();
  }

  protected load(): void {
    this.api.stubs(this.query(), this.source()).subscribe({
      next: list => { this.stubs.set(list); this.loadError.set(null); },
      error: e => this.loadError.set(errorMessage(e)),
    });
  }

  protected search(q: string): void {
    this.query.set(q);
    this.load();
  }

  protected filterSource(s: '' | StubSource): void {
    this.source.set(s);
    this.load();
  }

  protected open(view: StubView): void {
    this.isNew.set(false);
    this.selectedId.set(view.stub.stub_id);
    this.editorText = pretty(view.stub);
    this.editorError.set(null);
    this.editorNote.set(null);
  }

  protected create(): void {
    this.isNew.set(true);
    this.selectedId.set(null);
    this.editorText = pretty(NEW_STUB);
    this.editorError.set(null);
    this.editorNote.set(null);
  }

  private parse(): StubDefinition | null {
    try {
      return JSON.parse(this.editorText) as StubDefinition;
    } catch (e) {
      this.editorError.set(`The stub is not valid JSON: ${(e as Error).message}`);
      return null;
    }
  }

  protected validate(): void {
    const def = this.parse();
    if (!def) return;
    this.api.validateStub(def).subscribe({
      next: r => { this.editorError.set(null); this.editorNote.set(`Valid. Route ${r.route}; checks ${r.matched_rules.join(', ')}.`); },
      error: e => { this.editorNote.set(null); this.editorError.set(errorMessage(e)); },
    });
  }

  protected save(): void {
    const def = this.parse();
    if (!def) return;
    this.busy.set(true);
    this.api.saveStub(def).subscribe({
      next: v => {
        this.busy.set(false);
        this.isNew.set(false);
        this.selectedId.set(v.stub.stub_id);
        this.editorText = pretty(v.stub);
        this.editorError.set(null);
        this.editorNote.set(`Saved ${v.stub.stub_id}. It is live immediately.`);
        this.load();
        this.live.refresh();
      },
      error: e => { this.busy.set(false); this.editorNote.set(null); this.editorError.set(errorMessage(e)); },
    });
  }

  protected toggle(view: StubView, enabled: boolean): void {
    this.api.setStubEnabled(view.stub.stub_id, enabled).subscribe({
      next: () => this.load(),
      error: e => this.loadError.set(errorMessage(e)),
    });
  }

  protected remove(view: StubView): void {
    if (!confirm(`Delete ${view.stub.stub_id}? Pipelines that rely on it will get "no stub matched".`)) return;
    this.api.deleteStub(view.stub.stub_id).subscribe({
      next: () => { this.selectedId.set(null); this.editorText = ''; this.load(); this.live.refresh(); },
      error: e => this.editorError.set(errorMessage(e)),
    });
  }
}

import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DatePipe, DecimalPipe } from '@angular/common';
import { Api, errorMessage } from '../../core/api';
import { LiveMetrics } from '../../core/live-metrics';
import { RecordingView } from '../../core/models';
import { pretty } from '../../shared/format';

const SAMPLE = [
  {
    method: 'POST', path: '/api/v1/payments',
    request: {
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZWxsZXIifQ.c2lnbmF0dXJl' },
      body: { merchant_id: 'MERCH_1092', amount: 150.0, currency: 'USD', card: { number: '4111111111111111', cvv: '123', expiry: '12/28' } },
    },
    response: {
      status_code: 200, headers: { 'Content-Type': 'application/json', 'Set-Cookie': 'sid=a8Kd93LmQ2zX71pv' },
      body: { transaction_id: 'TXN-881920', card_last_four: '1111', card_type: 'VISA', customer_email: 'jane.doe@example.com', amount: 150.0, status: 'SUCCESS' },
    },
  },
  {
    method: 'GET', path: '/api/v1/customers/C-1009',
    response: {
      status_code: 200, headers: { 'Content-Type': 'application/json' },
      body: { customer_id: 'C-1009', full_name: 'Rahul Sharma', ssn: '536-22-1987', phone: '+14155550132', segment: 'RETAIL' },
    },
  },
];

@Component({
  selector: 'svp-recording',
  imports: [FormsModule, DatePipe, DecimalPipe],
  templateUrl: './recording.html',
  styleUrl: './recording.css',
})
export class RecordingPage {
  private readonly api = inject(Api);
  private readonly live = inject(LiveMetrics);

  protected serviceName = 'payment-svc';
  protected autoPublish = true;
  protected logsText = pretty(SAMPLE);

  protected readonly configs = signal<RecordingView[]>([]);
  protected readonly selectedId = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly selected = computed(() => this.configs().find(c => c.stub_config_id === this.selectedId()) ?? null);
  protected readonly pretty = pretty;

  constructor() {
    this.load();
  }

  private load(): void {
    this.api.recordings().subscribe({
      next: list => this.configs.set(list),
      error: e => this.error.set(errorMessage(e)),
    });
  }

  protected ingest(): void {
    let logs: unknown;
    try {
      logs = JSON.parse(this.logsText);
    } catch (e) {
      this.error.set(`Recorded traffic must be a JSON array of exchanges (${(e as Error).message}).`);
      return;
    }
    if (!Array.isArray(logs)) {
      this.error.set('Recorded traffic must be a JSON array of exchanges.');
      return;
    }
    this.busy.set(true);
    this.api.ingest(this.serviceName, this.autoPublish, logs).subscribe({
      next: r => {
        this.busy.set(false);
        this.error.set(null);
        this.selectedId.set(r.stub_config_id);
        this.load();
        this.live.refresh();
      },
      error: e => { this.busy.set(false); this.error.set(errorMessage(e)); },
    });
  }

  protected approve(id: string): void {
    this.api.approveRecording(id).subscribe({
      next: () => { this.load(); this.live.refresh(); },
      error: e => this.error.set(errorMessage(e)),
    });
  }

  protected reject(id: string): void {
    this.api.rejectRecording(id).subscribe({ next: () => this.load(), error: e => this.error.set(errorMessage(e)) });
  }

  protected typeLabel(t: string): string {
    const labels: Record<string, string> = {
      PAN: 'Card number', CVV: 'CVV', PIN: 'PIN', TRACK_DATA: 'Track data', CARD_EXPIRY: 'Card expiry', SSN: 'SSN',
      NATIONAL_ID: 'National ID', EMAIL: 'Email', PHONE: 'Phone', PERSON_NAME: 'Person name', DATE_OF_BIRTH: 'Date of birth',
      ADDRESS: 'Street address', BANK_ACCOUNT: 'Bank account', PASSWORD: 'Password', API_KEY: 'API key',
      ACCESS_TOKEN: 'Access token', JWT: 'JWT', PRIVATE_KEY: 'Private key', AUTH_HEADER: 'Auth header',
      HIGH_ENTROPY_SECRET: 'Unrecognised secret',
    };
    return labels[t] ?? t;
  }

  protected statusLabel(s: string): string {
    return { PUBLISHED: 'Published', PENDING_REVIEW: 'Needs review', DRAFT: 'Draft', REJECTED: 'Rejected' }[s] ?? s;
  }
}

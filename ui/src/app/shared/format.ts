/** Deterministic hue per session id, so a pipeline keeps its colour on every screen. */
export function sessionHue(id: string | null | undefined): number {
  const s = id ?? 'default';
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return Math.abs(h) % 360;
}

/** Engine latency is microseconds most of the time; show it in the unit that reads best. */
export function latency(ms: number | null | undefined): string {
  if (ms == null) return '–';
  if (ms < 1) return `${Math.max(1, Math.round(ms * 1000))} µs`;
  return `${ms.toFixed(ms < 10 ? 2 : 0)} ms`;
}

export function clockTime(epochMillis: number): string {
  const d = new Date(epochMillis);
  const pad = (n: number, w = 2) => n.toString().padStart(w, '0');
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad(d.getMilliseconds(), 3)}`;
}

export function ago(iso: string | null | undefined): string {
  if (!iso) return 'never';
  const seconds = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000));
  if (seconds < 5) return 'just now';
  if (seconds < 60) return `${seconds}s ago`;
  if (seconds < 3600) return `${Math.round(seconds / 60)}m ago`;
  return `${Math.round(seconds / 3600)}h ago`;
}

export function pretty(value: unknown): string {
  return value === undefined ? '' : JSON.stringify(value, null, 2);
}

export function statusClass(code: number): string {
  if (code >= 500) return 's5';
  if (code >= 400) return 's4';
  if (code >= 300) return 's3';
  return 's2';
}

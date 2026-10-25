import { Component, computed, input } from '@angular/core';
import { sessionHue } from './format';

/** A session's colour swatch and id: the visual signature of isolation across the console. */
@Component({
  selector: 'svp-session',
  template: `<span class="swatch" [style.--h]="hue()"></span><span class="id">{{ id() || 'default' }}</span>`,
  styles: `
    :host { display: inline-flex; align-items: center; gap: 0.45rem; min-width: 0; }
    .swatch { flex: none; width: 0.7rem; height: 0.7rem; border-radius: 2px;
      background: hsl(var(--h) 58% var(--swatch-l)); box-shadow: inset 0 0 0 1px rgb(0 0 0 / 0.12); }
    .id { font-family: var(--mono); font-size: 0.82em; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  `,
})
export class SessionChip {
  readonly id = input<string | null | undefined>();
  protected readonly hue = computed(() => sessionHue(this.id()));
}

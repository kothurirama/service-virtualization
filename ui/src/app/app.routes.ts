import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'traffic' },
  { path: 'traffic', title: 'Traffic', loadComponent: () => import('./pages/traffic/traffic').then(m => m.TrafficPage) },
  { path: 'stubs', title: 'Stubs', loadComponent: () => import('./pages/stubs/stubs').then(m => m.StubsPage) },
  { path: 'sessions', title: 'Sessions', loadComponent: () => import('./pages/sessions/sessions').then(m => m.SessionsPage) },
  { path: 'drift', title: 'Drift', loadComponent: () => import('./pages/drift/drift').then(m => m.DriftPage) },
  { path: 'recording', title: 'Recording', loadComponent: () => import('./pages/recording/recording').then(m => m.RecordingPage) },
  { path: '**', redirectTo: 'traffic' },
];

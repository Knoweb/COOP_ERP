import React from 'react';
import ReactDOM from 'react-dom/client';
import { App } from './App';
import { ensureLocaleData } from './shell/i18n/localeData';
// Order matters: the fonts and the tokens first, then the rules that use them.
import './design/fonts.css';
import './design/tokens.css';
import './shell/shell.css';

// The Sinhala and Tamil number and date data first, where the browser lacks it (localeData.ts).
void ensureLocaleData().then(() =>
  ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
      <App />
    </React.StrictMode>
  )
);

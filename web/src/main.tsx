import React from 'react';
import ReactDOM from 'react-dom/client';
import { App } from './App';
// Order matters: the fonts and the tokens first, then the rules that use them.
import './design/fonts.css';
import './design/tokens.css';
import './shell/shell.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);

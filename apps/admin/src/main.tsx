import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { FirebaseAuthProvider } from '@/components/auth/FirebaseAuthProvider';
import { App } from './App';
import '@/app/globals.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter>
      <FirebaseAuthProvider>
        <App />
      </FirebaseAuthProvider>
    </BrowserRouter>
  </React.StrictMode>,
);

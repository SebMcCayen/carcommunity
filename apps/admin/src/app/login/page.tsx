/**
 * Admin login page.
 *
 * Provides Google Sign-In via Firebase Authentication. After sign-in, the
 * Firebase `admin: true` custom claim is checked. Users without the claim
 * are signed out immediately and shown an error message.
 *
 * Security:
 * - Authorization is enforced server-side on every API request.
 * - The admin claim is set only by trusted backend code; it cannot be forged.
 * - This page only handles the UX flow — it does NOT constitute a security boundary.
 */

import { useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { signInWithGoogle } from '@/lib/auth';
import { brand } from '@/config/brand';

export default function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Redirect back to the page the user tried to access before being sent here.
  const from = (location.state as { from?: { pathname: string } } | null)?.from?.pathname ?? '/';

  async function handleGoogleSignIn() {
    setLoading(true);
    setError(null);

    try {
      await signInWithGoogle();
      navigate(from, { replace: true });
    } catch (err: unknown) {
      if (err instanceof Error && err.message === 'not_admin') {
        setError(
          'Ditt Google-konto saknar admin-behörighet. Kontakta en systemadministratör.',
        );
      } else if (err instanceof Error && err.message.includes('popup-closed')) {
        // User dismissed the popup — not an error worth showing.
        setError(null);
      } else {
        setError('Inloggningen misslyckades. Försök igen.');
      }
    } finally {
      setLoading(false);
    }
  }

  return (
    <main
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        minHeight: '100vh',
        gap: '1.5rem',
        padding: '2rem',
      }}
    >
      <h1 style={{ fontSize: '1.5rem', fontWeight: 700 }}>{brand.adminTitle}</h1>

      {error && (
        <p
          role="alert"
          style={{ color: '#b91c1c', maxWidth: '24rem', textAlign: 'center' }}
        >
          {error}
        </p>
      )}

      <button
        type="button"
        onClick={() => void handleGoogleSignIn()}
        disabled={loading}
        aria-busy={loading}
        style={{
          padding: '0.75rem 2rem',
          fontSize: '1rem',
          fontWeight: 600,
          cursor: loading ? 'not-allowed' : 'pointer',
          opacity: loading ? 0.6 : 1,
        }}
      >
        {loading ? 'Loggar in…' : 'Logga in med Google'}
      </button>
    </main>
  );
}

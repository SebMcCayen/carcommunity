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
import styles from './page.module.css';

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
    <main className={styles.page}>
      <h1 className={styles.title}>{brand.adminTitle}</h1>

      {error && (
        <p role="alert" className={styles.error}>
          {error}
        </p>
      )}

      <button
        type="button"
        onClick={() => void handleGoogleSignIn()}
        disabled={loading}
        aria-busy={loading}
        className={styles.signInButton}
      >
        {loading ? 'Loggar in…' : 'Logga in med Google'}
      </button>
    </main>
  );
}

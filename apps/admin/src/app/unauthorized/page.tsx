import { Link } from 'react-router-dom';
import { signOut } from '@/lib/auth';
import { brand } from '@/config/brand';

/**
 * Shown when a signed-in user does not hold the `admin: true` Firebase custom
 * claim. They cannot access the admin portal until an owner grants them admin
 * access via the Firebase Admin SDK.
 */
export default function UnauthorizedPage() {
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
        textAlign: 'center',
      }}
    >
      <h1 style={{ fontSize: '1.5rem', fontWeight: 700 }}>{brand.adminTitle}</h1>

      <p style={{ maxWidth: '28rem', lineHeight: 1.6 }}>
        Ditt konto saknar admin-behörighet för{' '}
        <strong>{brand.adminTitle}</strong>. Kontakta en systemadministratör
        för att få åtkomst.
      </p>

      <p
        role="note"
        style={{
          fontSize: '0.875rem',
          color: '#6d6c6d',
          maxWidth: '28rem',
          lineHeight: 1.6,
        }}
      >
        Om du precis fått admin-behörighet, logga ut och logga in igen för att
        uppdatera dina inloggningsuppgifter.
      </p>

      <div style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap', justifyContent: 'center' }}>
        <button
          type="button"
          onClick={() => void signOut()}
          style={{
            padding: '0.625rem 1.5rem',
            fontSize: '0.875rem',
            fontWeight: 600,
            cursor: 'pointer',
            border: '1px solid #b4b1ad',
            borderRadius: '6px',
            background: 'transparent',
          }}
        >
          Logga ut
        </button>
        <Link
          to="/login"
          style={{
            padding: '0.625rem 1.5rem',
            fontSize: '0.875rem',
            fontWeight: 600,
            textDecoration: 'none',
            border: '1px solid #b4b1ad',
            borderRadius: '6px',
          }}
        >
          Tillbaka till inloggning
        </Link>
      </div>
    </main>
  );
}

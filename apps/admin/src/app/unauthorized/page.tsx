import { Link } from 'react-router-dom';
import { signOut } from '@/lib/auth';
import { brand } from '@/config/brand';
import styles from './page.module.css';

/**
 * Shown when a signed-in user does not hold the `admin: true` Firebase custom
 * claim. They cannot access the admin portal until an owner grants them admin
 * access via the Firebase Admin SDK.
 */
export default function UnauthorizedPage() {
  return (
    <main className={styles.page}>
      <h1 className={styles.title}>{brand.adminTitle}</h1>

      <p className={styles.body}>
        Ditt konto saknar admin-behörighet för{' '}
        <strong>{brand.adminTitle}</strong>. Kontakta en systemadministratör
        för att få åtkomst.
      </p>

      <p role="note" className={styles.note}>
        Om du precis fått admin-behörighet, logga ut och logga in igen för att
        uppdatera dina inloggningsuppgifter.
      </p>

      <div className={styles.actions}>
        <button type="button" onClick={() => void signOut()} className={styles.button}>
          Logga ut
        </button>
        <Link to="/login" className={styles.button}>
          Tillbaka till inloggning
        </Link>
      </div>
    </main>
  );
}

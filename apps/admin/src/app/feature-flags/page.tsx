import { getFeatureFlagRows } from '@/features/feature-flags';
import styles from './page.module.css';

export default function FeatureFlagsPage() {
  const rows = getFeatureFlagRows();

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>Feature Flags</h1>
        <p className={styles.subtitle}>
          Platform feature flags — values are currently static defaults served by the API.
        </p>
      </div>

      <div className={styles.notice} role="note">
        <span aria-hidden="true">⚠</span>
        <p>
          <strong>Read-only view.</strong> Editing feature flags via the admin portal is not
          implemented yet. Changes must be made in the API source until a management UI is added.
          All flag values shown here are the static defaults returned by{' '}
          <code>GET /v1/feature-flags</code>.
        </p>
      </div>

      <section className={styles.section}>
        <h2 className={styles.sectionTitle}>Current flags</h2>
        <table className={styles.table}>
          <thead>
            <tr>
              <th scope="col">Flag key</th>
              <th scope="col">Status</th>
              <th scope="col">Edit</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(({ key, enabled }) => (
              <tr key={key}>
                <td>
                  <span className={styles.flagKey}>{key}</span>
                </td>
                <td>
                  <span
                    className={`${styles.badge} ${enabled ? styles.badgeEnabled : styles.badgeDisabled}`}
                  >
                    {enabled ? 'Enabled' : 'Disabled'}
                  </span>
                </td>
                <td>
                  <span className={styles.editNote}>Not available yet</span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </div>
  );
}

import styles from './PlaceholderPage.module.css';

interface PlaceholderPageProps {
  title: string;
  description?: string;
  behaviors?: string[];
  todoNote?: string;
}

export function PlaceholderPage({
  title,
  description,
  behaviors,
  todoNote,
}: PlaceholderPageProps) {
  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>{title}</h1>
        {description && <p className={styles.description}>{description}</p>}
      </div>

      {behaviors && behaviors.length > 0 && (
        <section className={styles.section} aria-labelledby="planned-heading">
          <h2 id="planned-heading" className={styles.sectionTitle}>
            Planned functionality
          </h2>
          <ul className={styles.behaviorList}>
            {behaviors.map((b) => (
              <li key={b} className={styles.behaviorItem}>
                <span className={styles.bullet} aria-hidden="true">
                  →
                </span>
                {b}
              </li>
            ))}
          </ul>
        </section>
      )}

      <div className={styles.todoBanner} role="note">
        <span className={styles.todoIcon} aria-hidden="true">
          ⚠
        </span>
        <p className={styles.todoText}>
          {todoNote ??
            'Placeholder page. Real data and actions require backend API integration and Microsoft Entra ID authentication.'}
        </p>
      </div>
    </div>
  );
}

import styles from './PlaceholderPage.module.css';
import { translate } from '@/i18n';

interface PlaceholderPageProps {
  title: string;
  description?: string;
  behaviors?: string[];
  todoNoteKey?: string;
}

export function PlaceholderPage({
  title,
  description,
  behaviors,
  todoNoteKey,
}: PlaceholderPageProps) {
  const t = (key: string) => translate('sv', key);

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>{title}</h1>
        {description && <p className={styles.description}>{description}</p>}
      </div>

      {behaviors && behaviors.length > 0 && (
        <section className={styles.section} aria-labelledby="planned-heading">
          <h2 id="planned-heading" className={styles.sectionTitle}>
            {t('placeholder.plannedFunctionality')}
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
          {t(todoNoteKey ?? 'placeholder.defaultTodoNote')}
        </p>
      </div>
    </div>
  );
}

import styles from './StatCard.module.css';

interface StatCardProps {
  label: string;
  value: string | number;
  note?: string;
  variant?: 'default' | 'warning' | 'error' | 'success';
}

export function StatCard({
  label,
  value,
  note,
  variant = 'default',
}: StatCardProps) {
  return (
    <div className={`${styles.card}${variant === 'default' ? '' : ` ${styles[variant]}`}`}>
      <span className={styles.label}>{label}</span>
      <span className={styles.value}>{value}</span>
      {note && <span className={styles.note}>{note}</span>}
    </div>
  );
}

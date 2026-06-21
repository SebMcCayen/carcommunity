import type { EventStatus } from '@/features/events';
import { formatEventStatus } from '@/features/events';
import styles from './EventStatusBadge.module.css';

interface EventStatusBadgeProps {
  status: EventStatus;
}

export function EventStatusBadge({ status }: EventStatusBadgeProps) {
  return (
    <span className={`${styles.statusBadge} ${styles[status]}`}>
      {formatEventStatus(status)}
    </span>
  );
}

import { useEffect, useState, type ReactNode } from 'react';
import { Building2, MapPin, Phone, Globe } from 'lucide-react';
import { Link } from 'react-router-dom';
import { translate } from '@/i18n';
import { readLogo } from '@/features/partners/portal';
import styles from './portal.module.css';
export const p = (key: string) => translate('sv', `partnerPortal.${key}`);
export function PortalHeader({
  title,
  description,
  parent,
}: {
  title: string;
  description?: string;
  parent?: { to: string; label: string };
}) {
  return (
    <header className={styles.header}>
      <div className={styles.breadcrumb}>
        <Link to="/partners">{p('title')}</Link> /{' '}
        {parent ? <Link to={parent.to}>{parent.label}</Link> : title}
      </div>
      <h1>{title}</h1>
      {description && <p>{description}</p>}
    </header>
  );
}
export function Status({ value }: { value: string }) {
  return <span className={styles.badge}>{p(value)}</span>;
}
export function PartnerLogo({
  path,
  base64,
  name,
}: {
  path?: string | null;
  base64?: string | null;
  name: string;
}) {
  const [result, setResult] = useState<{ path: string; url: string } | null>(null);
  useEffect(() => {
    if (!path || base64) return;
    let active = true;
    let objectUrl: string | undefined;
    readLogo(path)
      .then((blob) => {
        objectUrl = URL.createObjectURL(blob);
        if (active) setResult({ path, url: objectUrl });
        else URL.revokeObjectURL(objectUrl);
      })
      .catch(() => {
        if (active) setResult(null);
      });
    return () => {
      active = false;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [path, base64]);
  const url = base64
    ? `data:image/png;base64,${base64}`
    : result && result.path === path
      ? result.url
      : null;
  return url ? (
    <img className={styles.logo} src={url} alt={name} />
  ) : (
    <div className={styles.logoFallback} aria-label={p('logoMissing')}>
      <Building2 size={34} aria-hidden="true" />
    </div>
  );
}
export function PartnerPreview({
  name,
  category,
  description,
  address,
  phone,
  website,
  logoPath,
  logoPreviewBase64,
  children,
}: {
  name: string;
  category?: string;
  description?: string | null;
  address?: string | null;
  phone?: string | null;
  website?: string | null;
  logoPath?: string | null;
  logoPreviewBase64?: string | null;
  children?: ReactNode;
}) {
  return (
    <aside className={styles.preview} aria-label={p('preview')}>
      <h2>{p('preview')}</h2>
      <div className={styles.phone}>
        <PartnerLogo name={name} path={logoPath} base64={logoPreviewBase64} />
        <h3>{name}</h3>
        {category && (
          <span className={styles.muted}>
            {p(
              `category_${['workshop', 'car_care', 'parts', 'tires', 'charging', 'restaurant', 'retail', 'other'].includes(category) ? category : 'other'}`,
            )}
          </span>
        )}
        {description && <p>{description}</p>}
        {address && (
          <p>
            <MapPin size={16} aria-hidden="true" /> {address}
          </p>
        )}
        {phone && (
          <p>
            <Phone size={16} aria-hidden="true" /> {phone}
          </p>
        )}
        {website && (
          <p>
            <Globe size={16} aria-hidden="true" /> {website}
          </p>
        )}
        {children ?? <p className={styles.muted}>{p('previewEmpty')}</p>}
      </div>
      <p className={styles.muted}>{p('previewNotice')}</p>
    </aside>
  );
}
export function ErrorNotice({ error, retry }: { error: string | null; retry?: () => void }) {
  return error ? (
    <div role="alert" className={`${styles.alert} ${styles.error}`}>
      {error}
      {retry && (
        <button className={styles.linkButton} onClick={retry}>
          {p('retry')}
        </button>
      )}
    </div>
  ) : null;
}

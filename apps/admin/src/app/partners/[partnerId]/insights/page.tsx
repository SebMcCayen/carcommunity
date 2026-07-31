import { Link } from 'react-router-dom';
import { useParams } from 'react-router-dom';
import { useCallback, useEffect, useState } from 'react';

import {
  ADMIN_INSIGHTS_PERIODS,
  adminGetPartnerInsightsSummary,
  type AdminInsightsPeriod,
  type PartnerInsightsMetric,
  type PartnerInteractionType,
} from '@/features/partner-insights';
import { ApiError } from '@/lib/errors';
import { translate } from '@/i18n';

import styles from '../../../kronjakt/page.module.css';

const t = (key: string) => translate('sv', key);

const PERIOD_LABELS: Record<AdminInsightsPeriod, string> = {
  last_7_days: t('partnerInsights.periodLast7Days'),
  last_30_days: t('partnerInsights.periodLast30Days'),
  current_month: t('partnerInsights.periodCurrentMonth'),
  previous_month: t('partnerInsights.periodPreviousMonth'),
};

const METRIC_LABELS: Record<PartnerInteractionType, string> = {
  map_view: t('partnerInsights.metricMapView'),
  profile_view: t('partnerInsights.metricProfileView'),
  navigate: t('partnerInsights.metricNavigate'),
  phone: t('partnerInsights.metricPhone'),
  website: t('partnerInsights.metricWebsite'),
  offer_view: t('partnerInsights.metricOfferView'),
  show_code: t('partnerInsights.metricShowCode'),
  save_offer: t('partnerInsights.metricSaveOffer'),
  anonymous_pass_by: t('partnerInsights.metricAnonymousPassBy'),
};

function renderMetricValue(metric: PartnerInsightsMetric): string {
  if (metric.status === 'insufficient_data') {
    return t('partnerInsights.insufficientData');
  }
  if (metric.status === 'no_data') {
    return t('partnerInsights.noData');
  }
  return String(metric.totalCount);
}

export default function PartnerInsightsPage() {
  const { partnerId } = useParams<{ partnerId: string }>();
  const [period, setPeriod] = useState<AdminInsightsPeriod>('last_30_days');
  const [metrics, setMetrics] = useState<PartnerInsightsMetric[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadSummary = useCallback(async () => {
    if (!partnerId) {
      return;
    }

    setLoading(true);
    setError(null);
    try {
      const summary = await adminGetPartnerInsightsSummary(partnerId, period);
      setMetrics(summary.metrics);
    } catch (err) {
      const apiError = err as ApiError;
      setError(apiError.message ?? t('partnerInsights.error'));
    } finally {
      setLoading(false);
    }
  }, [partnerId, period]);

  useEffect(() => {
    void loadSummary();
  }, [loadSummary]);

  return (
    <div className={styles.page}>
      <Link to="/partners" className={styles.backLink}>
        ← {t('partnerInsights.backToPartner')}
      </Link>

      <div className={styles.header}>
        <h1 className={styles.title}>{t('partnerInsights.pageTitle')}</h1>
        <p className={styles.description}>{t('partnerInsights.pageDescription')}</p>
        <p className={styles.description}>{t('partnerInsights.thresholdNote')}</p>
      </div>

      <div className={styles.formRow} style={{ maxWidth: 320 }}>
        <label className={styles.label}>
          {t('partnerInsights.selectPeriod')}
          <select
            className={styles.input}
            value={period}
            onChange={(event) => setPeriod(event.target.value as AdminInsightsPeriod)}
          >
            {ADMIN_INSIGHTS_PERIODS.map((value) => (
              <option key={value} value={value}>
                {PERIOD_LABELS[value]}
              </option>
            ))}
          </select>
        </label>
      </div>

      {loading ? <div className={styles.loadingState}>{t('partnerInsights.loading')}</div> : null}

      {!loading && error ? (
        <div className={styles.errorState} role="alert">
          <p>{error}</p>
          <button
            type="button"
            onClick={() => void loadSummary()}
            style={{
              marginTop: 'var(--space-3)',
              padding: 'var(--space-2) var(--space-4)',
              borderRadius: 'var(--radius-sm)',
              // Was `--color-border` / `--color-text`, neither of which was ever
              // declared: the border collapsed to `none` and the retry button
              // rendered as bare text.
              border: '1px solid var(--border-strong)',
              background: 'transparent',
              color: 'var(--text-primary)',
              cursor: 'pointer',
            }}
          >
            {t('partnerInsights.retry')}
          </button>
        </div>
      ) : null}

      {!loading && !error ? (
        <div
          style={{
            display: 'grid',
            gap: 'var(--space-4)',
            gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))',
          }}
        >
          {metrics.map((metric) => (
            <article
              key={metric.interactionType}
              style={{
                // Was `--color-border` / `--color-surface`, neither declared, so
                // these metric cards had no border and a transparent fill.
                border: '1px solid var(--border-color)',
                borderRadius: 'var(--radius-md)',
                padding: 'var(--space-4)',
                background: 'var(--bg-surface)',
                boxShadow: 'var(--elevation-1)',
              }}
            >
              <h2 style={{ margin: 0, marginBottom: 'var(--space-2)', fontSize: 'var(--text-md)' }}>
                {METRIC_LABELS[metric.interactionType]}
              </h2>
              <p style={{ margin: 0, fontSize: 'var(--text-2xl)', fontWeight: 'var(--fw-semibold)' }}>
                {renderMetricValue(metric)}
              </p>
            </article>
          ))}
        </div>
      ) : null}
    </div>
  );
}

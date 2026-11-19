import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  getStatistics,
  statisticsPeriods,
  type PortalStatistics,
} from '@/features/partners/portal';
import { formatDateOnly } from '@/lib/format';
import { p, PortalHeader, ErrorNotice } from '../portal-components';
import styles from '../portal.module.css';
export default function PartnerStatisticsPage() {
  const [params, setParams] = useSearchParams();
  const companyId = params.get('partnerId') ?? '';
  const [input, setInput] = useState(companyId);
  const [period, setPeriod] = useState('all');
  const [offerId, setOfferId] = useState('');
  const [result, setResult] = useState<PortalStatistics | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const generation = useRef(0);
  const load = useCallback(async () => {
    const run = ++generation.current;
    setLoading(true);
    setError(null);
    try {
      const data = await getStatistics(companyId || undefined, period);
      if (run === generation.current) setResult(data);
    } catch (err) {
      if (run === generation.current) setError(err instanceof Error ? err.message : p('error'));
    } finally {
      if (run === generation.current) setLoading(false);
    }
  }, [companyId, period]);
  const invalidate = useCallback(() => {
    generation.current++;
  }, []);
  useEffect(() => {
    void load();
    return invalidate;
  }, [load, invalidate]);
  const offers = result?.partners.flatMap((partner) => partner.offers) ?? [];
  const selectedOffer = offers.find((offer) => offer.offerId === offerId);
  return (
    <div className={styles.page}>
      <PortalHeader title={p('statistics')} description={p('statsDescription')} />
      <p className={styles.muted}>{p('privacyNote')}</p>
      <form
        className={styles.toolbar}
        onSubmit={(event) => {
          event.preventDefault();
          setOfferId('');
          setParams(input.trim() ? { partnerId: input.trim() } : {});
        }}
      >
        <label className={styles.field}>
          {p('filterCompany')}
          <input
            placeholder={p('allPartners')}
            value={input}
            onChange={(event) => setInput(event.target.value)}
            maxLength={300}
          />
        </label>
        <label className={styles.field}>
          {p('period')}
          <select
            value={period}
            onChange={(event) => {
              setPeriod(event.target.value);
              setOfferId('');
            }}
          >
            {statisticsPeriods().map((value) => (
              <option key={value} value={value}>
                {value === 'all' ? p('allTime') : value}
              </option>
            ))}
          </select>
        </label>
        <button className={styles.button} disabled={loading}>
          {p('showStats')}
        </button>
        {companyId && (
          <Link to="/partners/statistics" className={styles.secondary}>
            {p('allPartners')}
          </Link>
        )}
      </form>
      <ErrorNotice error={error} retry={() => void load()} />
      {loading ? (
        <p role="status">{p('loading')}</p>
      ) : (
        result && (
          <>
            <div className={styles.stats}>
              <div className={styles.metric}>
                <span>{selectedOffer ? selectedOffer.title : p('redemptions')}</span>
                <strong>{selectedOffer?.totalRedeemed ?? result.totalRedeemed}</strong>
              </div>
            </div>
            <p className={styles.muted}>
              {p('updatedAt')}: {formatDateOnly(result.updatedAt)}
            </p>
            {offers.length > 0 && (
              <label className={styles.field} style={{ maxWidth: 420 }}>
                {p('filterOffer')}
                <select value={offerId} onChange={(event) => setOfferId(event.target.value)}>
                  <option value="">{p('allOffers')}</option>
                  {offers.map((offer) => (
                    <option key={offer.offerId} value={offer.offerId}>
                      {offer.title}
                    </option>
                  ))}
                </select>
              </label>
            )}
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <thead>
                  <tr>
                    <th>{p('companies')}</th>
                    <th>{p('profileOffers')}</th>
                    <th>{p('redemptions')}</th>
                  </tr>
                </thead>
                <tbody>
                  {result.partners.flatMap((partner) =>
                    partner.offers.length
                      ? partner.offers
                          .filter((offer) => !offerId || offer.offerId === offerId)
                          .map((offer) => (
                            <tr key={offer.offerId}>
                              <td>
                                <Link to={`/partners/${encodeURIComponent(partner.companyId)}`}>
                                  {partner.companyName}
                                </Link>
                              </td>
                              <td>
                                <Link
                                  to={`/partners/offers?partnerId=${encodeURIComponent(partner.companyId)}&offerId=${encodeURIComponent(offer.offerId)}`}
                                >
                                  {offer.title}
                                </Link>
                              </td>
                              <td>{offer.totalRedeemed}</td>
                            </tr>
                          ))
                      : !offerId
                        ? [
                            <tr key={partner.companyId}>
                              <td>
                                <Link to={`/partners/${encodeURIComponent(partner.companyId)}`}>
                                  {partner.companyName}
                                </Link>
                              </td>
                              <td>—</td>
                              <td>{partner.totalRedeemed}</td>
                            </tr>,
                          ]
                        : [],
                  )}
                </tbody>
              </table>
            </div>
            {!result.partners.length && <p>{p('noData')}</p>}
          </>
        )
      )}
    </div>
  );
}

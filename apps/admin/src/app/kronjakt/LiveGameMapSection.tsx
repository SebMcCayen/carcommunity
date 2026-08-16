'use client';

/**
 * Kronjakt LIVE game-map section (admin Statistics tab).
 *
 * Owns the two real-time `onSnapshot` subscriptions (live crowns + armed traps)
 * and feeds them to the LiveGameMap renderer, with a small legend + live counts
 * and a graceful notice when the Mapbox token is unset or a listener errors.
 *
 * Self-contained and independent of the aggregate stats reads above it: it
 * subscribes on mount and unsubscribes on unmount. The live-users layer is
 * deferred (no admin RTDB read path yet) — crowns + traps only.
 */

import { useEffect, useRef, useState } from 'react';

import {
  subscribeLiveCrownSpawns,
  subscribeLiveTraps,
  type LiveCrownSpawn,
  type LiveTrap,
} from '@/features/crown-hunt';
import { LiveGameMap } from '@/components/map/LiveGameMap';
import { translate } from '@/i18n';

import styles from './StatsTab.module.css';
import page from './page.module.css';

const t = (key: string) => translate('sv', key);
const fmt = (key: string, params: Record<string, string | number>): string =>
  Object.entries(params).reduce((acc, [k, v]) => acc.replace(`{${k}}`, String(v)), t(key));

export function LiveGameMapSection(): React.ReactElement {
  const mountedRef = useRef(true);
  const [crowns, setCrowns] = useState<LiveCrownSpawn[]>([]);
  const [traps, setTraps] = useState<LiveTrap[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    mountedRef.current = true;
    const onError = () => {
      if (mountedRef.current) setError(t('crownHunt.statLiveMapError'));
    };
    const unsubCrowns = subscribeLiveCrownSpawns((next) => {
      if (mountedRef.current) setCrowns(next);
    }, onError);
    const unsubTraps = subscribeLiveTraps((next) => {
      if (mountedRef.current) setTraps(next);
    }, onError);
    return () => {
      mountedRef.current = false;
      unsubCrowns();
      unsubTraps();
    };
  }, []);

  return (
    <>
      <h3 className={styles.sectionTitle}>{t('crownHunt.statLiveMapTitle')}</h3>
      <p className={page.introText}>{t('crownHunt.statLiveMapNote')}</p>

      <div className={styles.legendRow} aria-hidden="true">
        <span className={styles.legendItem}>
          <span className={`${styles.legendDot} ${styles.legendCrown}`} />
          {fmt('crownHunt.statLiveMapCrowns', { count: crowns.length })}
        </span>
        <span className={styles.legendItem}>
          <span className={`${styles.legendDot} ${styles.legendTrap}`} />
          {fmt('crownHunt.statLiveMapTraps', { count: traps.length })}
        </span>
      </div>

      {error !== null && <p className={page.errorText}>{error}</p>}

      <div className={styles.mapWrap}>
        <LiveGameMap
          crowns={crowns}
          traps={traps}
          labels={{
            attribution: t('crownHunt.osmAttribution'),
            unavailable: t('map.unavailable'),
            loadError: t('map.loadError'),
          }}
        />
      </div>
    </>
  );
}

export default LiveGameMapSection;

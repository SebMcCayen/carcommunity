'use client';

/**
 * Kronjakt STATISTICS dashboard tab ("Statistik" / "Stats").
 *
 * A read-only admin dashboard built ENTIRELY from the #710 aggregates (no
 * fabricated metrics): the season + all-time leaderboards, past-season
 * champions, crowns spawned vs collected (overall and by rarity, for all-time
 * and the current season), active-player counts when the aggregate carries them,
 * and a spawn/collect heat map from the per-cell stats. Self-contained: it loads
 * its own reads on mount so page.tsx only mounts it.
 *
 * COVERAGE CAVEAT surfaced to the operator: the rarity breakdown + heat map
 * cover AUTO-SPAWNED crowns only (the only crowns carrying a rarity + grid
 * cell); the hand-placed remainder is shown separately so totals reconcile.
 */

import { useCallback, useEffect, useRef, useState } from 'react';

import {
  adminGetSpawnStats,
  adminGetPerkStats,
  toAdminPerkStatsView,
  adminListLeaderboard,
  adminListSeasons,
  adminListCellStats,
  currentSeasonId,
  ALL_TIME_SCOPE,
  RARITY_TIERS,
  PERK_IDS,
  type AdminSpawnStatsView,
  type AdminPerkStatsView,
  type CrownHuntCellStat,
  type CrownHuntLeaderboardEntry,
  type CrownHuntSeason,
  type PerkId,
} from '@/features/crown-hunt';
import { CrownHeatMap } from '@/components/map/CrownHeatMap';
import { PerkLogo } from './PerkLogos';
import { LiveGameMapSection } from './LiveGameMapSection';
import { translate } from '@/i18n';

import styles from './StatsTab.module.css';
import page from './page.module.css';

const t = (key: string) => translate('sv', key);
const fmt = (key: string, params: Record<string, string | number>): string =>
  Object.entries(params).reduce((acc, [k, v]) => acc.replace(`{${k}}`, String(v)), t(key));

const pct = (rate: number): string => `${Math.round(rate * 100)}%`;
const na = (value: number | null): string => (value === null ? '—' : String(value));

/**
 * True for a Firestore `permission-denied` — the EXPECTED transitional case for
 * perk stats: the crownHuntPerkStats rules deploy separately (deferred), so
 * these reads are denied in prod until the rules ship. We discriminate on the
 * FirebaseError `code`, never a message match. Any OTHER error (network, an
 * `unavailable`, a genuine post-deploy regression) is a REAL failure and must
 * surface — not be masked as legitimate "0" data.
 */
function isPermissionDenied(err: unknown): boolean {
  return (
    typeof err === 'object' &&
    err !== null &&
    (err as { code?: unknown }).code === 'permission-denied'
  );
}

/**
 * Read one perk-stats scope, discriminating the outcome:
 *  - success            → the view, not failed
 *  - permission-denied  → a ZEROED view, not failed (expected pre-deploy)
 *  - any other error    → a zeroed view flagged `failed` so the UI can show a
 *                         perk-section error state instead of fake zeros.
 */
async function loadPerkScope(
  scope: string,
): Promise<{ view: AdminPerkStatsView; failed: boolean }> {
  try {
    return { view: await adminGetPerkStats(scope), failed: false };
  } catch (err: unknown) {
    if (isPermissionDenied(err)) {
      // Rules not deployed yet: absent aggregate → honest zeros (empty =
      // expected pre-launch, matching the section copy).
      return { view: toAdminPerkStatsView(scope, undefined), failed: false };
    }
    console.warn(`[kronjakt] perk stats (${scope}) failed to load`, err);
    return { view: toAdminPerkStatsView(scope, undefined), failed: true };
  }
}

interface StatsData {
  allTime: AdminSpawnStatsView;
  season: AdminSpawnStatsView;
  seasonLeaderboard: CrownHuntLeaderboardEntry[];
  allTimeLeaderboard: CrownHuntLeaderboardEntry[];
  seasons: CrownHuntSeason[];
  cellStats: CrownHuntCellStat[];
  allTimePerks: AdminPerkStatsView;
  seasonPerks: AdminPerkStatsView;
  /** True when a perk-stats read failed for a REAL reason (not permission-denied). */
  perksError: boolean;
}

type BoardScope = 'season' | 'alltime';

function StatCard({
  title,
  stats,
}: {
  title: string;
  stats: AdminSpawnStatsView;
}): React.ReactElement {
  return (
    <div className={styles.card}>
      <p className={styles.cardTitle}>{title}</p>
      <div className={styles.statRow}>
        <span className={styles.statLabel}>{t('crownHunt.statSpawned')}</span>
        <span className={styles.statValue}>{stats.spawnedTotal}</span>
      </div>
      <div className={styles.statRow}>
        <span className={styles.statLabel}>{t('crownHunt.statCollected')}</span>
        <span className={styles.statValue}>{stats.collectedTotal}</span>
      </div>
      <div className={styles.statRow}>
        <span className={styles.statLabel}>{t('crownHunt.statCollectionRate')}</span>
        <span className={styles.statValueSmall}>{pct(stats.collectionRate)}</span>
      </div>
      <div className={styles.statRow}>
        <span className={styles.statLabel}>{t('crownHunt.statActive7d')}</span>
        <span className={styles.statValueSmall}>{na(stats.activePlayers7d)}</span>
      </div>
      <div className={styles.statRow}>
        <span className={styles.statLabel}>{t('crownHunt.statActive30d')}</span>
        <span className={styles.statValueSmall}>{na(stats.activePlayers30d)}</span>
      </div>
    </div>
  );
}

function RarityTable({ stats }: { stats: AdminSpawnStatsView }): React.ReactElement {
  return (
    <table className={page.table}>
      <thead>
        <tr>
          <th>{t('crownHunt.statRarity')}</th>
          <th>{t('crownHunt.statSpawned')}</th>
          <th>{t('crownHunt.statCollected')}</th>
        </tr>
      </thead>
      <tbody>
        {RARITY_TIERS.map((rarity) => (
          <tr key={rarity}>
            <td>{t(`crownHunt.rarity_${rarity}`)}</td>
            <td>{stats.spawnedByRarity[rarity] ?? 0}</td>
            <td>{stats.collectedByRarity[rarity] ?? 0}</td>
          </tr>
        ))}
        <tr>
          <td>{t('crownHunt.statHandPlaced')}</td>
          <td>—</td>
          <td>{stats.handPlacedCollected}</td>
        </tr>
      </tbody>
    </table>
  );
}

function Leaderboard({ entries }: { entries: CrownHuntLeaderboardEntry[] }): React.ReactElement {
  if (entries.length === 0) {
    return <p className={page.emptyText}>{t('crownHunt.statNoLeaderboard')}</p>;
  }
  return (
    <table className={page.table}>
      <thead>
        <tr>
          <th>{t('crownHunt.statRank')}</th>
          <th>{t('crownHunt.statPlayer')}</th>
          <th>{t('crownHunt.statPoints')}</th>
          <th>{t('crownHunt.statCrowns')}</th>
        </tr>
      </thead>
      <tbody>
        {entries.map((e) => (
          <tr key={e.uid}>
            <td>{e.rank}</td>
            <td>
              {e.displayName}
              {e.seasonsWon > 0 && (
                <span className={styles.championBadge}>
                  {fmt('crownHunt.statChampion', { count: e.seasonsWon })}
                </span>
              )}
            </td>
            <td>{e.points}</td>
            <td>{e.crownsCollected}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** The perk id → its trap-triggers relevance (only the trap perk drains KP). */
const PERK_IS_TRAP: Record<PerkId, boolean> = {
  spike_strip: true,
  shield: false,
  boost: false,
};

/**
 * Per-perk usage cards — a generated gold logo, the Swedish perk name, and the
 * used-this-season / used-all-time / purchased counts, plus trap-trigger count
 * for the trap perk. All counts read zero (documents absent) until the
 * crownHuntPerks flag is enabled and the first perk event exists.
 */
function PerkStatsSection({
  allTime,
  season,
  hasError,
}: {
  allTime: AdminPerkStatsView;
  season: AdminPerkStatsView;
  hasError: boolean;
}): React.ReactElement {
  // A REAL read failure (not the expected permission-denied) shows an error
  // state, NOT zeroed cards — so a genuine failure is visibly distinct from the
  // graceful pre-deploy zeros.
  if (hasError) {
    return <p className={page.errorText}>{t('crownHunt.statPerkError')}</p>;
  }
  return (
    <div className={styles.grid}>
      {PERK_IDS.map((perkId) => {
        const name = t(`crownHunt.perkName_${perkId}`);
        return (
          <div key={perkId} className={styles.card}>
            <div className={styles.perkHead}>
              <PerkLogo perkId={perkId} size={40} title={name} />
              <span className={styles.perkName}>{name}</span>
            </div>
            <div className={styles.statRow}>
              <span className={styles.statLabel}>{t('crownHunt.statPerkUsedSeason')}</span>
              <span className={styles.statValue}>{season.usedByPerk[perkId]}</span>
            </div>
            <div className={styles.statRow}>
              <span className={styles.statLabel}>{t('crownHunt.statPerkUsedAllTime')}</span>
              <span className={styles.statValueSmall}>{allTime.usedByPerk[perkId]}</span>
            </div>
            <div className={styles.statRow}>
              <span className={styles.statLabel}>{t('crownHunt.statPerkPurchased')}</span>
              <span className={styles.statValueSmall}>{allTime.purchasedByPerk[perkId]}</span>
            </div>
            {PERK_IS_TRAP[perkId] && (
              <>
                {/* trapTriggers is a per-scope scalar; show BOTH scopes so it
                    reads consistently with the season/all-time rows above. */}
                <div className={styles.statRow}>
                  <span className={styles.statLabel}>
                    {t('crownHunt.statPerkTrapTriggersSeason')}
                  </span>
                  <span className={styles.statValueSmall}>{season.trapTriggers}</span>
                </div>
                <div className={styles.statRow}>
                  <span className={styles.statLabel}>
                    {t('crownHunt.statPerkTrapTriggersAllTime')}
                  </span>
                  <span className={styles.statValueSmall}>{allTime.trapTriggers}</span>
                </div>
              </>
            )}
          </div>
        );
      })}
    </div>
  );
}

export function StatsTab(): React.ReactElement {
  const mountedRef = useRef(true);
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const [data, setData] = useState<StatsData | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [boardScope, setBoardScope] = useState<BoardScope>('season');

  const load = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    const seasonId = currentSeasonId();
    try {
      const [
        allTime,
        season,
        seasonLeaderboard,
        allTimeLeaderboard,
        seasons,
        cellStats,
        allTimePerks,
        seasonPerks,
      ] = await Promise.all([
        adminGetSpawnStats(ALL_TIME_SCOPE),
        adminGetSpawnStats(seasonId),
        adminListLeaderboard('season', seasonId, 20),
        adminListLeaderboard('alltime', null, 20),
        adminListSeasons(12),
        adminListCellStats(500),
        // Perk stats are BEST-EFFORT and must never take down the rest of the
        // dashboard. loadPerkScope discriminates the outcome: permission-denied
        // (rules not deployed yet) → honest zeros; any other error → zeros +
        // `failed`, so the perk SECTION shows an error state rather than fake
        // zeros that would read as legitimate pre-launch data.
        loadPerkScope(ALL_TIME_SCOPE),
        loadPerkScope(seasonId),
      ]);
      if (!mountedRef.current) return;
      setData({
        allTime,
        season,
        seasonLeaderboard,
        allTimeLeaderboard,
        seasons,
        cellStats,
        allTimePerks: allTimePerks.view,
        seasonPerks: seasonPerks.view,
        perksError: allTimePerks.failed || seasonPerks.failed,
      });
    } catch {
      if (!mountedRef.current) return;
      setError(t('crownHunt.error'));
    } finally {
      if (mountedRef.current) setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  if (isLoading && !data) {
    return <p className={page.loadingText}>{t('crownHunt.loading')}</p>;
  }
  if (error !== null && !data) {
    return (
      <p className={page.errorText}>
        {error}{' '}
        <button className={page.linkButton} onClick={() => void load()}>
          {t('crownHunt.retry')}
        </button>
      </p>
    );
  }
  if (!data) {
    return <p className={page.emptyText}>{t('crownHunt.statNoData')}</p>;
  }

  const pastSeasons = data.seasons.filter((s) => s.status === 'ended' && (s.winners?.length ?? 0) > 0);
  const board = boardScope === 'season' ? data.seasonLeaderboard : data.allTimeLeaderboard;
  const rarityScope = boardScope === 'season' ? data.season : data.allTime;

  return (
    <section>
      <p className={page.introText}>{t('crownHunt.statsIntro')}</p>

      {/* Spawned vs collected — all-time + current season */}
      <div className={styles.grid}>
        <StatCard title={t('crownHunt.statAllTime')} stats={data.allTime} />
        <StatCard
          title={fmt('crownHunt.statSeason', { season: data.season.scope })}
          stats={data.season}
        />
      </div>

      {/* Leaderboard with scope toggle */}
      <h3 className={styles.sectionTitle}>{t('crownHunt.statLeaderboardTitle')}</h3>
      <div className={styles.toggleRow} role="radiogroup" aria-label={t('crownHunt.statLeaderboardTitle')}>
        <button
          type="button"
          role="radio"
          aria-checked={boardScope === 'season'}
          className={boardScope === 'season' ? page.btnSmallPrimary : page.btnSmall}
          onClick={() => setBoardScope('season')}
        >
          {t('crownHunt.statBoardSeason')}
        </button>
        <button
          type="button"
          role="radio"
          aria-checked={boardScope === 'alltime'}
          className={boardScope === 'alltime' ? page.btnSmallPrimary : page.btnSmall}
          onClick={() => setBoardScope('alltime')}
        >
          {t('crownHunt.statBoardAllTime')}
        </button>
      </div>
      <Leaderboard entries={board} />

      {/* Rarity breakdown for the selected scope */}
      <h3 className={styles.sectionTitle}>{t('crownHunt.statRarityTitle')}</h3>
      <p className={page.introText}>{t('crownHunt.statRarityNote')}</p>
      <RarityTable stats={rarityScope} />

      {/* Perk usage — buys/uses per perk (season + all-time) */}
      <h3 className={styles.sectionTitle}>{t('crownHunt.statPerkTitle')}</h3>
      <p className={page.introText}>{t('crownHunt.statPerkNote')}</p>
      <PerkStatsSection
        allTime={data.allTimePerks}
        season={data.seasonPerks}
        hasError={data.perksError}
      />

      {/* Past-season champions */}
      <h3 className={styles.sectionTitle}>{t('crownHunt.statChampionsTitle')}</h3>
      {pastSeasons.length === 0 ? (
        <p className={page.emptyText}>{t('crownHunt.statNoChampions')}</p>
      ) : (
        pastSeasons.map((season) => (
          <div key={season.seasonId} className={styles.podium}>
            <div className={styles.podiumHead}>
              <span className={styles.podiumSeason}>{season.seasonId}</span>
              <span className={styles.podiumMeta}>
                {fmt('crownHunt.statParticipants', { count: season.participantCount ?? 0 })}
              </span>
            </div>
            <ol className={styles.podiumList}>
              {(season.winners ?? []).map((w) => (
                <li key={w.uid}>
                  {w.displayName || w.uid.slice(0, 8)} — {fmt('crownHunt.statWinnerLine', {
                    points: w.points,
                    crowns: w.crownsCollected,
                  })}
                </li>
              ))}
            </ol>
          </div>
        ))
      )}

      {/* Spawn-location heat map */}
      <h3 className={styles.sectionTitle}>{t('crownHunt.statHeatTitle')}</h3>
      <p className={page.introText}>{t('crownHunt.statHeatNote')}</p>
      {data.cellStats.length === 0 ? (
        <p className={page.emptyText}>{t('crownHunt.statNoHeat')}</p>
      ) : (
        <div className={styles.mapWrap}>
          <CrownHeatMap
            cellStats={data.cellStats}
            labels={{
              attribution: t('crownHunt.osmAttribution'),
              unavailable: t('map.unavailable'),
              loadError: t('map.loadError'),
            }}
          />
        </div>
      )}

      {/* Live game map — current crowns + armed traps (real-time onSnapshot) */}
      <LiveGameMapSection />
    </section>
  );
}

export default StatsTab;

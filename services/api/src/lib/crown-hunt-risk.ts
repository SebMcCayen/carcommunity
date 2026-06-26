/**
 * Crown Hunt basic anti-fraud risk-scoring helper.
 *
 * Evaluates privacy-conscious risk signals for a single claim attempt.
 * Returns a normalised score (0–100) and a list of safe category labels.
 *
 * Design rules:
 *  - Thresholds are configurable constants, not secrets or env vars.
 *  - Thresholds are NEVER exposed to mobile clients.
 *  - No automatic user suspension happens here.
 *  - No device fingerprinting libraries in this step.
 *  - No permanent raw location history is created here.
 *  - High-risk claims return `risk_review` and award no points.
 *  - TODO: Google Play Integrity integration.
 *  - TODO: Apple App Attest / DeviceCheck integration.
 *  - TODO: Admin review workflow for risk_review claims.
 *  - TODO: Richer anomaly detection (ML-based).
 */

// ---------------------------------------------------------------------------
// Risk thresholds (configurable — never expose to clients)
// ---------------------------------------------------------------------------

/** Score at or above this triggers risk_review and blocks the award. */
export const RISK_REVIEW_THRESHOLD = 60;

/**
 * Conservative GPS accuracy threshold.
 * Positions with accuracy worse than this (larger value) are flagged.
 */
const POOR_ACCURACY_THRESHOLD_METERS = 50;

/**
 * Maximum successful claims allowed within a short window for velocity check.
 * A higher-than-normal rate in a short window is a weak risk signal.
 */
const HIGH_VELOCITY_CLAIM_COUNT_THRESHOLD = 5;

/**
 * The time window (seconds) for the velocity claim count check.
 */
const HIGH_VELOCITY_WINDOW_SECONDS = 300; // 5 minutes

/**
 * Maximum allowed claim attempts per minute (rate limit signal for risk, not HTTP rate limit).
 */
const EXCESSIVE_ATTEMPTS_PER_MINUTE_THRESHOLD = 4;

// ---------------------------------------------------------------------------
// Input signals
// ---------------------------------------------------------------------------

export interface RiskSignals {
  /** Whether the reported position is stale (age > max allowed). */
  positionStale: boolean;
  /** Whether GPS accuracy is poor. */
  poorAccuracy: boolean;
  /** Whether the position represents an impossible geographic jump. */
  impossibleJump: boolean;
  /** Whether a duplicate idempotency key was used (replay attempt). */
  duplicateIdempotencyKey: boolean;
  /** How many claim attempts the user has made in the last minute. */
  attemptsInLastMinute: number;
  /** How many successful claims the user has made in the velocity window. */
  successfulClaimsInVelocityWindow: number;
  /** How many geofence-edge attempts (just within range) in the last hour. */
  geofenceEdgeAttempts: number;
  /** Reported GPS accuracy in meters. null if not reported. */
  accuracyMeters: number | null;
  /**
   * Platform integrity placeholder.
   * TODO: Populate once Apple App Attest / Google Play Integrity are integrated.
   */
  platformIntegrityPassed: boolean | null;
}

export interface RiskEvaluation {
  /** Normalised risk score 0–100. */
  riskScore: number;
  /**
   * Safe, non-leaking category labels for the triggered signals.
   * These may be stored and shown to admins but must never be returned to mobile clients.
   */
  riskReasons: string[];
  /** Whether this claim should be flagged for risk_review (no points awarded). */
  isHighRisk: boolean;
}

// ---------------------------------------------------------------------------
// Evaluator
// ---------------------------------------------------------------------------

/**
 * Evaluates anti-fraud risk signals for a single claim attempt.
 *
 * Returns a normalised score, reason categories, and a high-risk flag.
 * Does not mutate any state or write to the database.
 */
export function evaluateClaimRisk(signals: RiskSignals): RiskEvaluation {
  const reasons: string[] = [];
  let score = 0;

  // Stale position is a strong signal of session replay or GPS spoofing.
  if (signals.positionStale) {
    reasons.push('stale_position');
    score += 35;
  }

  // Impossible geographic jump strongly indicates GPS spoofing or mock location.
  if (signals.impossibleJump) {
    reasons.push('impossible_jump');
    score += 40;
  }

  // Duplicate idempotency key indicates a replay attempt.
  if (signals.duplicateIdempotencyKey) {
    reasons.push('duplicate_idempotency_key');
    score += 50;
  }

  // Poor GPS accuracy is a weak signal on its own.
  if (signals.poorAccuracy || (signals.accuracyMeters !== null && signals.accuracyMeters !== undefined && signals.accuracyMeters > POOR_ACCURACY_THRESHOLD_METERS)) {
    reasons.push('poor_gps_accuracy');
    score += 10;
  }

  // Excessive claim attempts in the last minute suggests automated tooling.
  if (signals.attemptsInLastMinute >= EXCESSIVE_ATTEMPTS_PER_MINUTE_THRESHOLD) {
    reasons.push('excessive_claim_attempts');
    score += 25;
  }

  // High velocity of successful claims is a weak signal.
  if (signals.successfulClaimsInVelocityWindow >= HIGH_VELOCITY_CLAIM_COUNT_THRESHOLD) {
    reasons.push('high_claim_velocity');
    score += 15;
  }

  // Repeated geofence-edge attempts suggest boundary probing.
  if (signals.geofenceEdgeAttempts >= 3) {
    reasons.push('repeated_geofence_edge');
    score += 20;
  }

  // Platform integrity failure is a strong signal (when available).
  if (signals.platformIntegrityPassed === false) {
    reasons.push('platform_integrity_failed');
    score += 40;
  }

  // Clamp to 0–100.
  const riskScore = Math.min(100, Math.max(0, score));

  return {
    riskScore,
    riskReasons: reasons,
    isHighRisk: riskScore >= RISK_REVIEW_THRESHOLD,
  };
}

// ---------------------------------------------------------------------------
// Exported threshold helpers (for service use — never send to clients)
// ---------------------------------------------------------------------------

export { POOR_ACCURACY_THRESHOLD_METERS, HIGH_VELOCITY_WINDOW_SECONDS, EXCESSIVE_ATTEMPTS_PER_MINUTE_THRESHOLD };

/**
 * PartnerDetailScreen — public partner company detail view.
 *
 * Shows basic public information about an active KCC Företagspartner.
 * Available to both free users and members.
 *
 * Privacy rules:
 *  - Only public-safe fields are shown (no contact email, admin notes, etc.)
 *  - All data comes from the backend public API.
 *  - Phone numbers and URLs are validated before opening.
 *  - External links use the platform's safe URL opening mechanism.
 *  - discountCode is NEVER shown automatically — only on explicit user action via show-code.
 *  - Protected offer details are cleared from state on logout.
 *
 * Accessibility:
 *  - All interactive elements have accessibilityRole and accessibilityLabel.
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  Linking,
  ScrollView,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';

import type { PartnerCompanyPublicDetail } from '@carcommunity/shared/partners';
import { canViewPartnerOfferDetails } from '@carcommunity/shared/users';

import { getPartnerDetail, PartnerApiError } from '../api/partners';
import {
  getPartnerOfferTeasers,
  getMemberOffers,
  getSavedOffers,
  showOfferCode,
  saveOffer,
  unsaveOffer,
  PartnerOfferApiError,
  type PublicPartnerOfferTeaser,
  type MemberPartnerOfferDetail,
} from '../api/partner-offers';
import { useAuth } from '../hooks/useAuth';
import { useAppTheme } from '../hooks/useAppTheme';
import { useI18n } from '../hooks/useI18n';
import type { RootStackParamList } from '../navigation/types';

type Props = NativeStackScreenProps<RootStackParamList, 'PartnerDetail'>;

/** Safe URL validation: only http and https are permitted. */
function isSafeUrl(url: string): boolean {
  try {
    const parsed = new URL(url);
    return parsed.protocol === 'http:' || parsed.protocol === 'https:';
  } catch {
    return false;
  }
}

/** Safe phone number validation: allows digits, spaces, +, -, (, ). */
function isSafePhone(phone: string): boolean {
  return /^[0-9\s+\-()/]{4,30}$/.test(phone.trim());
}

export const PartnerDetailScreen = ({ route, navigation }: Props) => {
  const { partnerId } = route.params;
  const { theme } = useAppTheme();
  const { t } = useI18n();
  const { currentUser, withToken } = useAuth();

  const [partner, setPartner] = useState<PartnerCompanyPublicDetail | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [openError, setOpenError] = useState<string | null>(null);

  // Offer state
  const [offerTeasers, setOfferTeasers] = useState<PublicPartnerOfferTeaser[]>([]);
  const [memberOffers, setMemberOffers] = useState<MemberPartnerOfferDetail[]>([]);
  const [savedOfferIds, setSavedOfferIds] = useState<Set<string>>(new Set());
  const [visibleCodes, setVisibleCodes] = useState<Map<string, string | null>>(new Map());
  const [offerError, setOfferError] = useState<string | null>(null);
  const [loadingCodeId, setLoadingCodeId] = useState<string | null>(null);
  const [savingOfferId, setSavingOfferId] = useState<string | null>(null);

  // TODO: Wire isDriving to real safe-driving mode context when implemented.
  // When true, offer interactions (show-code, save/unsave) are blocked and a
  // safety warning is displayed. Defaults to false until driving detection exists.
  const [isDriving] = useState(false);

  const isMounted = useRef(true);
  useEffect(() => {
    isMounted.current = true;
    return () => {
      isMounted.current = false;
    };
  }, []);

  const isMember =
    currentUser !== null &&
    canViewPartnerOfferDetails({
      role: currentUser.roles[0] ?? 'user',
      status: currentUser.status,
      subscriptionEntitlement: currentUser.subscriptionEntitlement,
    });

  const load = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const detail = await getPartnerDetail(partnerId);
      if (isMounted.current) {
        if (detail === null) {
          navigation.goBack();
        } else {
          setPartner(detail);
        }
      }
    } catch (err) {
      if (isMounted.current) {
        setError(err instanceof PartnerApiError ? err.message : t('partners.error'));
      }
    } finally {
      if (isMounted.current) setIsLoading(false);
    }
  }, [partnerId, navigation, t]);

  // Load offer teasers for all authenticated users
  const loadOfferTeasers = useCallback(async () => {
    try {
      const result = await withToken((token) => getPartnerOfferTeasers(1, partnerId, token));
      if (isMounted.current && result !== null) {
        setOfferTeasers(result.data.offers);
      }
    } catch {
      // Non-fatal — partner detail still shown without offers
    }
  }, [partnerId, withToken]);

  // Load full member offer details — only for active members.
  // Uses the partner-scoped member offers endpoint to avoid per-offer N+1 requests.
  const loadMemberOffers = useCallback(async () => {
    if (!isMember) return;
    try {
      const result = await withToken((token) => getMemberOffers(token, 1, partnerId));
      if (isMounted.current && result !== null) {
        setMemberOffers(result.data.offers);
      }
    } catch {
      if (isMounted.current) setOfferError(t('partnerOffers.loadError'));
    }
  }, [isMember, partnerId, withToken, t]);

  // Hydrate saved offer IDs from backend — only for active members.
  const loadSavedOfferIds = useCallback(async () => {
    if (!isMember) return;
    try {
      const result = await withToken((token) => getSavedOffers(token));
      if (isMounted.current && result !== null) {
        setSavedOfferIds(new Set(result.data.offers.map((o) => o.offerId)));
      }
    } catch {
      // Non-fatal — optimistic save/unsave still works without initial hydration
    }
  }, [isMember, withToken]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- triggers async fetch; state updates happen in async callbacks
    void load();
  }, [load]);

  // Load teasers after partner loads
  useEffect(() => {
    if (currentUser !== null) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- triggers async fetch; state updates happen in async callbacks
      void loadOfferTeasers();
    }
  }, [currentUser, loadOfferTeasers]);

  // Load member details and saved offer IDs after teasers load
  useEffect(() => {
    if (isMember && offerTeasers.length > 0) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- triggers async fetch; state updates happen in async callbacks
      void loadMemberOffers();
      void loadSavedOfferIds();
    }
  }, [isMember, offerTeasers, loadMemberOffers, loadSavedOfferIds]);

  // Clear protected offer data on logout
  useEffect(() => {
    if (currentUser === null) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- synchronous reset on logout; no cascading risk
      setMemberOffers([]);
      setSavedOfferIds(new Set());
      setVisibleCodes(new Map());
    }
  }, [currentUser]);

  const handleShowCode = useCallback(
    async (offerId: string) => {
      if (isDriving) return;
      if (loadingCodeId !== null) return;
      setLoadingCodeId(offerId);
      try {
        const result = await withToken((token) => showOfferCode(offerId, token));
        if (isMounted.current && result !== null) {
          setVisibleCodes((prev) => new Map(prev).set(offerId, result.code));
          Alert.alert(
            t('partnerOffers.codeAlertTitle'),
            result.code ?? t('partnerOffers.noOffers'),
            [{ text: t('partnerOffers.codeAlertClose') }],
          );
        }
      } catch (err) {
        if (isMounted.current) {
          const msg = err instanceof PartnerOfferApiError ? err.message : t('partnerOffers.codeLoadError');
          Alert.alert(t('partnerOffers.codeAlertTitle'), msg, [{ text: t('partnerOffers.codeAlertClose') }]);
        }
      } finally {
        if (isMounted.current) setLoadingCodeId(null);
      }
    },
    [isDriving, loadingCodeId, withToken, t],
  );

  const handleSaveToggle = useCallback(
    async (offerId: string) => {
      if (isDriving) return;
      if (savingOfferId !== null) return;
      setSavingOfferId(offerId);
      try {
        if (savedOfferIds.has(offerId)) {
          await withToken((token) => unsaveOffer(offerId, token));
          if (isMounted.current) {
            setSavedOfferIds((prev) => {
              const next = new Set(prev);
              next.delete(offerId);
              return next;
            });
          }
        } else {
          await withToken((token) => saveOffer(offerId, token));
          if (isMounted.current) {
            setSavedOfferIds((prev) => new Set(prev).add(offerId));
          }
        }
      } catch {
        if (isMounted.current) setOfferError(t('partnerOffers.saveError'));
      } finally {
        if (isMounted.current) setSavingOfferId(null);
      }
    },
    [isDriving, savingOfferId, savedOfferIds, withToken, t],
  );

  const openPhone = useCallback(
    async (phone: string) => {
      setOpenError(null);
      if (!isSafePhone(phone)) {
        setOpenError(t('partners.invalidPhone'));
        return;
      }
      const url = `tel:${phone.replace(/\s/g, '')}`;
      try {
        const canOpen = await Linking.canOpenURL(url);
        if (canOpen) {
          await Linking.openURL(url);
        } else {
          setOpenError(t('partners.cannotOpenPhone'));
        }
      } catch {
        setOpenError(t('partners.cannotOpenPhone'));
      }
    },
    [t],
  );

  const openWebsite = useCallback(
    async (url: string) => {
      setOpenError(null);
      if (!isSafeUrl(url)) {
        setOpenError(t('partners.invalidWebsite'));
        return;
      }
      try {
        await Linking.openURL(url);
      } catch {
        setOpenError(t('partners.cannotOpenWebsite'));
      }
    },
    [t],
  );

  const openNavigation = useCallback(
    async (lat: number, lon: number, name: string) => {
      setOpenError(null);
      // Use geo: URI for cross-platform navigation. Encode name for safety.
      const encodedName = encodeURIComponent(name);
      const url = `geo:${lat},${lon}?q=${lat},${lon}(${encodedName})`;
      try {
        const canOpen = await Linking.canOpenURL(url);
        if (canOpen) {
          await Linking.openURL(url);
        } else {
          // Fall back to Google Maps web URL
          const mapsUrl = `https://www.google.com/maps/search/?api=1&query=${lat},${lon}`;
          await Linking.openURL(mapsUrl);
        }
      } catch {
        setOpenError(t('partners.cannotOpenNavigation'));
      }
    },
    [t],
  );

  if (isLoading) {
    return (
      <View style={[styles.container, { backgroundColor: theme.colors.pageBackground }]}>
        <ActivityIndicator size="large" color={theme.colors.brandPrimary} />
      </View>
    );
  }

  if (error !== null || partner === null) {
    return (
      <View style={[styles.container, { backgroundColor: theme.colors.pageBackground }]}>
        <Text style={[styles.errorText, { color: theme.colors.statusError }]}>
          {error ?? t('partners.error')}
        </Text>
        <TouchableOpacity
          style={[styles.button, { backgroundColor: theme.colors.brandPrimary }]}
          onPress={() => void load()}
          accessibilityRole="button"
        >
          <Text style={styles.buttonText}>{t('partners.retry')}</Text>
        </TouchableOpacity>
      </View>
    );
  }

  return (
    <ScrollView
      style={{ backgroundColor: theme.colors.pageBackground }}
      contentContainerStyle={styles.scrollContent}
    >
      {/* Status label */}
      <View style={[styles.badge, { backgroundColor: theme.colors.subtleBackground }]}>
        <Text style={[styles.badgeText, { color: theme.colors.brandPrimary }]}>
          {partner.statusLabel}
        </Text>
      </View>

      {/* Company name */}
      <Text style={[styles.companyName, { color: theme.colors.textPrimary }]}>
        {partner.companyName}
      </Text>

      {/* Category */}
      <Text style={[styles.category, { color: theme.colors.textSecondary }]}>
        {partner.category}
      </Text>

      {/* Public description — plain text, never HTML */}
      <Text style={[styles.description, { color: theme.colors.textPrimary }]}>
        {partner.publicDescription}
      </Text>

      {/* Address */}
      <Text style={[styles.address, { color: theme.colors.textSecondary }]}>
        {partner.address}
      </Text>

      {openError !== null && (
        <Text style={[styles.errorText, { color: theme.colors.statusError }]}>{openError}</Text>
      )}

      {/* Action buttons */}
      <View style={styles.actions}>
        <TouchableOpacity
          style={[styles.button, { backgroundColor: theme.colors.brandPrimary }]}
          onPress={() => void openNavigation(partner.latitude, partner.longitude, partner.companyName)}
          accessibilityRole="button"
          accessibilityLabel={t('partners.navigateButton')}
        >
          <Text style={styles.buttonText}>{t('partners.navigateButton')}</Text>
        </TouchableOpacity>

        {partner.publicPhone !== null && isSafePhone(partner.publicPhone) && (
          <TouchableOpacity
            style={[styles.buttonOutline, { borderColor: theme.colors.brandPrimary }]}
            onPress={() => void openPhone(partner.publicPhone!)}
            accessibilityRole="button"
            accessibilityLabel={t('partners.callButton')}
          >
            <Text style={[styles.buttonOutlineText, { color: theme.colors.brandPrimary }]}>
              {t('partners.callButton')}
            </Text>
          </TouchableOpacity>
        )}

        {partner.publicWebsiteUrl !== null && isSafeUrl(partner.publicWebsiteUrl) && (
          <TouchableOpacity
            style={[styles.buttonOutline, { borderColor: theme.colors.brandPrimary }]}
            onPress={() => void openWebsite(partner.publicWebsiteUrl!)}
            accessibilityRole="button"
            accessibilityLabel={t('partners.websiteButton')}
          >
            <Text style={[styles.buttonOutlineText, { color: theme.colors.brandPrimary }]}>
              {t('partners.websiteButton')}
            </Text>
          </TouchableOpacity>
        )}
      </View>

      {/* Offers & member benefits section */}
      {offerTeasers.length > 0 && (
        <View style={styles.offersSection}>
          <Text style={[styles.offersSectionTitle, { color: theme.colors.textPrimary }]}>
            {t('partnerOffers.sectionTitle')}
          </Text>

          {isDriving && (
            <Text style={[styles.drivingWarning, { color: theme.colors.statusError }]}>
              ⚠️ {t('partnerOffers.drivingSafetyWarning')}
            </Text>
          )}

          {offerError !== null && (
            <Text style={[styles.errorText, { color: theme.colors.statusError }]}>
              {offerError}
            </Text>
          )}

          {offerTeasers.map((teaser) => {
            const memberDetail = memberOffers.find((o) => o.offerId === teaser.offerId);
            const isSaved = savedOfferIds.has(teaser.offerId);
            const codeShown = visibleCodes.has(teaser.offerId);

            return (
              <View
                key={teaser.offerId}
                style={[styles.offerCard, { backgroundColor: theme.colors.subtleBackground }]}
              >
                <Text style={[styles.offerTitle, { color: theme.colors.textPrimary }]}>
                  {teaser.title}
                </Text>
                <Text style={[styles.offerTeaser, { color: theme.colors.textSecondary }]}>
                  {teaser.teaserText}
                </Text>

                {/* Availability dates */}
                {(teaser.availableUntil !== null) && (
                  <Text style={[styles.offerMeta, { color: theme.colors.textSecondary }]}>
                    {t('partnerOffers.validUntil')}: {new Date(teaser.availableUntil).toLocaleDateString('sv-SE')}
                  </Text>
                )}

                {/* Member-only extended details */}
                {!isMember && (
                  <View style={styles.memberLock}>
                    <Text style={[styles.memberLockText, { color: theme.colors.textSecondary }]}>
                      🔒 {t('partnerOffers.memberRequired')}
                    </Text>
                  </View>
                )}

                {isMember && memberDetail !== undefined && (
                  <View style={styles.memberDetails}>
                    <Text style={[styles.offerDescription, { color: theme.colors.textPrimary }]}>
                      {memberDetail.description}
                    </Text>

                    {memberDetail.redemptionInstructions !== null && (
                      <View>
                        <Text style={[styles.offerMetaLabel, { color: theme.colors.textSecondary }]}>
                          {t('partnerOffers.howToRedeem')}:
                        </Text>
                        <Text style={[styles.offerMeta, { color: theme.colors.textPrimary }]}>
                          {memberDetail.redemptionInstructions}
                        </Text>
                      </View>
                    )}

                    {memberDetail.terms !== null && (
                      <View>
                        <Text style={[styles.offerMetaLabel, { color: theme.colors.textSecondary }]}>
                          {t('partnerOffers.terms')}:
                        </Text>
                        <Text style={[styles.offerMeta, { color: theme.colors.textSecondary }]}>
                          {memberDetail.terms}
                        </Text>
                      </View>
                    )}

                    {/* Show code — requires explicit user action */}
                    {!isDriving && !codeShown && (
                      <TouchableOpacity
                        style={[styles.offerButton, { backgroundColor: theme.colors.brandPrimary }]}
                        onPress={() => void handleShowCode(teaser.offerId)}
                        disabled={loadingCodeId === teaser.offerId}
                        accessibilityRole="button"
                        accessibilityLabel={t('partnerOffers.showCode')}
                      >
                        <Text style={styles.offerButtonText}>
                          {loadingCodeId === teaser.offerId
                            ? '...'
                            : t('partnerOffers.showCode')}
                        </Text>
                      </TouchableOpacity>
                    )}

                    {/* Save / unsave */}
                    {!isDriving && (
                      <TouchableOpacity
                        style={[
                          styles.offerButtonOutline,
                          { borderColor: theme.colors.brandPrimary },
                        ]}
                        onPress={() => void handleSaveToggle(teaser.offerId)}
                        disabled={savingOfferId === teaser.offerId}
                        accessibilityRole="button"
                        accessibilityLabel={isSaved ? t('partnerOffers.unsaveOffer') : t('partnerOffers.saveOffer')}
                      >
                        <Text style={[styles.offerButtonOutlineText, { color: theme.colors.brandPrimary }]}>
                          {savingOfferId === teaser.offerId
                            ? '...'
                            : isSaved
                            ? t('partnerOffers.unsaveOffer')
                            : t('partnerOffers.saveOffer')}
                        </Text>
                      </TouchableOpacity>
                    )}
                  </View>
                )}
              </View>
            );
          })}
        </View>
      )}

      {/* Entry point for business application */}
      <TouchableOpacity
        style={styles.applicationEntry}
        onPress={() => navigation.navigate('PartnerApplication')}
        accessibilityRole="button"
        accessibilityLabel={t('partners.applicationEntryLabel')}
      >
        <Text style={[styles.applicationEntryText, { color: theme.colors.textSecondary }]}>
          {t('partners.applicationEntryHint')}
        </Text>
      </TouchableOpacity>
    </ScrollView>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 24,
  },
  scrollContent: {
    padding: 24,
  },
  badge: {
    alignSelf: 'flex-start',
    paddingHorizontal: 10,
    paddingVertical: 3,
    borderRadius: 12,
    marginBottom: 12,
  },
  badgeText: {
    fontSize: 12,
    fontWeight: '600',
  },
  companyName: {
    fontSize: 24,
    fontWeight: '700',
    marginBottom: 4,
  },
  category: {
    fontSize: 14,
    marginBottom: 16,
  },
  description: {
    fontSize: 15,
    lineHeight: 22,
    marginBottom: 12,
  },
  address: {
    fontSize: 14,
    marginBottom: 20,
  },
  actions: {
    gap: 12,
    marginBottom: 32,
  },
  button: {
    paddingVertical: 12,
    paddingHorizontal: 20,
    borderRadius: 8,
    alignItems: 'center',
  },
  buttonText: {
    color: '#FFFFFF',
    fontSize: 15,
    fontWeight: '600',
  },
  buttonOutline: {
    paddingVertical: 12,
    paddingHorizontal: 20,
    borderRadius: 8,
    borderWidth: 1.5,
    alignItems: 'center',
  },
  buttonOutlineText: {
    fontSize: 15,
    fontWeight: '600',
  },
  applicationEntry: {
    paddingVertical: 12,
    alignItems: 'center',
  },
  applicationEntryText: {
    fontSize: 13,
    textDecorationLine: 'underline',
  },
  errorText: {
    fontSize: 14,
    textAlign: 'center',
    marginBottom: 16,
  },
  offersSection: {
    marginBottom: 24,
    marginTop: 8,
  },
  offersSectionTitle: {
    fontSize: 18,
    fontWeight: '700',
    marginBottom: 12,
  },
  drivingWarning: {
    fontSize: 14,
    marginBottom: 12,
    textAlign: 'center',
  },
  offerCard: {
    borderRadius: 10,
    padding: 16,
    marginBottom: 12,
  },
  offerTitle: {
    fontSize: 16,
    fontWeight: '600',
    marginBottom: 4,
  },
  offerTeaser: {
    fontSize: 14,
    lineHeight: 20,
    marginBottom: 8,
  },
  offerMeta: {
    fontSize: 13,
    marginBottom: 4,
  },
  offerMetaLabel: {
    fontSize: 12,
    fontWeight: '600',
    marginTop: 8,
    marginBottom: 2,
  },
  memberLock: {
    marginTop: 8,
  },
  memberLockText: {
    fontSize: 13,
    fontStyle: 'italic',
  },
  memberDetails: {
    marginTop: 8,
    gap: 4,
  },
  offerDescription: {
    fontSize: 14,
    lineHeight: 20,
    marginBottom: 8,
  },
  offerButton: {
    paddingVertical: 10,
    paddingHorizontal: 16,
    borderRadius: 8,
    alignItems: 'center',
    marginTop: 10,
  },
  offerButtonText: {
    color: '#FFFFFF',
    fontSize: 14,
    fontWeight: '600',
  },
  offerButtonOutline: {
    paddingVertical: 10,
    paddingHorizontal: 16,
    borderRadius: 8,
    borderWidth: 1.5,
    alignItems: 'center',
    marginTop: 8,
  },
  offerButtonOutlineText: {
    fontSize: 14,
    fontWeight: '600',
  },
});

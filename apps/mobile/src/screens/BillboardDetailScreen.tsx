/**
 * BillboardDetailScreen — displays a sponsored digital billboard detail.
 *
 * Privacy rules:
 *  - Content is never rendered as raw HTML (no dangerouslySetInnerHTML or WebView injection).
 *  - All URLs validated before opening (https:// only for website CTA).
 *  - Phone numbers opened via tel: scheme only.
 *  - Analytics failures must not block the user's main action.
 *
 * Safe driving:
 *  - If the user is actively sharing location (driving proxy), CTA buttons are disabled.
 *  - A warning message is shown instead of blocking navigation.
 */

import { useEffect, useState } from 'react';
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

import type { PublicBillboardDetail } from '@carcommunity/shared/digital-billboards';

import { fetchBillboardDetail, fireAndForgetBillboardInteraction } from '../api/digital-billboards';
import { useLiveLocation } from '../context/LiveLocationContext';
import { useAppTheme } from '../hooks/useAppTheme';
import { useAuth } from '../hooks/useAuth';
import { useI18n } from '../hooks/useI18n';
import type { RootStackParamList } from '../navigation/types';

type Props = NativeStackScreenProps<RootStackParamList, 'BillboardDetail'>;

function isSafeHttpsUrl(url: string): boolean {
  // Reject any URL containing HTML characters to prevent injection.
  if (url.includes('<') || url.includes('>') || url.includes('"')) return false;
  try {
    const parsed = new URL(url);
    return parsed.protocol === 'https:';
  } catch {
    return false;
  }
}

function isSafePhone(phone: string): boolean {
  return /^\+?[\d\s\-()]{6,30}$/.test(phone.trim());
}

function stripHtmlTags(value: string): string {
  // Remove all HTML tags and strip remaining angle brackets for defense in depth.
  // React Native's Text component does not render HTML, but we sanitize as a precaution.
  return value.replace(/<[^>]*>/g, '').replace(/</g, '').replace(/>/g, '').trim();
}

export const BillboardDetailScreen = ({ route, navigation }: Props) => {
  const { billboardId } = route.params;
  const { theme } = useAppTheme();
  const { t } = useI18n();
  const { status: liveStatus } = useLiveLocation();
  const { withToken } = useAuth();

  const [detail, setDetail] = useState<PublicBillboardDetail | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const isDriving = liveStatus === 'sharing';

  useEffect(() => {
    let cancelled = false;

    void (async () => {
      setIsLoading(true);
      setError(null);
      try {
        await withToken(async (token) => {
          const response = await fetchBillboardDetail(billboardId, token);
          if (!cancelled) {
            setDetail(response.data);
            fireAndForgetBillboardInteraction(billboardId, 'open', token);
          }
        });
      } catch {
        if (!cancelled) {
          setError(t('billboard.loadError'));
        }
      } finally {
        if (!cancelled) {
          setIsLoading(false);
        }
      }
    })();

    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [billboardId]);

  const handleNavigate = () => {
    if (isDriving) {
      Alert.alert('', t('billboard.safeDrivingInteractionDisabled'));
      return;
    }
    if (!detail) return;

    const url = `https://maps.google.com/?q=${detail.latitude},${detail.longitude}`;
    Linking.openURL(url).catch(() => undefined);
    void withToken(async (token) => {
      fireAndForgetBillboardInteraction(billboardId, 'navigate', token);
    });
  };

  const handlePhone = () => {
    if (isDriving) {
      Alert.alert('', t('billboard.safeDrivingInteractionDisabled'));
      return;
    }
    if (!detail?.callToActionValue || !isSafePhone(detail.callToActionValue)) return;

    Linking.openURL(`tel:${detail.callToActionValue}`).catch(() => undefined);
    void withToken(async (token) => {
      fireAndForgetBillboardInteraction(billboardId, 'phone', token);
    });
  };

  const handleWebsite = () => {
    if (isDriving) {
      Alert.alert('', t('billboard.safeDrivingInteractionDisabled'));
      return;
    }
    if (!detail?.callToActionValue || !isSafeHttpsUrl(detail.callToActionValue)) return;

    Linking.openURL(detail.callToActionValue).catch(() => undefined);
    void withToken(async (token) => {
      fireAndForgetBillboardInteraction(billboardId, 'website', token);
    });
  };

  const handleOfferView = () => {
    if (isDriving) {
      Alert.alert('', t('billboard.safeDrivingInteractionDisabled'));
      return;
    }
    if (!detail) return;

    navigation.navigate('PartnerDetail', { partnerId: detail.partnerId });
    void withToken(async (token) => {
      fireAndForgetBillboardInteraction(billboardId, 'offer_view', token);
    });
  };

  const handlePartnerProfile = () => {
    if (!detail) return;
    navigation.navigate('PartnerDetail', { partnerId: detail.partnerId });
  };

  if (isLoading) {
    return (
      <View style={[styles.centered, { backgroundColor: theme.colors.pageBackground }]}>
        <ActivityIndicator color={theme.colors.brandPrimary} />
      </View>
    );
  }

  if (error || !detail) {
    return (
      <View style={[styles.centered, { backgroundColor: theme.colors.pageBackground }]}>
        <Text style={{ color: theme.colors.statusError }}>{error ?? t('billboard.loadError')}</Text>
      </View>
    );
  }

  const formatDate = (iso: string | null) => {
    if (!iso) return null;
    return new Date(iso).toLocaleDateString('sv-SE', {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
    });
  };

  return (
    <ScrollView
      style={[styles.container, { backgroundColor: theme.colors.pageBackground }]}
      contentContainerStyle={styles.content}
    >
      <View style={[styles.sponsorBadge, { backgroundColor: theme.colors.brandPrimary }]}>
        <Text style={styles.sponsorBadgeText} accessibilityRole="text">
          {detail.sponsorLabel || t('billboard.sponsoredLabel')}
        </Text>
      </View>

      <Text style={[styles.companyName, { color: theme.colors.textSecondary }]}>
        {t('billboard.advertisingFrom')}: {stripHtmlTags(detail.partnerCompanyName)}
      </Text>

      <Text style={[styles.headline, { color: theme.colors.textPrimary }]}>{stripHtmlTags(detail.headline)}</Text>
      <Text style={[styles.message, { color: theme.colors.textSecondary }]}>{stripHtmlTags(detail.message)}</Text>

      {detail.availableFrom && (
        <Text style={[styles.availability, { color: theme.colors.textSecondary }]}>
          {t('billboard.availableFrom')}: {formatDate(detail.availableFrom)}
        </Text>
      )}
      {detail.availableUntil && (
        <Text style={[styles.availability, { color: theme.colors.textSecondary }]}>
          {t('billboard.availableUntil')}: {formatDate(detail.availableUntil)}
        </Text>
      )}

      {isDriving && (
        <View style={[styles.drivingWarning, { backgroundColor: theme.colors.statusWarning }]}>
          <Text style={styles.drivingWarningText} accessibilityRole="text">
            {t('billboard.safeDrivingInteractionDisabled')}
          </Text>
        </View>
      )}

      <View style={styles.ctaSection}>
        {detail.callToActionType === 'navigate' && (
          <TouchableOpacity
            style={[styles.ctaButton, { backgroundColor: theme.colors.brandPrimary, opacity: isDriving ? 0.5 : 1 }]}
            onPress={handleNavigate}
            disabled={isDriving}
            accessibilityRole="button"
            accessibilityLabel={t('billboard.navigate')}
          >
            <Text style={styles.ctaButtonText}>{t('billboard.navigate')}</Text>
          </TouchableOpacity>
        )}
        {detail.callToActionType === 'phone' && (
          <TouchableOpacity
            style={[styles.ctaButton, { backgroundColor: theme.colors.brandPrimary, opacity: isDriving ? 0.5 : 1 }]}
            onPress={handlePhone}
            disabled={isDriving}
            accessibilityRole="button"
            accessibilityLabel={t('billboard.call')}
          >
            <Text style={styles.ctaButtonText}>{t('billboard.call')}</Text>
          </TouchableOpacity>
        )}
        {detail.callToActionType === 'website' && (
          <TouchableOpacity
            style={[styles.ctaButton, { backgroundColor: theme.colors.brandPrimary, opacity: isDriving ? 0.5 : 1 }]}
            onPress={handleWebsite}
            disabled={isDriving}
            accessibilityRole="button"
            accessibilityLabel={t('billboard.visitWebsite')}
          >
            <Text style={styles.ctaButtonText}>{t('billboard.visitWebsite')}</Text>
          </TouchableOpacity>
        )}
        {detail.callToActionType === 'offer_view' && (
          <TouchableOpacity
            style={[styles.ctaButton, { backgroundColor: theme.colors.brandPrimary, opacity: isDriving ? 0.5 : 1 }]}
            onPress={handleOfferView}
            disabled={isDriving}
            accessibilityRole="button"
            accessibilityLabel={t('billboard.viewOffer')}
          >
            <Text style={styles.ctaButtonText}>{t('billboard.viewOffer')}</Text>
          </TouchableOpacity>
        )}
        {detail.callToActionType === 'partner_profile' && (
          <TouchableOpacity
            style={[styles.ctaButton, { backgroundColor: theme.colors.brandPrimary }]}
            onPress={handlePartnerProfile}
            accessibilityRole="button"
            accessibilityLabel={t('billboard.viewCompany')}
          >
            <Text style={styles.ctaButtonText}>{t('billboard.viewCompany')}</Text>
          </TouchableOpacity>
        )}
      </View>
    </ScrollView>
  );
};

const styles = StyleSheet.create({
  container: { flex: 1 },
  content: { padding: 16, gap: 12 },
  centered: { flex: 1, justifyContent: 'center', alignItems: 'center' },
  sponsorBadge: { alignSelf: 'flex-start', paddingHorizontal: 10, paddingVertical: 4, borderRadius: 4 },
  sponsorBadgeText: { color: '#FFFFFF', fontSize: 11, fontWeight: '600', textTransform: 'uppercase' },
  companyName: { fontSize: 13, marginTop: 4 },
  headline: { fontSize: 22, fontWeight: '700', marginTop: 8 },
  message: { fontSize: 15, lineHeight: 22, marginTop: 4 },
  availability: { fontSize: 13, marginTop: 4 },
  drivingWarning: { padding: 12, borderRadius: 8, marginTop: 8 },
  drivingWarningText: { color: '#FFFFFF', fontSize: 13, fontWeight: '600' },
  ctaSection: { marginTop: 16, gap: 10 },
  ctaButton: { paddingVertical: 14, paddingHorizontal: 20, borderRadius: 8, alignItems: 'center' },
  ctaButtonText: { color: '#FFFFFF', fontSize: 15, fontWeight: '600' },
});

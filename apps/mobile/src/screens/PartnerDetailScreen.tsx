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
 *  - No offers, discount codes, or analytics in this step.
 *
 * Accessibility:
 *  - All interactive elements have accessibilityRole and accessibilityLabel.
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Linking,
  ScrollView,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';

import type { PartnerCompanyPublicDetail } from '@carcommunity/shared/partners';

import { getPartnerDetail, PartnerApiError } from '../api/partners';
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

  const [partner, setPartner] = useState<PartnerCompanyPublicDetail | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [openError, setOpenError] = useState<string | null>(null);

  const isMounted = useRef(true);
  useEffect(() => {
    isMounted.current = true;
    return () => {
      isMounted.current = false;
    };
  }, []);

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

  useEffect(() => {
    void load();
  }, [load]);

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
      <View style={[styles.badge, { backgroundColor: theme.colors.brandPrimarySubtle }]}>
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
});

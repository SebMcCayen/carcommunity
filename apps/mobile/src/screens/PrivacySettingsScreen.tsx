/**
 * PrivacySettingsScreen — lets the user manage privacy preferences post-onboarding.
 *
 * Currently exposes:
 *   - anonymousPartnerStatsOptIn toggle
 *
 * The toggle is opt-in only (defaults false). No statistics are collected in this step.
 * Backend is the source of truth; this screen fetches on mount and PATCHes on save.
 */

import { useCallback, useEffect, useState } from 'react';
import { ScrollView, StyleSheet, Switch, Text, View } from 'react-native';

import { getPrivacySettings, patchPrivacySettings } from '../api/profile';
import { KccButton } from '../components/KccButton';
import { useAppTheme } from '../hooks/useAppTheme';
import { useAuth } from '../hooks/useAuth';
import { useI18n } from '../hooks/useI18n';

export const PrivacySettingsScreen = () => {
  const { t } = useI18n();
  const { theme } = useAppTheme();
  const { withToken } = useAuth();

  const [partnerStatsOptIn, setPartnerStatsOptIn] = useState(false);
  const [isLoading, setIsLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  const fetchSettings = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const result = await withToken((token) => getPrivacySettings(token));
      if (result?.ok) {
        setPartnerStatsOptIn(result.data.anonymousPartnerStatsOptIn);
      }
    } catch {
      setError(t('privacySettings.error'));
    } finally {
      setIsLoading(false);
    }
  }, [t, withToken]);

  useEffect(() => {
    void fetchSettings();
  }, [fetchSettings]);

  const handleSave = async () => {
    setIsSaving(true);
    setError(null);
    setSaved(false);
    try {
      const result = await withToken((token) =>
        patchPrivacySettings(token, { anonymousPartnerStatsOptIn: partnerStatsOptIn }),
      );
      if (result?.ok) {
        setPartnerStatsOptIn(result.data.anonymousPartnerStatsOptIn);
        setSaved(true);
      } else {
        setError(t('privacySettings.error'));
      }
    } catch {
      setError(t('privacySettings.error'));
    } finally {
      setIsSaving(false);
    }
  };

  const styles = StyleSheet.create({
    container: {
      flex: 1,
      backgroundColor: theme.colors.pageBackground,
      padding: 20,
    },
    sectionTitle: {
      fontSize: 17,
      fontWeight: '600',
      color: theme.colors.textPrimary,
      marginBottom: 8,
    },
    body: {
      fontSize: 14,
      color: theme.colors.textSecondary,
      marginBottom: 12,
    },
    row: {
      flexDirection: 'row',
      alignItems: 'center',
      paddingVertical: 8,
    },
    switchLabel: {
      flex: 1,
      fontSize: 15,
      color: theme.colors.textPrimary,
      marginLeft: 12,
    },
    note: {
      fontSize: 13,
      color: theme.colors.textSecondary,
      marginTop: 6,
      marginBottom: 20,
    },
    error: {
      color: theme.colors.statusError,
      marginBottom: 12,
      fontSize: 14,
    },
    savedText: {
      color: theme.colors.statusSuccess,
      marginBottom: 12,
      fontSize: 14,
    },
  });

  if (isLoading) {
    return (
      <View style={[styles.container, { justifyContent: 'center', alignItems: 'center' }]}>
        <Text style={styles.body}>{t('privacySettings.loading')}</Text>
      </View>
    );
  }

  return (
    <ScrollView style={styles.container} contentContainerStyle={{ paddingBottom: 40 }}>
      <Text style={styles.sectionTitle}>{t('privacySettings.partnerStatsTitle')}</Text>
      <Text style={styles.body}>{t('privacySettings.partnerStatsBody')}</Text>

      <View style={styles.row} testID="partner-stats-row">
        <Switch
          testID="partner-stats-switch"
          value={partnerStatsOptIn}
          onValueChange={(val) => {
            setPartnerStatsOptIn(val);
            setSaved(false);
          }}
          accessibilityLabel={t('privacySettings.partnerStatsOptIn')}
        />
        <Text style={styles.switchLabel}>{t('privacySettings.partnerStatsOptIn')}</Text>
      </View>

      <Text style={styles.note}>{t('privacySettings.partnerStatsNote')}</Text>

      {error ? <Text style={styles.error}>{error}</Text> : null}
      {saved ? <Text style={styles.savedText}>{t('privacySettings.saved')}</Text> : null}

      <KccButton
        testID="privacy-save-button"
        label={isSaving ? t('privacySettings.loading') : t('privacySettings.saveButton')}
        onPress={() => void handleSave()}
        disabled={isSaving}
        variant="primary"
      />
    </ScrollView>
  );
};

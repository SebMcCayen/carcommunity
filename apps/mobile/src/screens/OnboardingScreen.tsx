/**
 * OnboardingScreen — shown after login when the user has not yet completed onboarding.
 *
 * Requires:
 *   - 18+ age confirmation
 *   - Terms acceptance
 *   - Privacy policy acceptance
 *
 * Optional:
 *   - Display name
 *   - Anonymous partner statistics opt-in (unchecked by default)
 *
 * On successful submission, refreshes the auth context so the navigator
 * redirects to the main app automatically.
 *
 * Security notes:
 *   - Partner statistics opt-in is false by default and must be explicitly set.
 *   - Backend is the source of truth — client only provides intent.
 */

import { useState } from 'react';
import { ScrollView, StyleSheet, Switch, Text, TextInput, View } from 'react-native';

import { patchPrivacySettings, patchUserProfile } from '../api/profile';
import { KccButton } from '../components/KccButton';
import { useAppTheme } from '../hooks/useAppTheme';
import { useAuth } from '../hooks/useAuth';
import { useI18n } from '../hooks/useI18n';

export const OnboardingScreen = () => {
  const { t } = useI18n();
  const { theme } = useAppTheme();
  const { withToken, refreshCurrentUser } = useAuth();

  const [ageConfirmed, setAgeConfirmed] = useState(false);
  const [termsAccepted, setTermsAccepted] = useState(false);
  const [privacyAccepted, setPrivacyAccepted] = useState(false);
  const [displayName, setDisplayName] = useState('');
  const [partnerStatsOptIn, setPartnerStatsOptIn] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const canContinue = ageConfirmed && termsAccepted && privacyAccepted && !isLoading;

  const handleContinue = async () => {
    if (!canContinue) return;

    setIsLoading(true);
    setError(null);

    try {
      const profileResult = await withToken((token) =>
        patchUserProfile(token, {
          displayName: displayName.trim() || null,
          ageConfirmed: true,
          termsAccepted: true,
          privacyPolicyAccepted: true,
        }),
      );

      if (!profileResult?.ok) {
        setError(t('onboarding.error'));
        return;
      }

      // Only update privacy settings if explicitly opted in.
      if (partnerStatsOptIn) {
        await withToken((token) =>
          patchPrivacySettings(token, { anonymousPartnerStatsOptIn: true }),
        );
      }

      // Refresh auth context so navigator detects onboardingCompletedAt.
      await refreshCurrentUser();
    } catch {
      setError(t('onboarding.error'));
    } finally {
      setIsLoading(false);
    }
  };

  const styles = StyleSheet.create({
    container: {
      flex: 1,
      backgroundColor: theme.colors.pageBackground,
      padding: 20,
    },
    title: {
      fontSize: 24,
      fontWeight: 'bold',
      color: theme.colors.textPrimary,
      marginBottom: 8,
    },
    subtitle: {
      fontSize: 15,
      color: theme.colors.textSecondary,
      marginBottom: 24,
    },
    section: {
      marginBottom: 20,
    },
    row: {
      flexDirection: 'row',
      alignItems: 'center',
      paddingVertical: 8,
    },
    checkboxLabel: {
      flex: 1,
      fontSize: 15,
      color: theme.colors.textPrimary,
      marginLeft: 12,
    },
    inputLabel: {
      fontSize: 14,
      color: theme.colors.textSecondary,
      marginBottom: 6,
    },
    input: {
      borderWidth: 1,
      borderColor: theme.colors.borderDefault,
      borderRadius: 8,
      padding: 12,
      fontSize: 15,
      color: theme.colors.textPrimary,
      backgroundColor: theme.colors.surfaceBackground,
    },
    partnerNote: {
      fontSize: 13,
      color: theme.colors.textSecondary,
      marginTop: 6,
    },
    error: {
      color: theme.colors.statusError,
      marginBottom: 12,
      fontSize: 14,
    },
    divider: {
      height: 1,
      backgroundColor: theme.colors.borderDefault,
      marginVertical: 16,
    },
  });

  return (
    <ScrollView style={styles.container} contentContainerStyle={{ paddingBottom: 40 }}>
      <Text style={styles.title}>{t('onboarding.title')}</Text>
      <Text style={styles.subtitle}>{t('onboarding.subtitle')}</Text>

      {/* Required confirmations */}
      <View style={styles.section}>
        <View style={styles.row} testID="age-row">
          <Switch
            testID="age-switch"
            value={ageConfirmed}
            onValueChange={setAgeConfirmed}
            accessibilityLabel={t('onboarding.ageConfirm')}
          />
          <Text style={styles.checkboxLabel}>{t('onboarding.ageConfirm')}</Text>
        </View>

        <View style={styles.row} testID="terms-row">
          <Switch
            testID="terms-switch"
            value={termsAccepted}
            onValueChange={setTermsAccepted}
            accessibilityLabel={t('onboarding.termsAccept')}
          />
          <Text style={styles.checkboxLabel}>{t('onboarding.termsAccept')}</Text>
        </View>

        <View style={styles.row} testID="privacy-row">
          <Switch
            testID="privacy-switch"
            value={privacyAccepted}
            onValueChange={setPrivacyAccepted}
            accessibilityLabel={t('onboarding.privacyAccept')}
          />
          <Text style={styles.checkboxLabel}>{t('onboarding.privacyAccept')}</Text>
        </View>
      </View>

      <View style={styles.divider} />

      {/* Optional display name */}
      <View style={styles.section}>
        <Text style={styles.inputLabel}>{t('onboarding.displayNameLabel')}</Text>
        <TextInput
          testID="display-name-input"
          style={styles.input}
          value={displayName}
          onChangeText={setDisplayName}
          placeholder={t('onboarding.displayNamePlaceholder')}
          placeholderTextColor={theme.colors.textSecondary}
          maxLength={120}
          autoCapitalize="words"
          autoCorrect={false}
          returnKeyType="done"
        />
      </View>

      <View style={styles.divider} />

      {/* Optional partner stats opt-in — unchecked by default */}
      <View style={styles.section}>
        <View style={styles.row} testID="partner-stats-row">
          <Switch
            testID="partner-stats-switch"
            value={partnerStatsOptIn}
            onValueChange={setPartnerStatsOptIn}
            accessibilityLabel={t('onboarding.partnerStatsOptIn')}
          />
          <Text style={styles.checkboxLabel}>{t('onboarding.partnerStatsOptIn')}</Text>
        </View>
        <Text style={styles.partnerNote}>{t('onboarding.partnerStatsNote')}</Text>
      </View>

      {error ? <Text style={styles.error}>{error}</Text> : null}

      <KccButton
        testID="onboarding-continue-button"
        label={isLoading ? t('onboarding.loading') : t('onboarding.continueButton')}
        onPress={() => void handleContinue()}
        disabled={!canContinue}
        variant="primary"
      />
    </ScrollView>
  );
};

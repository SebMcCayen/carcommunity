/**
 * LoginScreen — platform-aware login UI.
 *
 * iOS:     Shows "Logga in med Apple" placeholder button.
 * Android: Shows "Logga in med Google" placeholder button.
 * Other:   Shows an unsupported-platform notice.
 *
 * This screen does NOT implement real Apple or Google Sign-In.
 * Real provider SDKs must be integrated before any production launch.
 *
 * TODO: Replace placeholder login with expo-apple-authentication on iOS.
 * TODO: Replace placeholder login with @react-native-google-signin/google-signin on Android.
 * TODO: Backend must remain the source of truth for all access, roles, and suspension checks.
 */

import { ActivityIndicator, Platform, ScrollView, StyleSheet, Text, View } from 'react-native';

import { KccButton } from '../components/KccButton';
import { useAppTheme } from '../hooks/useAppTheme';
import { useAuth, getPlatformAuthProvider } from '../hooks/useAuth';
import { useI18n } from '../hooks/useI18n';

/**
 * A clearly-marked development-only placeholder identity token.
 * This is NOT a real Apple or Google identity token.
 * Must be replaced by real provider SDK output before production.
 *
 * @devOnly NOT PRODUCTION-READY
 */
const DEV_PLACEHOLDER_IDENTITY_TOKEN = 'dev-placeholder-identity-token-not-for-production';

export const LoginScreen = () => {
  const { t } = useI18n();
  const { theme } = useAppTheme();
  const { isLoading, error, login } = useAuth();

  const provider = getPlatformAuthProvider();

  const handleLogin = async () => {
    if (!provider) return;
    await login(provider, DEV_PLACEHOLDER_IDENTITY_TOKEN);
  };

  const platformHint = Platform.OS === 'ios' ? t('auth.iosHint') : t('auth.androidHint');

  return (
    <ScrollView
      testID="login-screen"
      contentContainerStyle={[
        styles.container,
        { backgroundColor: theme.colors.pageBackground, padding: theme.spacing[6] },
      ]}
    >
      <View style={styles.content}>
        <Text
          style={[
            styles.title,
            {
              color: theme.colors.textPrimary,
              fontSize: theme.typography.size.headingLg,
              fontWeight: theme.typography.weight.semibold,
              marginBottom: theme.spacing[3],
            },
          ]}
        >
          {t('auth.loginTitle')}
        </Text>

        <Text
          style={[
            styles.subtitle,
            {
              color: theme.colors.textSecondary,
              fontSize: theme.typography.size.bodyMd,
              marginBottom: theme.spacing[2],
            },
          ]}
        >
          {t('auth.loginSubtitle')}
        </Text>

        {provider !== null && (
          <Text
            style={[
              styles.platformHint,
              {
                color: theme.colors.textSecondary,
                fontSize: theme.typography.size.bodySm,
                marginBottom: theme.spacing[8],
              },
            ]}
          >
            {platformHint}
          </Text>
        )}

        {isLoading && (
          <View testID="login-loading" style={styles.loadingRow}>
            <ActivityIndicator
              color={theme.colors.brandPrimary}
              accessibilityLabel={t('auth.loading')}
            />
            <Text
              style={[
                styles.loadingText,
                {
                  color: theme.colors.textSecondary,
                  fontSize: theme.typography.size.bodySm,
                  marginLeft: theme.spacing[2],
                },
              ]}
            >
              {t('auth.loading')}
            </Text>
          </View>
        )}

        {error !== null && !isLoading && (
          <View
            testID="login-error"
            style={[
              styles.errorBox,
              {
                backgroundColor: theme.colors.subtleBackground,
                borderRadius: theme.radius.md,
                padding: theme.spacing[3],
                marginBottom: theme.spacing[4],
                borderLeftWidth: 3,
                borderLeftColor: theme.colors.statusError,
              },
            ]}
          >
            <Text
              style={[
                styles.errorText,
                { color: theme.colors.statusError, fontSize: theme.typography.size.bodySm },
              ]}
            >
              {t('auth.errorGeneric')}
            </Text>
          </View>
        )}

        {provider === null ? (
          <View testID="login-platform-unsupported" style={styles.unsupportedBox}>
            <Text
              style={[
                styles.unsupportedText,
                {
                  color: theme.colors.textSecondary,
                  fontSize: theme.typography.size.bodyMd,
                  textAlign: 'center',
                },
              ]}
            >
              {t('auth.platformUnsupported')}
            </Text>
          </View>
        ) : (
          <View testID="login-button-container" style={styles.buttonContainer}>
            {provider === 'apple' && (
              <KccButton
                testID="login-apple-button"
                label={t('auth.appleLoginButton')}
                onPress={handleLogin}
                disabled={isLoading}
              />
            )}

            {provider === 'google' && (
              <KccButton
                testID="login-google-button"
                label={t('auth.googleLoginButton')}
                onPress={handleLogin}
                disabled={isLoading}
              />
            )}

            {/* DEV-ONLY indicator: visible to make clear this is not production login */}
            <Text
              testID="login-dev-label"
              style={[
                styles.devLabel,
                {
                  color: theme.colors.textSecondary,
                  fontSize: theme.typography.size.caption,
                  textAlign: 'center',
                  marginTop: theme.spacing[2],
                },
              ]}
            >
              {t('auth.devLoginLabel')}
            </Text>
          </View>
        )}

        <View style={[styles.privacySection, { marginTop: theme.spacing[8] }]}>
          <Text
            style={[
              styles.privacyText,
              {
                color: theme.colors.textSecondary,
                fontSize: theme.typography.size.caption,
                marginBottom: theme.spacing[1],
              },
            ]}
          >
            {t('auth.privacyNote')}
          </Text>
          <Text
            style={[
              styles.privacyText,
              {
                color: theme.colors.textSecondary,
                fontSize: theme.typography.size.caption,
              },
            ]}
          >
            {t('auth.deleteAccountNote')}
          </Text>
        </View>
      </View>
    </ScrollView>
  );
};

const styles = StyleSheet.create({
  container: {
    flexGrow: 1,
    justifyContent: 'center',
  },
  content: {
    maxWidth: 400,
    width: '100%',
    alignSelf: 'center',
  },
  title: {
    letterSpacing: -0.5,
  },
  subtitle: {
    lineHeight: 24,
  },
  platformHint: {
    lineHeight: 20,
  },
  loadingRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 16,
  },
  loadingText: {
    lineHeight: 20,
  },
  errorBox: {},
  errorText: {
    lineHeight: 20,
  },
  buttonContainer: {
    gap: 8,
  },
  devLabel: {
    opacity: 0.7,
  },
  unsupportedBox: {
    paddingVertical: 16,
  },
  unsupportedText: {
    lineHeight: 24,
  },
  privacySection: {},
  privacyText: {
    lineHeight: 18,
  },
});

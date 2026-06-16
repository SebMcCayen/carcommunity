/**
 * LoginScreen — platform-aware login UI.
 *
 * iOS:     Shows "Logga in med Apple" button (native Apple Sign-In in native mode).
 * Android: Shows "Logga in med Google" button (native Google Sign-In in native mode).
 * Other:   Shows an unsupported-platform notice.
 *
 * Auth modes:
 *   - EXPO_PUBLIC_AUTH_MODE=native: Uses real provider SDKs (expo-apple-authentication
 *     on iOS, @react-native-google-signin/google-signin on Android). Requires a
 *     custom Expo native build — does NOT work in Expo Go.
 *   - EXPO_PUBLIC_AUTH_MODE=dev (default): Uses a clearly-marked placeholder identity
 *     token for development without a native build.
 *
 * TODO (production): Set EXPO_PUBLIC_AUTH_MODE=native and verify Apple/Google
 *   capability and configuration before App Store / Play Store submission.
 * TODO (account linking): If the backend returns an account-linking error,
 *   surface a linking flow here rather than showing a generic error.
 */

import { ActivityIndicator, Platform, ScrollView, StyleSheet, Text, View } from 'react-native';

import { KccButton } from '../components/KccButton';
import { publicEnv } from '../config/env';
import { useAppTheme } from '../hooks/useAppTheme';
import { useAuth, getPlatformAuthProvider } from '../hooks/useAuth';
import { useI18n } from '../hooks/useI18n';
import { useNativeLogin, NativeLoginCancelledError } from '../hooks/useNativeLogin';

/**
 * Development-only placeholder identity token.
 * NOT a real Apple or Google identity token.
 * Only used when EXPO_PUBLIC_AUTH_MODE=dev (the default).
 *
 * @devOnly NOT PRODUCTION-READY
 */
const DEV_PLACEHOLDER_IDENTITY_TOKEN = 'dev-placeholder-identity-token-not-for-production';

export const LoginScreen = () => {
  const { t } = useI18n();
  const { theme } = useAppTheme();
  const { isLoading, error, login } = useAuth();
  const { signIn } = useNativeLogin();

  const provider = getPlatformAuthProvider();

  const handleLogin = async () => {
    if (!provider) return;

    if (publicEnv.authMode === 'native') {
      // --- Native auth path ---
      // Use the real provider SDK to obtain an identity token, then forward it
      // to the backend. The identity token is never stored or logged.
      try {
        const result = await signIn();
        if (!result) return; // Unsupported platform (handled by provider === null guard above)
        await login(result.provider, result.identityToken);
      } catch (err) {
        if (err instanceof NativeLoginCancelledError) {
          // User cancelled the sign-in sheet — no error shown in UI.
          return;
        }
        // Other native SDK errors fall through to useAuth's error handling.
        await login(provider, ''); // Trigger error state in useAuth
      }
    } else {
      // --- DEV-only placeholder path ---
      // @devOnly This path must never run in production.
      // The placeholder token is not a real Apple or Google identity token.
      await login(provider, DEV_PLACEHOLDER_IDENTITY_TOKEN);
    }
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

            {/* DEV-ONLY indicator: only shown when authMode is not 'native' */}
            {publicEnv.authMode !== 'native' && (
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
            )}
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

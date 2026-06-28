/**
 * NotificationSettingsScreen — push and in-app notification preferences.
 *
 * Shows:
 *  - Push notifications master state (OS permission status)
 *  - Per-category push and in-app toggles
 *  - Clear explanation if OS permission is denied
 *  - Link to system settings if needed
 *
 * UX rules:
 *  - Clearly distinguish OS push permission from backend category preferences.
 *  - If OS permission is denied, show a safe explanation (not a re-prompt).
 *  - Do not enable categories without user intent.
 *  - Essential in-app account messages remain enabled (enforced by backend).
 *  - Do not combine push opt-in with terms acceptance.
 *  - Do not repeatedly prompt after denial.
 *
 * Accessibility:
 *  - All toggles have accessibilityRole and accessibilityLabel.
 *  - Essential categories show a non-interactive label instead of a toggle.
 */

import { Linking, ScrollView, StyleSheet, Switch, Text, View } from 'react-native';

import type { NotificationCategory, NotificationPreferenceSummary } from '@carcommunity/shared/notifications';
import { ACTIVE_NOTIFICATION_CATEGORIES, ESSENTIAL_NOTIFICATION_CATEGORIES } from '@carcommunity/shared/notifications';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useAppTheme } from '../hooks/useAppTheme';
import { useI18n } from '../hooks/useI18n';
import { KccButton } from '../components/KccButton';
import { KccCard } from '../components/KccCard';
import { ScreenContainer } from '../components/ScreenContainer';
import {
  checkPushPermissionStatus,
  type PushPermissionStatus,
} from '../session/pushNotificationSetup';
import { getNotificationPreferences, patchNotificationPreferences } from '../api/notifications';
import { loadSessionToken } from '../storage/tokenStorage';

// ---------------------------------------------------------------------------
// Category label helpers
// ---------------------------------------------------------------------------

function useCategoryLabels() {
  const { t } = useI18n();

  return (category: NotificationCategory): string => {
    switch (category) {
      case 'event_reminder':
        return t('notifications.categoryEventReminder');
      case 'event_updated':
        return t('notifications.categoryEventUpdated');
      case 'event_cancelled':
        return t('notifications.categoryEventCancelled');
      case 'admin_message':
        return t('notifications.categoryAdminMessage');
      case 'account_warning':
        return t('notifications.categoryAccountWarning');
      case 'account_suspension':
        return t('notifications.categoryAccountSuspension');
      case 'subscription_status':
        return t('notifications.categorySubscription');
      case 'system_notice':
        return t('notifications.categorySystem');
      default:
        return category;
    }
  };
}

// ---------------------------------------------------------------------------
// Preference row
// ---------------------------------------------------------------------------

interface PreferenceRowProps {
  category: NotificationCategory;
  label: string;
  pref: NotificationPreferenceSummary | undefined;
  pushPermissionGranted: boolean;
  onTogglePush: (category: NotificationCategory, value: boolean) => void;
  onToggleInApp: (category: NotificationCategory, value: boolean) => void;
}

const PreferenceRow = ({
  category,
  label,
  pref,
  pushPermissionGranted,
  onTogglePush,
  onToggleInApp,
}: PreferenceRowProps) => {
  const { theme } = useAppTheme();
  const { t } = useI18n();
  const isEssential = ESSENTIAL_NOTIFICATION_CATEGORIES.includes(category);

  return (
    <View
      style={[
        styles.prefRow,
        { borderBottomColor: theme.colors.borderDefault },
      ]}
    >
      <Text
        style={[
          styles.prefLabel,
          { color: theme.colors.textPrimary, fontSize: theme.typography.size.bodyMd },
        ]}
      >
        {label}
      </Text>

      <View style={styles.prefToggles}>
        {/* In-app toggle */}
        {isEssential ? (
          <Text
            style={[
              styles.essentialLabel,
              { color: theme.colors.textSecondary, fontSize: theme.typography.size.bodySm },
            ]}
          >
            {t('notifications.settingsEssential')}
          </Text>
        ) : (
          <View style={styles.toggleGroup}>
            <Text
              style={[
                styles.toggleLabel,
                { color: theme.colors.textSecondary, fontSize: theme.typography.size.caption },
              ]}
            >
              {t('notifications.settingsInApp')}
            </Text>
            <Switch
              accessibilityRole="switch"
              accessibilityLabel={`${label} ${t('notifications.settingsInApp')}`}
              value={pref?.inAppEnabled ?? true}
              onValueChange={(v) => onToggleInApp(category, v)}
              trackColor={{
                false: theme.colors.borderDefault,
                true: theme.colors.brandPrimary,
              }}
            />
          </View>
        )}

        {/* Push toggle — only shows if OS permission is granted */}
        {pushPermissionGranted && (
          <View style={styles.toggleGroup}>
            <Text
              style={[
                styles.toggleLabel,
                { color: theme.colors.textSecondary, fontSize: theme.typography.size.caption },
              ]}
            >
              {t('notifications.settingsPush')}
            </Text>
            <Switch
              accessibilityRole="switch"
              accessibilityLabel={`${label} ${t('notifications.settingsPush')}`}
              value={pref?.pushEnabled ?? false}
              onValueChange={(v) => onTogglePush(category, v)}
              trackColor={{
                false: theme.colors.borderDefault,
                true: theme.colors.brandPrimary,
              }}
            />
          </View>
        )}
      </View>
    </View>
  );
};

// ---------------------------------------------------------------------------
// Main screen
// ---------------------------------------------------------------------------

export const NotificationSettingsScreen = () => {
  const { theme } = useAppTheme();
  const { t } = useI18n();
  const getCategoryLabel = useCategoryLabels();

  const [preferences, setPreferences] = useState<NotificationPreferenceSummary[]>([]);
  const [pushPermission, setPushPermission] = useState<PushPermissionStatus>('undetermined');
  const [isLoading, setIsLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;
    return () => { mountedRef.current = false; };
  }, []);

  useEffect(() => {
    const loadData = async () => {
      try {
        const [permStatus, stored] = await Promise.all([
          checkPushPermissionStatus(),
          loadSessionToken().catch(() => null),
        ]);

        const token = stored?.token ?? undefined;
        const prefRes = await getNotificationPreferences(token);

        if (!mountedRef.current) return;
        setPushPermission(permStatus);
        setPreferences(prefRes.data.preferences);
      } catch {
        // Non-fatal — fall back to defaults so toggles still work.
        if (!mountedRef.current) return;
        setPreferences(
          ACTIVE_NOTIFICATION_CATEGORIES.map((category) => ({
            category,
            pushEnabled: false,
            inAppEnabled: true,
            updatedAt: new Date(0).toISOString(),
          })),
        );
      } finally {
        if (mountedRef.current) setIsLoading(false);
      }
    };

    void loadData();
  }, []);

  const handleToggle = useCallback(
    async (
      category: NotificationCategory,
      field: 'pushEnabled' | 'inAppEnabled',
      value: boolean,
    ) => {
      // Optimistic update.
      setPreferences((prev) =>
        prev.map((p) =>
          p.category === category ? { ...p, [field]: value } : p,
        ),
      );

      setIsSaving(true);
      const stored = await loadSessionToken().catch(() => null);
      const token = stored?.token ?? undefined;
      try {
        const res = await patchNotificationPreferences([{ category, [field]: value }], token);
        if (!mountedRef.current) return;
        setPreferences(res.data.preferences);
      } catch {
        // Refresh from backend to avoid incorrect rollback on failure.
        if (!mountedRef.current) return;
        try {
          const prefRes = await getNotificationPreferences(token);
          if (!mountedRef.current) return;
          setPreferences(prefRes.data.preferences);
        } catch {
          // Non-fatal — user can retry later.
        }
      }
      } finally {
        if (mountedRef.current) setIsSaving(false);
      }
    },
    [],
  );

  const prefMap = new Map(preferences.map((p) => [p.category, p]));

  const pushPermissionGranted = pushPermission === 'granted';
  const pushPermissionDenied = pushPermission === 'denied';

  return (
    <ScreenContainer>
      <ScrollView contentContainerStyle={{ paddingBottom: 32 }}>
        {/* Push permission section */}
        <KccCard
          title={t('notifications.settingsPushTitle')}
          body={
            pushPermissionDenied
              ? t('notifications.settingsPushDeniedBody')
              : pushPermissionGranted
              ? t('notifications.settingsPushGrantedBody')
              : t('notifications.settingsPushUndeterminedBody')
          }
          footer={
            pushPermissionDenied ? (
              <KccButton
                label={t('notifications.settingsOpenSystemSettings')}
                variant="secondary"
                onPress={() => Linking.openSettings()}
              />
            ) : undefined
          }
        />

        {/* Category preferences */}
        <View
          style={[
            styles.section,
            {
              backgroundColor: theme.colors.surfaceBackground,
              borderColor: theme.colors.borderDefault,
              marginTop: theme.spacing[4],
            },
          ]}
        >
          <Text
            style={[
              styles.sectionTitle,
              {
                color: theme.colors.textSecondary,
                fontSize: theme.typography.size.bodySm,
                padding: theme.spacing[3],
              },
            ]}
          >
            {t('notifications.settingsCategoriesTitle')}
          </Text>

          {isLoading ? null : (
            ACTIVE_NOTIFICATION_CATEGORIES.map((category) => (
              <PreferenceRow
                key={category}
                category={category}
                label={getCategoryLabel(category)}
                pref={prefMap.get(category)}
                pushPermissionGranted={pushPermissionGranted}
                onTogglePush={(cat, val) => void handleToggle(cat, 'pushEnabled', val)}
                onToggleInApp={(cat, val) => void handleToggle(cat, 'inAppEnabled', val)}
              />
            ))
          )}
        </View>

        {isSaving && (
          <Text
            style={[
              styles.savingText,
              { color: theme.colors.textSecondary, fontSize: theme.typography.size.bodySm },
            ]}
          >
            {t('notifications.settingsSaving')}
          </Text>
        )}
      </ScrollView>
    </ScreenContainer>
  );
};

// ---------------------------------------------------------------------------
// Styles
// ---------------------------------------------------------------------------

const styles = StyleSheet.create({
  section: {
    borderRadius: 12,
    borderWidth: StyleSheet.hairlineWidth,
    overflow: 'hidden',
  },
  sectionTitle: {
    textTransform: 'uppercase',
    letterSpacing: 0.5,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  prefRow: {
    padding: 14,
    borderBottomWidth: StyleSheet.hairlineWidth,
    gap: 8,
  },
  prefLabel: {
    fontWeight: '500',
  },
  prefToggles: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 16,
    flexWrap: 'wrap',
  },
  toggleGroup: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
  },
  toggleLabel: {},
  essentialLabel: {
    fontStyle: 'italic',
  },
  savingText: {
    textAlign: 'center',
    marginTop: 12,
  },
});

/**
 * Modal shown after the user stops a live location session.
 *
 * Prompts the user to save or discard their drive.
 * No action is preselected — the user must make an explicit choice.
 *
 * Privacy:
 *  - Save is only triggered on explicit user action.
 *  - Discard deletes any temporary route data.
 *  - Route history is never public.
 *  - No preselected save action.
 *
 * Accessibility:
 *  - Buttons have appropriate accessibilityRole and accessibilityLabel.
 *  - Buttons are disabled while a request is in flight to prevent duplicates.
 */

import { useState } from 'react';
import { ActivityIndicator, Modal, Pressable, StyleSheet, Text, View } from 'react-native';

import { useAppTheme } from '../hooks/useAppTheme';
import { useI18n } from '../hooks/useI18n';

export interface SaveDrivePromptModalProps {
  visible: boolean;
  sessionId: string | null;
  onSave: (sessionId: string) => Promise<void>;
  onDiscard: (sessionId: string) => Promise<void>;
  onDismiss: () => void;
}

/**
 * Post-drive save/discard prompt.
 *
 * Shown after a live location session stops.
 * The user must explicitly choose "Spara körning" or "Kasta körning".
 * Dismissing the modal retains temporary data for a short period without saving.
 */
export const SaveDrivePromptModal = ({
  visible,
  sessionId,
  onSave,
  onDiscard,
  onDismiss,
}: SaveDrivePromptModalProps) => {
  const { t } = useI18n();
  const { theme } = useAppTheme();
  const [isBusy, setIsBusy] = useState(false);
  const [result, setResult] = useState<'none' | 'saved' | 'discarded'>('none');

  const handleSave = async () => {
    if (!sessionId || isBusy) return;
    setIsBusy(true);
    try {
      await onSave(sessionId);
      setResult('saved');
    } catch {
      // Error handling is done by the parent via the onSave callback
    } finally {
      setIsBusy(false);
    }
  };

  const handleDiscard = async () => {
    if (!sessionId || isBusy) return;
    setIsBusy(true);
    try {
      await onDiscard(sessionId);
      setResult('discarded');
    } catch {
      // Error handling is done by the parent via the onDiscard callback
    } finally {
      setIsBusy(false);
    }
  };

  const handleDismiss = () => {
    if (isBusy) return;
    setResult('none');
    onDismiss();
  };

  return (
    <Modal
      visible={visible}
      transparent
      animationType="fade"
      onRequestClose={handleDismiss}
      accessibilityViewIsModal
    >
      <View style={styles.overlay}>
        <View
          style={[
            styles.card,
            {
              backgroundColor: theme.colors.surfaceBackground,
              borderColor: theme.colors.borderDefault,
              borderRadius: theme.radius.lg,
              padding: theme.spacing[6],
              gap: theme.spacing[4],
            },
          ]}
          accessibilityRole="dialog"
          accessibilityLabel={t('savedDrives.promptTitle')}
        >
          <Text
            style={[styles.title, { color: theme.colors.textPrimary }]}
            accessibilityRole="header"
          >
            {t('savedDrives.promptTitle')}
          </Text>

          {result === 'none' && (
            <>
              <Text style={[styles.body, { color: theme.colors.textSecondary }]}>
                {t('savedDrives.promptBody')}
              </Text>
              <Text style={[styles.body, { color: theme.colors.textSecondary }]}>
                {t('savedDrives.promptPrivacyNote')}
              </Text>
              <Text style={[styles.body, { color: theme.colors.textSecondary }]}>
                {t('savedDrives.promptNotPublic')}
              </Text>

              {isBusy ? (
                <ActivityIndicator color={theme.colors.brandPrimary} />
              ) : (
                <>
                  <Pressable
                    style={[
                      styles.button,
                      styles.primaryButton,
                      {
                        backgroundColor: theme.colors.brandPrimary,
                        borderRadius: theme.radius.md,
                        paddingVertical: theme.spacing[3],
                        paddingHorizontal: theme.spacing[4],
                      },
                    ]}
                    onPress={() => void handleSave()}
                    disabled={isBusy}
                    accessibilityRole="button"
                    accessibilityLabel={t('savedDrives.saveAction')}
                    accessibilityState={{ disabled: isBusy }}
                  >
                    <Text style={[styles.buttonText, { color: theme.colors.textInverse }]}>
                      {t('savedDrives.saveAction')}
                    </Text>
                  </Pressable>

                  <Pressable
                    style={[
                      styles.button,
                      {
                        backgroundColor: theme.colors.subtleBackground,
                        borderColor: theme.colors.borderDefault,
                        borderWidth: 1,
                        borderRadius: theme.radius.md,
                        paddingVertical: theme.spacing[3],
                        paddingHorizontal: theme.spacing[4],
                      },
                    ]}
                    onPress={() => void handleDiscard()}
                    disabled={isBusy}
                    accessibilityRole="button"
                    accessibilityLabel={t('savedDrives.discardAction')}
                    accessibilityState={{ disabled: isBusy }}
                  >
                    <Text style={[styles.buttonText, { color: theme.colors.textSecondary }]}>
                      {t('savedDrives.discardAction')}
                    </Text>
                  </Pressable>
                </>
              )}
            </>
          )}

          {result === 'discarded' && (
            <>
              <Text style={[styles.body, { color: theme.colors.textSecondary }]}>
                {t('savedDrives.noKörningSaved')}
              </Text>
              <Pressable
                style={[
                  styles.button,
                  {
                    backgroundColor: theme.colors.subtleBackground,
                    borderColor: theme.colors.borderDefault,
                    borderWidth: 1,
                    borderRadius: theme.radius.md,
                    paddingVertical: theme.spacing[3],
                    paddingHorizontal: theme.spacing[4],
                  },
                ]}
                onPress={handleDismiss}
                accessibilityRole="button"
              >
                <Text style={[styles.buttonText, { color: theme.colors.textSecondary }]}>
                  {t('savedDrives.closeButton')}
                </Text>
              </Pressable>
            </>
          )}

          {result === 'saved' && (
            <>
              <Text style={[styles.body, { color: theme.colors.statusSuccess }]}>
                {t('savedDrives.saveSuccess')}
              </Text>
              <Pressable
                style={[
                  styles.button,
                  {
                    backgroundColor: theme.colors.subtleBackground,
                    borderColor: theme.colors.borderDefault,
                    borderWidth: 1,
                    borderRadius: theme.radius.md,
                    paddingVertical: theme.spacing[3],
                    paddingHorizontal: theme.spacing[4],
                  },
                ]}
                onPress={handleDismiss}
                accessibilityRole="button"
              >
                <Text style={[styles.buttonText, { color: theme.colors.textSecondary }]}>
                  {t('savedDrives.closeButton')}
                </Text>
              </Pressable>
            </>
          )}
        </View>
      </View>
    </Modal>
  );
};

const styles = StyleSheet.create({
  overlay: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.5)',
    justifyContent: 'center',
    alignItems: 'center',
    padding: 24,
  },
  card: {
    width: '100%',
    maxWidth: 400,
    borderWidth: 1,
  },
  title: {
    fontSize: 18,
    fontWeight: '700',
  },
  body: {
    fontSize: 14,
    lineHeight: 20,
  },
  button: {
    alignItems: 'center',
  },
  primaryButton: {},
  buttonText: {
    fontSize: 15,
    fontWeight: '600',
  },
});

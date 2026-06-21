/**
 * EventChatScreen — event-specific text chat for eligible members.
 *
 * Access rules (enforced by backend; client-side check is UX only):
 *   - Active member_monthly entitlement required.
 *   - RSVP must be `going` or `maybe`.
 *   - Event must be published.
 *   - Blocking is enforced in both directions by the backend.
 *
 * Safe-driving: chat input is disabled while a safe-driving placeholder
 * reports that the user may be driving.
 *
 * Security notes:
 *   - Chat messages are plain text — NEVER rendered with dangerouslySetInnerHTML.
 *   - Chat history is not persisted to device storage.
 *   - Backend is the source of truth for all access decisions.
 *   - Token is never logged.
 */

import { useCallback, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  FlatList,
  KeyboardAvoidingView,
  Platform,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import { useRoute } from '@react-navigation/native';
import type { RouteProp } from '@react-navigation/native';
import { canAccessMemberFeatures } from '@carcommunity/shared/users';

import type { EventChatMessage } from '@carcommunity/shared/event-chat';
import { CHAT_MESSAGE_REPORT_REASONS } from '@carcommunity/shared/event-chat';

import { ScreenContainer } from '../components/ScreenContainer';
import { LockedFeatureNotice } from '../components/LockedFeatureNotice';
import { useAppTheme } from '../hooks/useAppTheme';
import { useAuth } from '../hooks/useAuth';
import { useI18n } from '../hooks/useI18n';
import { useEventChat } from '../hooks/useEventChat';
import type { RootStackParamList } from '../navigation/types';

type EventChatRouteProp = RouteProp<RootStackParamList, 'EventChat'>;

// ---------------------------------------------------------------------------
// Message bubble
// ---------------------------------------------------------------------------

type MessageBubbleProps = {
  message: EventChatMessage;
  onReport: (messageId: string) => void;
  onBlock: (userId: string) => void;
};

function MessageBubble({ message, onReport, onBlock }: MessageBubbleProps) {
  const { theme } = useAppTheme();
  const { t } = useI18n();

  const isRemoved = message.moderationState === 'removed';

  const handleLongPress = useCallback(() => {
    if (message.isOwnMessage || isRemoved) return;
    Alert.alert(
      message.author.displayName ?? t('chat.unknownAuthor'),
      undefined,
      [
        {
          text: t('chat.reportMessage'),
          onPress: () => onReport(message.id),
        },
        {
          text: t('blocking.blockUser'),
          style: 'destructive',
          onPress: () => onBlock(message.author.userId),
        },
        { text: t('blocking.blockCancelAction'), style: 'cancel' },
      ],
    );
  }, [message, isRemoved, t, onReport, onBlock]);

  return (
    <View
      style={[
        styles.bubbleContainer,
        message.isOwnMessage ? styles.bubbleOwn : styles.bubbleOther,
      ]}
    >
      {!message.isOwnMessage && !isRemoved && (
        <Text style={[styles.authorName, { color: theme.colors.textSecondary }]}>
          {message.author.displayName ?? t('chat.unknownAuthor')}
        </Text>
      )}
      <TouchableOpacity
        onLongPress={handleLongPress}
        activeOpacity={0.8}
        style={[
          styles.bubble,
          {
            backgroundColor: message.isOwnMessage
              ? theme.colors.brandPrimary
              : theme.colors.surfaceBackground,
            borderRadius: theme.radius.md,
            padding: theme.spacing[2],
          },
          isRemoved && { opacity: 0.5 },
        ]}
      >
        {/* Plain text only — never use dangerouslySetInnerHTML or similar */}
        <Text
          style={[
            styles.messageText,
            {
              color: message.isOwnMessage ? '#FFFFFF' : theme.colors.textPrimary,
              fontStyle: isRemoved ? 'italic' : 'normal',
            },
          ]}
        >
          {message.message}
        </Text>
        <Text style={[styles.timestamp, { color: message.isOwnMessage ? 'rgba(255,255,255,0.7)' : theme.colors.textSecondary }]}>
          {new Date(message.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
        </Text>
      </TouchableOpacity>
    </View>
  );
}

// ---------------------------------------------------------------------------
// Main screen
// ---------------------------------------------------------------------------

export const EventChatScreen = () => {
  const { t } = useI18n();
  const { theme } = useAppTheme();
  const route = useRoute<EventChatRouteProp>();
  const { eventId, eventRsvpStatus } = route.params;
  const { currentUser, withToken } = useAuth();

  const [inputText, setInputText] = useState('');
  const flatListRef = useRef<FlatList<EventChatMessage>>(null);

  // Client-side eligibility check — UX only. Backend enforces the real decision.
  const isMember =
    currentUser !== null &&
    canAccessMemberFeatures({
      role: currentUser.roles[0] ?? 'user',
      status: currentUser.status,
      subscriptionEntitlement: currentUser.subscriptionEntitlement,
    });

  const hasEligibleRsvp = eventRsvpStatus === 'going' || eventRsvpStatus === 'maybe';
  const isEligible = isMember && hasEligibleRsvp;

  const {
    messages,
    screenState,
    error,
    nextCursor,
    isSending,
    isDriving,
    sendMessage,
    reportMessage,
    loadOlderMessages,
  } = useEventChat({ eventId, isEligible, withToken });

  const handleSend = useCallback(async () => {
    const text = inputText.trim();
    if (!text) return;
    setInputText('');
    await sendMessage(text);
    // Scroll to bottom after sending
    flatListRef.current?.scrollToEnd({ animated: true });
  }, [inputText, sendMessage]);

  const handleReport = useCallback(
    (messageId: string) => {
      const reasonButtons = CHAT_MESSAGE_REPORT_REASONS.map((reason) => ({
        text: t(`chat.reportReason.${reason}`),
        onPress: () => void reportMessage(messageId, { reason }),
      }));
      Alert.alert(
        t('chat.reportMessage'),
        t('chat.reportReasonPrompt'),
        [...reasonButtons, { text: t('blocking.blockCancelAction'), style: 'cancel' as const }],
      );
    },
    [t, reportMessage],
  );

  const handleBlock = useCallback(
    (userId: string) => {
      Alert.alert(
        t('blocking.blockConfirmTitle'),
        t('blocking.blockConfirmBody'),
        [
          {
            text: t('blocking.blockConfirmAction'),
            style: 'destructive',
            onPress: () => {
              // Blocking is handled by the existing blocking flow in the app.
              // After blocking, the next poll will exclude the blocked user's messages.
              void withToken(async (token) => {
                try {
                  const { blockUser } = await import('../api/blocking');
                  await blockUser(userId, token);
                } catch {
                  // Block failure is surfaced at the blocking API level.
                }
              });
            },
          },
          { text: t('blocking.blockCancelAction'), style: 'cancel' },
        ],
      );
    },
    [t, withToken],
  );

  // ---------------------------------------------------------------------------
  // Access gate
  // ---------------------------------------------------------------------------

  if (!isMember) {
    return (
      <ScreenContainer testID="event-chat-screen">
        <LockedFeatureNotice testID="event-chat-member-gate" message={t('chat.memberRequired')} />
      </ScreenContainer>
    );
  }

  if (!hasEligibleRsvp) {
    return (
      <ScreenContainer testID="event-chat-screen">
        <View style={styles.centeredContent}>
          <Text style={[styles.accessNotice, { color: theme.colors.textSecondary }]}>
            {t('chat.rsvpRequired')}
          </Text>
        </View>
      </ScreenContainer>
    );
  }

  if (screenState === 'access_lost') {
    return (
      <ScreenContainer testID="event-chat-screen">
        <View style={styles.centeredContent}>
          <Text style={[styles.accessNotice, { color: theme.colors.textSecondary }]}>
            {t('chat.accessLost')}
          </Text>
        </View>
      </ScreenContainer>
    );
  }

  // ---------------------------------------------------------------------------
  // Main chat UI
  // ---------------------------------------------------------------------------

  return (
    <KeyboardAvoidingView
      style={[styles.flex, { backgroundColor: theme.colors.pageBackground }]}
      behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
      keyboardVerticalOffset={Platform.OS === 'ios' ? 88 : 0}
    >
      {/* Safe-driving warning — always visible */}
      <View
        style={[
          styles.safetyBanner,
          {
            backgroundColor: theme.colors.brandPrimary,
            paddingHorizontal: theme.spacing[3],
            paddingVertical: theme.spacing[1],
          },
        ]}
      >
        <Text style={[styles.safetyText, { color: '#FFFFFF' }]}>
          {t('chat.safeDrivingWarning')}
        </Text>
      </View>

      {/* Message list */}
      {screenState === 'loading' ? (
        <View style={styles.centeredContent}>
          <ActivityIndicator color={theme.colors.brandPrimary} />
        </View>
      ) : screenState === 'error' ? (
        <View style={styles.centeredContent}>
          <Text style={[styles.accessNotice, { color: theme.colors.statusError }]}>
            {error ?? t('chat.errorLoading')}
          </Text>
        </View>
      ) : (
        <FlatList
          ref={flatListRef}
          data={messages}
          testID="event-chat-message-list"
          keyExtractor={(item) => item.id}
          contentContainerStyle={[styles.listContent, { padding: theme.spacing[2] }]}
          onEndReached={nextCursor ? () => void loadOlderMessages() : undefined}
          onEndReachedThreshold={0.2}
          ListEmptyComponent={
            <View style={styles.centeredContent}>
              <Text style={[styles.accessNotice, { color: theme.colors.textSecondary }]}>
                {t('chat.empty')}
              </Text>
            </View>
          }
          renderItem={({ item }) => (
            <MessageBubble
              message={item}
              onReport={handleReport}
              onBlock={handleBlock}
            />
          )}
        />
      )}

      {/* Input bar */}
      <View
        style={[
          styles.inputBar,
          {
            backgroundColor: theme.colors.surfaceBackground,
            borderTopColor: theme.colors.borderDefault,
            padding: theme.spacing[2],
            gap: theme.spacing[2],
          },
        ]}
      >
        {isDriving ? (
          <Text
            testID="event-chat-driving-notice"
            style={[styles.drivingNotice, { color: theme.colors.textSecondary }]}
          >
            {t('chat.drivingDisabled')}
          </Text>
        ) : (
          <>
            <TextInput
              testID="event-chat-input"
              style={[
                styles.input,
                {
                  backgroundColor: theme.colors.pageBackground,
                  color: theme.colors.textPrimary,
                  borderColor: theme.colors.borderDefault,
                  borderRadius: theme.radius.sm,
                  padding: theme.spacing[2],
                },
              ]}
              placeholder={t('chat.inputPlaceholder')}
              placeholderTextColor={theme.colors.textSecondary}
              value={inputText}
              onChangeText={setInputText}
              multiline
              maxLength={1000}
              returnKeyType="send"
              blurOnSubmit={false}
              editable={!isSending}
              accessible
              accessibilityLabel={t('chat.inputPlaceholder')}
            />
            <TouchableOpacity
              testID="event-chat-send-button"
              style={[
                styles.sendButton,
                {
                  backgroundColor:
                    inputText.trim() && !isSending
                      ? theme.colors.brandPrimary
                      : theme.colors.borderDefault,
                  borderRadius: theme.radius.sm,
                  padding: theme.spacing[2],
                },
              ]}
              onPress={() => void handleSend()}
              disabled={!inputText.trim() || isSending}
              accessible
              accessibilityLabel={t('chat.sendButton')}
            >
              {isSending ? (
                <ActivityIndicator color="#FFFFFF" size="small" />
              ) : (
                <Text style={styles.sendButtonText}>{t('chat.sendButton')}</Text>
              )}
            </TouchableOpacity>
          </>
        )}
      </View>
    </KeyboardAvoidingView>
  );
};

const styles = StyleSheet.create({
  flex: { flex: 1 },
  safetyBanner: {},
  safetyText: { fontSize: 12, textAlign: 'center' },
  listContent: { flexGrow: 1 },
  centeredContent: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 16,
  },
  accessNotice: { fontSize: 14, textAlign: 'center', lineHeight: 20 },
  bubbleContainer: { marginVertical: 4 },
  bubbleOwn: { alignItems: 'flex-end' },
  bubbleOther: { alignItems: 'flex-start' },
  authorName: { fontSize: 12, marginBottom: 2 },
  bubble: { maxWidth: '80%' },
  messageText: { fontSize: 15, lineHeight: 20 },
  timestamp: { fontSize: 11, marginTop: 2, textAlign: 'right' },
  inputBar: {
    flexDirection: 'row',
    alignItems: 'flex-end',
    borderTopWidth: StyleSheet.hairlineWidth,
  },
  input: { flex: 1, maxHeight: 100, borderWidth: StyleSheet.hairlineWidth, fontSize: 15 },
  sendButton: { minWidth: 60, alignItems: 'center', justifyContent: 'center' },
  sendButtonText: { color: '#FFFFFF', fontWeight: '600', fontSize: 15 },
  drivingNotice: { flex: 1, fontSize: 13, textAlign: 'center', padding: 8 },
});

/**
 * useEventChat — hook for loading and posting event chat messages.
 *
 * Polls conservatively while the chat screen is visible and the app is in
 * the foreground. Stops polling on:
 *   - screen hidden / app backgrounded
 *   - user logged out
 *   - access lost (entitlement or event access)
 *
 * Safe-driving: input is disabled while safe-driving mode is active.
 * A conservative placeholder hook is used until motion detection is available.
 *
 * Security notes:
 * - Chat messages are plain text. Never render with dangerouslySetInnerHTML.
 * - Chat history is not persisted to device storage.
 * - Backend is the source of truth for all access decisions.
 * - Token is never logged.
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { AppState, type AppStateStatus } from 'react-native';

import type { EventChatMessage } from '@carcommunity/shared/event-chat';

import {
  EventChatApiError,
  loadEventChatMessages,
  postEventChatMessage,
  reportEventChatMessage,
} from '../api/event-chat';
import type { ReportChatMessageRequest } from '@carcommunity/shared/event-chat';

/** Interval in milliseconds between chat polls while screen is visible. */
const POLL_INTERVAL_MS = 7_000;

export type ChatScreenState =
  | 'loading'
  | 'empty'
  | 'loaded'
  | 'sending'
  | 'error'
  | 'access_lost';

export interface UseEventChatOptions {
  eventId: string;
  /**
   * Whether the current user is eligible to read and post chat.
   * Client-side only for UX — backend enforces the real decision.
   */
  isEligible: boolean;
  /** Auth token from useAuth. Never log this value. */
  withToken: <T>(fn: (token: string) => Promise<T>) => Promise<T | null>;
}

export interface UseEventChatResult {
  messages: EventChatMessage[];
  screenState: ChatScreenState;
  error: string | null;
  nextCursor: string | null;
  /** True while a send is in progress. */
  isSending: boolean;
  /** True if the safe-driving placeholder considers the user to be driving. */
  isDriving: boolean;
  /** Returns true on success, false on failure. */
  sendMessage: (text: string) => Promise<boolean>;
  reportMessage: (messageId: string, request: ReportChatMessageRequest) => Promise<void>;
  loadOlderMessages: () => Promise<void>;
}

/**
 * Placeholder safe-driving hook.
 * TODO: Integrate with a real driving/motion state when available.
 * Returns false (not driving) conservatively until detection is implemented.
 */
function useSafeDrivingPlaceholder(): boolean {
  // TODO: Wire to actual app driving state when motion detection is implemented.
  // For now, always returns false (safe to type) as a conservative placeholder.
  return false;
}

export function useEventChat({
  eventId,
  isEligible,
  withToken,
}: UseEventChatOptions): UseEventChatResult {
  const [messages, setMessages] = useState<EventChatMessage[]>([]);
  const [screenState, setScreenState] = useState<ChatScreenState>('loading');
  const [error, setError] = useState<string | null>(null);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [isSending, setIsSending] = useState(false);

  const isDriving = useSafeDrivingPlaceholder();

  // Refs to prevent overlapping poll requests and to track mount state.
  const pollInFlight = useRef(false);
  const isMounted = useRef(true);
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const appStateRef = useRef<AppStateStatus>(AppState.currentState);

  const clearPolling = useCallback(() => {
    if (intervalRef.current !== null) {
      clearInterval(intervalRef.current);
      intervalRef.current = null;
    }
  }, []);

  const clearChatData = useCallback(() => {
    if (!isMounted.current) return;
    setMessages([]);
    setNextCursor(null);
    setError(null);
  }, []);

  /** Fetch the latest messages. Prevents overlapping requests. */
  const fetchMessages = useCallback(async () => {
    if (pollInFlight.current) return;
    if (!isEligible) {
      if (isMounted.current) {
        setScreenState('access_lost');
        clearChatData();
      }
      return;
    }

    pollInFlight.current = true;
    try {
      const result = await withToken((token) =>
        loadEventChatMessages({ eventId, token }),
      );

      if (!isMounted.current) return;

      if (!result) {
        setScreenState('access_lost');
        clearPolling();
        clearChatData();
        return;
      }

      setMessages(result.data.messages);
      setNextCursor(result.meta.nextCursor);
      setError(null);
      setScreenState(result.data.messages.length === 0 ? 'empty' : 'loaded');
    } catch (err) {
      if (!isMounted.current) return;
      const status = err instanceof EventChatApiError ? err.status : 0;
      if (status === 403 || status === 401) {
        setScreenState('access_lost');
        clearPolling();
        clearChatData();
      } else {
        setError(null);
        setScreenState('error');
      }
    } finally {
      pollInFlight.current = false;
    }
  }, [eventId, isEligible, withToken, clearChatData, clearPolling]);

  /** Start conservative polling. Stops when unmounted or access is lost. */
  const startPolling = useCallback(() => {
    clearPolling();
    if (!isEligible) return;
    intervalRef.current = setInterval(() => {
      void fetchMessages();
    }, POLL_INTERVAL_MS);
  }, [clearPolling, fetchMessages, isEligible]);

  // Initial load + start polling when screen becomes visible and user is eligible.
  useEffect(() => {
    isMounted.current = true;

    if (!isEligible) {
      setScreenState('access_lost');
      clearChatData();
      clearPolling();
      return;
    }

    void fetchMessages();
    startPolling();

    return () => {
      isMounted.current = false;
      clearPolling();
    };
  }, [eventId, isEligible, fetchMessages, startPolling, clearPolling, clearChatData]); // All callbacks are stable useCallback refs

  // Stop polling when app enters background; resume on foreground.
  useEffect(() => {
    const subscription = AppState.addEventListener('change', (nextState: AppStateStatus) => {
      const prev = appStateRef.current;
      appStateRef.current = nextState;

      if (nextState === 'background' || nextState === 'inactive') {
        clearPolling();
      } else if (nextState === 'active' && prev !== 'active' && isEligible) {
        void fetchMessages();
        startPolling();
      }
    });

    return () => subscription.remove();
  }, [clearPolling, fetchMessages, isEligible, startPolling]);

  /** Send a new plain-text message. Returns true on success, false on failure. */
  const sendMessage = useCallback(
    async (text: string): Promise<boolean> => {
      const trimmed = text.trim();
      if (!trimmed || isSending) return false;

      setIsSending(true);
      setScreenState('sending');
      try {
        const result = await withToken((token) =>
          postEventChatMessage({ eventId, message: trimmed, token }),
        );
        if (!isMounted.current) return false;
        if (result) {
          setMessages((prev) => [...prev, result.data.message]);
          setScreenState('loaded');
          setError(null);
          return true;
        }
        return false;
      } catch (err) {
        if (!isMounted.current) return false;
        const status = err instanceof EventChatApiError ? err.status : 0;
        if (status === 403 || status === 401) {
          setScreenState('access_lost');
          clearPolling();
          clearChatData();
        } else {
          setScreenState('loaded');
        }
        return false;
      } finally {
        if (isMounted.current) setIsSending(false);
      }
    },
    [eventId, isSending, withToken, clearChatData, clearPolling],
  );

  /** Report a message. Returns silently regardless of prior reports. */
  const reportMessageFn = useCallback(
    async (messageId: string, request: ReportChatMessageRequest) => {
      try {
        await withToken((token) =>
          reportEventChatMessage({ eventId, messageId, request, token }),
        );
      } catch {
        // Swallow — reporter experience is best-effort; backend records the report.
      }
    },
    [eventId, withToken],
  );

  /** Load older messages using cursor pagination. */
  const loadOlderMessages = useCallback(async () => {
    if (!nextCursor || pollInFlight.current) return;
    pollInFlight.current = true;
    try {
      const result = await withToken((token) =>
        loadEventChatMessages({ eventId, before: nextCursor, token }),
      );
      if (!isMounted.current) return;
      if (result) {
        setMessages((prev) => [...result.data.messages, ...prev]);
        setNextCursor(result.meta.nextCursor);
      }
    } catch {
      // Swallow — older messages are best-effort.
    } finally {
      pollInFlight.current = false;
    }
  }, [eventId, nextCursor, withToken]);

  return {
    messages,
    screenState,
    error,
    nextCursor,
    isSending,
    isDriving,
    sendMessage,
    reportMessage: reportMessageFn,
    loadOlderMessages,
  };
}

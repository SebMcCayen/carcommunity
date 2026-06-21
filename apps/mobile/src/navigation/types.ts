import type { EventTeaser, EventRsvpStatus } from '@carcommunity/shared/events';

export type RootStackParamList = {
  Login: undefined;
  Onboarding: undefined;
  MainTabs: undefined;
  Settings: undefined;
  About: undefined;
  LiveLocation: undefined;
  PrivacySettings: undefined;
  BlockedUsers: undefined;
  EventDetail: { eventId: string; teaser: EventTeaser };
  EventChat: { eventId: string; eventTitle: string; eventRsvpStatus: EventRsvpStatus | null };
};

export type MainTabParamList = {
  Home: undefined;
  Map: undefined;
  Events: undefined;
  Chat: undefined;
  Profile: undefined;
};

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
  GroupDrive: { eventId: string; eventTitle: string; eventRsvpStatus: EventRsvpStatus | null };
  SavedDrives: undefined;
  SavedDriveDetail: { driveId: string };
  VehicleDetail: { vehicleId: string };
  VehicleForm: { vehicleId?: string };
  Badges: undefined;
  PointsWallet: undefined;
  CrownHunt: undefined;
  PartnerDetail: { partnerId: string };
  PartnerApplication: undefined;
  BillboardDetail: { billboardId: string };
};

export type MainTabParamList = {
  Home: undefined;
  Map: undefined;
  Events: undefined;
  Chat: undefined;
  Garage: undefined;
  Profile: undefined;
};

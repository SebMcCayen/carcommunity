# Alert age for new accounts

Android and iOS seed **1 week** only when Firebase's successful credential
exchange reports `additionalUserInfo.isNewUser == true`, and only if this device
has no saved alert-age choice. Opening an upgraded app, restoring a session,
reinstalling, or signing into an existing account does not seed the new default.

Existing accounts without a preference retain **All**, including existing iOS
accounts that previously had no age-filter control. Every explicit selection,
including All, is persisted and takes precedence over initialization. Preferences
remain device-local and survive sign-out; there is no account migration or
backend deployment.

Both platforms offer 3 days, 1 week, 30 days and All. Only Trafikverket incidents
with a known original posting time are filtered. Member reports and incidents
without a posting time remain visible. The boundary is inclusive: an alert
exactly seven days old is still shown with the one-week setting.

The iOS layers sheet, native map markers and accessible marker overlay use the
same selection. Changing the choice reapplies the display filter immediately;
it does not delete reports or change the backend query.

Regression coverage lives in Android's `IncidentAgeFilterTest` and
`IncidentAgeFilterPreferenceStoreTest`, and iOS's `MapLayerPreferencesTests` and
`IncidentMapTests`.

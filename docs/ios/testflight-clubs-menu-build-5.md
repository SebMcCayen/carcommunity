# TestFlight 0.1.0 (5): Social → Club availability

Android always renders the Club entry in Social (`AuthenticatedApp.kt`), while `SharedClubRoute` checks the server activation before enabling reads/actions. Build 4 incorrectly hid the iOS entry when `config/sharedClubs` could not be confirmed.

Build 5 always renders the alphabetically sorted entry. `ClubsScreen`, its coordinator generation checks and the Firebase repository retain all existing server activation/authentication guards. Offline, unavailable and config-less users reach the existing unavailable message; no backend feature is activated and no club actions are enabled.

Native acceptance opens the real Social menu at normal/largest accessibility text sizes without Firebase, enters Club, checks the unavailable state and absence of Create/Join, returns to Social and confirms map navigation remains usable. The complete iOS pipeline remains required before merge. No production deployment or paid service is added.

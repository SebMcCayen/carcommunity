# Mobile Platform Architecture and Feature Parity

## Overview

The Kungsbacka Car Community mobile application is implemented as two completely separate native applications:

- iOS: Swift and SwiftUI
- Android: Kotlin and Jetpack Compose

The applications do not share executable mobile source code.

Despite using separate codebases, both applications must provide equivalent user-facing functionality, business behavior, security controls, API behavior, localization, and data handling.

Functional parity between iOS and Android is a mandatory project requirement.

## Project structure

The mobile applications are located in:

- `apps/ios`
- `apps/android`

Shared specifications and contracts may be located in:

- `contracts`
- `design`
- `docs/features`
- `docs/architecture`
- `docs/adr`

The following may be shared as specifications, but must be implemented natively on each platform:

- API contracts
- Business rules
- Validation rules
- Feature flags
- Analytics event names
- Error codes
- Localization source texts
- Design tokens
- Icons and branding
- Acceptance criteria
- Security and privacy requirements
- Test scenarios

Do not introduce React Native, Flutter, Kotlin Multiplatform, Xamarin, .NET MAUI, shared mobile runtime code, or another cross-platform mobile framework unless an approved architecture decision explicitly changes this rule.

## Mandatory mobile parity rule

Whenever a change introduces, modifies, removes, or affects mobile functionality, Copilot must:

1. Evaluate the effect on both iOS and Android.
2. Update both native applications when the feature applies to both platforms.
3. Preserve equivalent user-facing behavior.
4. Preserve equivalent business rules.
5. Preserve equivalent validation and error handling.
6. Preserve equivalent security and privacy protections.
7. Preserve equivalent analytics and logging behavior.
8. Update Swedish and English localization for both platforms.
9. Add or update tests for both platforms.
10. Update shared feature documentation and API contracts when required.

A mobile feature must not be considered complete when only one platform has been implemented.

Do not silently omit the implementation for the other platform.

## Platform-native implementation

Equivalent functionality does not require identical source code or pixel-identical interfaces.

Each platform must follow its native conventions.

### iOS

Use:

- Swift
- SwiftUI
- Swift Concurrency
- Apple platform APIs
- Swift Package Manager
- XCTest or Swift Testing

Follow Apple Human Interface Guidelines and native iOS behavior.

Use native technologies when relevant, including:

- Sign in with Apple
- StoreKit 2
- Core Location
- UserNotifications
- Keychain
- Background Tasks
- Mapbox Maps SDK for iOS

### Android

Use:

- Kotlin
- Jetpack Compose
- Kotlin Coroutines and Flow
- Android Architecture Components
- Gradle Version Catalogs
- JUnit and Compose UI testing

Follow Material 3 and Android platform conventions.

Use native technologies when relevant, including:

- Google Sign-In or Credential Manager
- Google Play Billing
- Android location APIs
- Firebase Cloud Messaging
- Android Keystore
- WorkManager
- Mapbox Maps SDK for Android

The visual design may differ where required by native platform conventions, but the capability, business outcome, permissions, security, and user value must remain equivalent.

## Authentication differences

Authentication is intentionally platform-specific in the MVP:

- iOS uses Sign in with Apple only.
- Android uses Google Sign-In only.
- Account linking between providers is not included in the MVP.

These authentication differences are approved and must not be treated as a parity violation.

Both authentication flows must create and use the same KCC backend account model and authorization rules.

The backend must never trust roles, subscription status, permissions, or account ownership supplied by the mobile client.

## Shared backend contracts

Both mobile applications must use the same versioned backend API contract.

The API contract is the source of truth for:

- Request models
- Response models
- Validation constraints
- Error codes
- Pagination
- Authentication requirements
- Authorization requirements
- Feature flags
- Date and time formats
- Units and measurement formats

When changing an API used by mobile applications:

1. Update the versioned backend API contract (for example, the shared TypeScript contracts in `packages/shared`) first or in the same change.
2. Update the iOS implementation.
3. Update the Android implementation.
4. Update tests for both clients.
5. Preserve backward compatibility when existing released application versions may still call the API.

Do not create platform-specific backend behavior unless required by platform rules and documented explicitly.

## Localization

Both applications must support Swedish and English.
Swedish is the default MVP language, but any shipped user-facing UI must also include English translations (always via i18n keys).

Every new or changed user-facing string must be added to both applications.

Do not hard-code user-facing text in SwiftUI or Jetpack Compose views.

Use native localization systems:

- iOS: String Catalogs or localized resources
- Android: `strings.xml` and localized resource directories

Localization keys should use consistent semantic naming across both platforms where practical.

## Accessibility

Equivalent accessibility must be implemented on both platforms.

For every user-facing feature, consider:

- Screen reader support
- Scalable text
- Contrast
- Touch target size
- Reduced motion
- Clear focus order
- Accessible labels
- Accessible error messages

Use:

- VoiceOver support on iOS
- TalkBack support on Android

Accessibility must not be implemented on only one platform.

## Security and privacy

Both applications must follow the same security and privacy requirements.

Mandatory rules:

- Never store access tokens in plain text.
- Use Keychain on iOS.
- Use Android Keystore-backed secure storage on Android.
- Never log tokens, credentials, payment information, private chat content, or exact GPS coordinates in general application logs.
- Validate all server responses and user input.
- Enforce authorization on the backend.
- Do not trust client-side roles or subscription status.
- Request permissions only when the related feature is used.
- Explain why location, notification, photo, or camera permission is required.
- Stop live location sharing when a drive ends.
- Delete unsaved drive data when the user chooses not to save it.
- Follow the same account deletion and data retention rules on both platforms.

Security controls must not be weaker on one platform.

## Driving safety

Driving-related functionality must prioritize safety over engagement.

Both applications must:

- Show the same safety warning before starting a drive.
- Keep the driving interface simple.
- Avoid requiring complex interaction during an active drive.
- Provide a clear emergency stop and delete action.
- Keep the screen awake only while required by the active drive.
- Restore normal screen behavior when the drive ends or is cancelled.
- Provide equivalent alerts for hazards, police reports, speed cameras, and road obstacles.
- Respect platform-specific background location requirements.

Do not add functionality that encourages chat interaction or complex input while driving.

## Map functionality

Both applications use Mapbox native SDKs.

Maintain parity for:

- Standard map view
- Satellite map view
- 2D and 3D modes where supported
- User location
- Active driver markers
- VIP indicators
- Offers
- Events
- Hazard markers
- Route display
- Search
- Map filters
- Location warnings

Do not add Mapbox Navigation SDK or turn-by-turn navigation unless an approved architecture decision authorizes it.

Mapbox-specific logic should be isolated behind platform-specific map service abstractions so that the provider can be replaced later.

## Subscription functionality

Subscription behavior must be equivalent on both platforms.

Use:

- StoreKit 2 on iOS
- Google Play Billing on Android

A service such as RevenueCat may be used if approved by the project architecture.

The backend must be the source of truth for subscription entitlements.

Do not unlock premium functionality solely from locally cached purchase state.

Safety-related functionality must remain available without a paid subscription.

## Analytics and logging

Both applications must use the same analytics event names and event meanings.

For each event, keep equivalent fields when applicable.

Examples:

- `drive_started`
- `drive_completed`
- `drive_deleted`
- `hazard_reported`
- `offer_opened`
- `subscription_started`
- `friend_request_sent`

Platform-specific fields may be added, but shared fields must remain consistent.

Do not collect analytics data that is not needed.

Do not include personal data, tokens, exact location, or message contents in analytics events.

## Testing requirements

A cross-platform mobile feature must include appropriate validation for both platforms.

### iOS tests

Add or update:

- Unit tests
- View model tests
- Service tests
- API client tests
- UI tests when appropriate

### Android tests

Add or update:

- Unit tests
- View model tests
- Repository tests
- API client tests
- Compose UI tests when appropriate

Shared acceptance scenarios should be documented once and verified on both platforms.

Do not mark a mobile feature complete when tests exist for only one platform without documented justification.

## Dependency management

Dependencies are managed independently for each native application.

### iOS

Use Swift Package Manager.

Commit and maintain the resolved dependency state.

### Android

Use Gradle Version Catalogs where practical.

Commit and maintain dependency lock or verification metadata where configured.

For both platforms:

- Prefer platform APIs before adding dependencies.
- Avoid unnecessary dependencies.
- Prefer actively maintained projects.
- Verify licensing.
- Avoid packages with unresolved critical vulnerabilities.
- Pin or constrain dependency versions appropriately.
- Update dependencies through reviewed pull requests.
- Do not replace a stable dependency only because a newer alternative exists.
- Do not introduce dependencies that duplicate existing functionality.

## Feature implementation workflow

When implementing a new mobile feature, follow this order:

1. Read the feature requirements.
2. Review the shared API contract.
3. Identify privacy and security implications.
4. Identify required permissions.
5. Define shared acceptance criteria.
6. Implement the iOS version.
7. Implement the Android version.
8. Add Swedish and English localization.
9. Add tests for iOS.
10. Add tests for Android.
11. Update API contracts and documentation.
12. Verify equivalent behavior.
13. Document any intentional differences.

A single feature may use separate commits or pull requests for iOS and Android, but the parent issue must remain open until both platforms are complete.

## Platform-specific exceptions

A change may affect only one platform when:

- The issue is a platform-specific build failure.
- The issue is a platform-specific dependency update.
- The operating system exposes no equivalent capability.
- Apple or Google policies require different behavior.
- The change concerns platform-specific signing, packaging, certificates, or store metadata.
- An approved ADR documents the difference.

A platform-specific exception must:

1. Be explicitly identified.
2. Explain why parity is not possible or not applicable.
3. State whether the difference is temporary or permanent.
4. Reference an issue or ADR.
5. Avoid changing shared business behavior unless approved.

Do not create undocumented platform differences.

## Pull request completion checklist

Before completing a mobile pull request, verify:

- [ ] The impact on both mobile platforms was evaluated.
- [ ] The iOS implementation is complete, or an approved exception exists.
- [ ] The Android implementation is complete, or an approved exception exists.
- [ ] User-facing functionality is equivalent.
- [ ] Business rules are equivalent.
- [ ] Security and privacy behavior is equivalent.
- [ ] Swedish localization is updated.
- [ ] English localization is updated.
- [ ] API contracts are updated when required.
- [ ] Analytics events are aligned.
- [ ] iOS tests are added or updated.
- [ ] Android tests are added or updated.
- [ ] Accessibility was considered on both platforms.
- [ ] Any intentional difference is documented.
- [ ] No secrets or sensitive personal data were added.
- [ ] No unnecessary dependencies were introduced.

## Copilot behavior

When asked to implement a mobile change, Copilot must not assume that updating one platform is sufficient.

Copilot should explicitly identify:

- Required iOS work
- Required Android work
- Shared API or contract changes
- Localization changes
- Testing changes
- Security and privacy considerations
- Any legitimate platform-specific differences

If a request only mentions iOS or Android but clearly changes a shared product feature, Copilot should still inspect whether equivalent work is required for the other platform.

Do not close, complete, or describe a cross-platform feature as finished until both native implementations are accounted for.

## Definition of done

A mobile feature is complete only when:

- Equivalent functionality is available on iOS and Android.
- Both implementations use the same backend business rules.
- Both support Swedish and English.
- Both meet security and privacy requirements.
- Both have appropriate automated tests.
- Shared documentation and contracts are updated.
- Any platform-specific differences are documented and approved.

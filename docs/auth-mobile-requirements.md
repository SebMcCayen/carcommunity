# Mobile Authentication Requirements

This document defines the authentication requirements for the iOS and Android
native applications. Both platforms share the same backend authentication
contract and security requirements. Provider differences are intentional and
approved (see [Authentication providers](#authentication-providers)).

---

## Architecture overview

```
iOS app       →  Sign in with Apple   →  Firebase Authentication  →  Firebase ID token
Android app   →  Google Sign-In        →  Firebase Authentication  →  Firebase ID token
                                                                             │
                                       ┌─────────────────────────────────────┘
                                       │
                                       ├── Callable Cloud Functions (via Firebase SDK)
                                       │     Auth context injected automatically
                                       │     App Check verified server-side
                                       │
                                       ├── Firestore / Realtime Database (via Firebase SDK)
                                       │     Security Rules enforce access
                                       │
                                       └── HTTP Cloud Functions (webhooks / integrations only)
                                             Authorization: Bearer <Firebase ID token>
```

- Firebase Authentication is the identity broker for both platforms.
- The **Firebase UID** (`uid`) is the canonical user identity.
- Mobile clients obtain a **Firebase ID token** from the Firebase SDK.
- **Callable functions** use the Firebase SDK authentication context automatically — clients do not attach a manual `Authorization` header.
- **HTTP bearer tokens** (`Authorization: Bearer <Firebase ID token>`) are only needed when calling HTTP Cloud Functions or other explicitly authenticated HTTP interfaces, not for callable functions or direct SDK access.
- The backend verifies identity via Firebase Admin SDK and never trusts a UID supplied in the request body or URL.
- Firebase SDKs manage token persistence and refresh internally — native apps must not manually persist Firebase ID tokens.

---

## Authentication providers

| Platform | Provider                                             | Approved difference                                          |
| -------- | ---------------------------------------------------- | ------------------------------------------------------------ |
| iOS      | Sign in with Apple (through Firebase Authentication) | Yes — Apple mandates Apple Sign-In for apps on the App Store |
| Android  | Google Sign-In (through Firebase Authentication)     | Yes — Google Sign-In is the natural default on Android       |

Account linking between providers is **not** included in the MVP.

---

## iOS requirements

### Sign in with Apple

1. Use `ASAuthorizationAppleIDProvider` to initiate the sign-in flow.
2. Generate a random nonce (SHA-256 hash) and pass it to the authorization request.
3. On success, exchange the Apple identity token and nonce for a Firebase credential:
   ```swift
   let credential = OAuthProvider.appleCredential(
       withIDToken: identityToken,
       rawNonce: rawNonce,
       fullName: fullName
   )
   Auth.auth().signIn(with: credential) { result, error in ... }
   ```
4. Call `Auth.auth().currentUser?.getIDToken()` to obtain the Firebase ID token.
5. Attach the token to API requests: `Authorization: Bearer <token>`.

### Token storage

- **Never** store the Firebase ID token or Apple identity token in plain text.
- Use **Keychain** for any token or credential that must persist across app launches.
- Firebase SDK manages its own token refresh using Keychain internally.

### Token refresh

- Firebase ID tokens expire after **1 hour**.
- Call `getIDToken(forcingRefresh: false)` before every API request — the Firebase
  SDK refreshes automatically when the token is close to expiry.
- If the backend returns `401`, force-refresh once with `forcingRefresh: true`
  before retrying. If the second attempt also returns `401`, sign the user out.

### Error handling

| Firebase error code        | User-facing action                        |
| -------------------------- | ----------------------------------------- |
| `ERROR_USER_DISABLED`      | Show account suspended message, sign out  |
| `ERROR_USER_NOT_FOUND`     | Sign out and return to login screen       |
| `ERROR_INVALID_CREDENTIAL` | Sign out and prompt user to sign in again |
| Network error              | Show offline state, do not sign out       |

### Privacy and security

- Request Sign in with Apple only when the user explicitly initiates sign-in.
- Do not log the Firebase ID token, Apple identity token, or any credential.
- Stop all background activity that requires authentication when the user signs out.

---

## Android requirements

### Google Sign-In

1. Configure `GoogleSignInOptions` with `requestIdToken(serverClientId)` using
   the OAuth web client ID from Google Cloud Console.
2. Use `GoogleSignIn.getClient(context, gso).signIn()` (or Credential Manager API)
   to initiate the flow.
3. On success, exchange the Google ID token for a Firebase credential:
   ```kotlin
   val credential = GoogleAuthProvider.getCredential(googleIdToken, null)
   FirebaseAuth.getInstance().signInWithCredential(credential)
       .addOnCompleteListener { task -> ... }
   ```
4. Call `FirebaseAuth.getInstance().currentUser?.getIdToken(false)` to obtain
   the Firebase ID token.
5. Attach the token to API requests: `Authorization: Bearer <token>`.

### Token storage

- **Never** store the Firebase ID token or Google credential in plain text.
- Use **Android Keystore**-backed secure storage (e.g. `EncryptedSharedPreferences`)
  for any credential that must persist across app restarts.
- The Firebase Android SDK manages its own token refresh internally.

### Token refresh

- Firebase ID tokens expire after **1 hour**.
- Call `getIdToken(false)` before every API request — the SDK refreshes automatically.
- If the backend returns `401`, retry once with `getIdToken(true)` (force-refresh).
  If the second attempt also returns `401`, sign the user out.

### Error handling

| Firebase error code        | User-facing action                        |
| -------------------------- | ----------------------------------------- |
| `ERROR_USER_DISABLED`      | Show account suspended message, sign out  |
| `ERROR_USER_NOT_FOUND`     | Sign out and return to login screen       |
| `ERROR_INVALID_CREDENTIAL` | Sign out and prompt user to sign in again |
| Network / timeout error    | Show offline state, do not sign out       |

### Privacy and security

- Request Google Sign-In only when the user explicitly initiates sign-in.
- Do not log the Firebase ID token, Google ID token, or any credential.
- Clear authentication state on sign-out (`FirebaseAuth.getInstance().signOut()`).
- Stop all background activity that requires authentication when the user signs out.

---

## Shared backend authentication contract

Both platforms must implement the following contract for every authenticated backend interaction.

### Callable Cloud Functions

Callable functions use the Firebase SDK authentication context automatically.

- iOS: call the function via `Functions.functions().httpsCallable(name)`.
- Android: call via `FirebaseFunctions.getInstance().getHttpsCallable(name)`.
- The SDK attaches the current user's ID token and App Check token automatically.
- Do not attach a manual `Authorization` header for callable function calls.

### Direct Firestore and Realtime Database access

Direct SDK reads and writes use the authenticated Firebase session automatically.

- Security Rules evaluate `request.auth.uid` server-side on every operation.
- No manual `Authorization` header is required.

### HTTP Cloud Functions (webhooks and integrations only)

When calling HTTP Cloud Functions that require user authentication (rare — only for integrations that cannot use callable functions):

```
Authorization: Bearer <Firebase ID token>
```

Obtain the token: `Auth.auth().currentUser?.getIDToken()` (iOS) or `FirebaseAuth.getInstance().currentUser?.getIdToken(false)` (Android).

### Token lifecycle

1. The Firebase SDK maintains the authenticated session and refreshes tokens automatically.
2. For callable functions and direct SDK access, the SDK handles token refresh transparently.
3. For HTTP functions: obtain a fresh token before each request — the SDK refreshes automatically when the token is close to expiry.
4. On `401 Unauthorized` from an HTTP function, force-refresh once before retrying. If the second attempt also returns `401`, sign the user out.
5. On repeated auth failures, sign the user out and navigate to the login screen.

### Admin authorization

- Admin access is controlled by the `admin: true` Firebase custom claim.
- The claim is set **only** by trusted Cloud Functions code — never by the mobile app.
- Mobile apps must never attempt to read or modify custom claims directly.
- The backend verifies the claim server-side — hiding UI elements on the client is not an authorization control.
- Clients must force-refresh their ID token after a privileged claim change so that callable functions receive the updated claims.

---

## Account model

| Field          | Source                               | Notes                                          |
| -------------- | ------------------------------------ | ---------------------------------------------- |
| `firebaseUid`  | Verified Firebase ID token           | Never trust a UID from the request body or URL |
| `users/{uid}`  | Firestore document (Cloud Functions) | Created on first successful authentication     |
| `role`         | Firestore `users/{uid}.role`         | Defaults to `user`; read-only from client      |
| `admin` claim  | Firebase custom claim                | Set by trusted Cloud Functions code only       |
| `activeMember` | Firebase custom claim                | Set by subscription verification callable      |
| `suspended`    | Firebase custom claim + Firestore    | Set by admin callable; enforced by backend     |

On first sign-in a Cloud Function trigger creates the `users/{uid}` Firestore document and links it to the Firebase UID. Subsequent sign-ins look up the existing document by UID.
---

## Security checklist

- [ ] Apple identity token is verified via Firebase (not directly by the app).
- [ ] Google ID token is verified via Firebase (not directly by the app).
- [ ] Firebase ID token is sent only over HTTPS.
- [ ] Firebase ID token is never logged.
- [ ] Firebase ID token is never stored in plain text.
- [ ] Token is force-refreshed on `401` before retry.
- [ ] User is signed out on repeated `401` failures.
- [ ] Background tasks that require auth stop on sign-out.
- [ ] The app never sets Firebase custom claims.
- [ ] No user ID is trusted from the request body for authorization.

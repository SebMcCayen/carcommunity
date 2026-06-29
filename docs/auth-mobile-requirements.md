# Mobile Authentication Requirements

This document defines the authentication requirements for the iOS and Android
native applications. Both platforms share the same backend authentication
contract and security requirements. Provider differences are intentional and
approved (see [Authentication providers](#authentication-providers)).

---

## Architecture overview

```
iOS app          →  Sign in with Apple   →  Firebase Authentication  →  Firebase ID token
Android app      →  Google Sign-In        →  Firebase Authentication  →  Firebase ID token
                                                                                │
                                          Backend (services/api) ←─────────────┘
                                          verifyIdToken(token)
                                          extract uid + admin claim
                                          look up / create DB user
```

- Firebase Authentication is the identity broker for both platforms.
- The **Firebase UID** (`uid`) is the canonical user identity on the backend.
- Mobile clients exchange the provider credential for a **Firebase ID token**.
- Every API request carries the Firebase ID token as `Authorization: ******`.
- The backend verifies the token with Firebase Admin SDK and never trusts a UID
  supplied in the request body or URL.

---

## Authentication providers

| Platform | Provider         | Approved difference |
| -------- | ---------------- | ------------------- |
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
5. Attach the token to API requests: `Authorization: ******`.

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

| Firebase error code         | User-facing action                         |
| --------------------------- | ------------------------------------------ |
| `ERROR_USER_DISABLED`       | Show account suspended message, sign out   |
| `ERROR_USER_NOT_FOUND`      | Sign out and return to login screen        |
| `ERROR_INVALID_CREDENTIAL`  | Sign out and prompt user to sign in again  |
| Network error               | Show offline state, do not sign out        |

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
5. Attach the token to API requests: `Authorization: ******`.

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

| Firebase error code              | User-facing action                         |
| -------------------------------- | ------------------------------------------ |
| `ERROR_USER_DISABLED`            | Show account suspended message, sign out   |
| `ERROR_USER_NOT_FOUND`           | Sign out and return to login screen        |
| `ERROR_INVALID_CREDENTIAL`       | Sign out and prompt user to sign in again  |
| Network / timeout error          | Show offline state, do not sign out        |

### Privacy and security

- Request Google Sign-In only when the user explicitly initiates sign-in.
- Do not log the Firebase ID token, Google ID token, or any credential.
- Clear authentication state on sign-out (`FirebaseAuth.getInstance().signOut()`).
- Stop all background activity that requires authentication when the user signs out.

---

## Shared API authentication contract

Both platforms must implement the following contract for every authenticated API request.

### Authorization header

```
Authorization: ******
```

### Token lifecycle

1. Obtain the token by calling the Firebase Auth SDK (see platform sections above).
2. Include it on every request to a protected backend endpoint.
3. On `401 Unauthorized`, attempt a token force-refresh **once** before giving up.
4. On repeated `401` failures, sign the user out and navigate to the login screen.

### Admin authorization

- Admin access is controlled by the `admin: true` Firebase custom claim.
- The claim is set **only** by trusted backend code — never by the mobile app.
- Mobile apps must never attempt to read or modify custom claims directly.
- The backend verifies the claim server-side on every admin endpoint — hiding
  UI elements on the client is not an authorization control.

---

## Account model

| Field         | Source                         | Notes                                         |
| ------------- | ------------------------------ | --------------------------------------------- |
| `firebaseUid` | Verified Firebase ID token     | Never trust a UID from the request body or URL |
| `userId`      | Backend database (UUID)        | Created on first successful authentication     |
| `role`        | Backend database               | Defaults to `user`                             |
| `admin` claim | Firebase custom claim          | Set by trusted backend code only              |

On first sign-in a backend user record is created and linked to the Firebase UID.
Subsequent sign-ins look up the existing record by Firebase UID.

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

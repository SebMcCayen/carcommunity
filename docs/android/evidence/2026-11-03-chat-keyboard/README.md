# Chat keyboard reading space

Android now follows the existing iOS `ChatHubScreen`/`ChatKeyboardObserver`
interaction: the Chats/Driver posts selector and channel buttons fold away while
an on-screen keyboard covers the conversation. They return after dismissal,
even if the composer retains focus. The selected room, draft and listeners stay
mounted; hiding navigation does not navigate or recreate the conversation.

The existing status-bar inset remains owned exactly once when the source header
is hidden. Horizontal room switching is disabled while its destination buttons
are hidden. Android Back dismisses the IME before leaving the hub. No iOS code,
localization, public contracts or backend configuration changes are required.

`ChatHubKeyboardNavigationTest` drives the actual platform IME against the
production hub and local messages in dark/light themes, with and without Driver.
It checks reclaimed message space, safe top inset, retained draft/focus and room,
and restoration of navigation after Back. Native before/keyboard/restored images
are exported under the test app's `chat-keyboard-navigation` external-files
folder. Test source is not execution evidence; completed results belong to the PR.

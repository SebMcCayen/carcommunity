# Public privacy information

The canonical privacy policy for CarCommunity, Driver and the homepage is:

**https://kungsbackacarcommunity.se/integritetspolicy.html**

Related pages:

- Terms: https://kungsbackacarcommunity.se/anvandarvillkor.html
- Account deletion: https://kungsbackacarcommunity.se/radera-konto.html
- Support: https://kungsbackacarcommunity.se/support.html
- Community rules: https://kungsbackacarcommunity.se/community-regler.html

Edit that single public source in `SebMcCayen/kungsbacka-car-community-homepage`.
Do not copy another editable policy into the admin app. Existing Firebase
`/privacy`, `/terms` and `/support` links are retained through small, script-free compatibility
pages in the existing Hosting output so installed apps can reach the same information without a new build.

See [routing/rollout](../app-store-public-information.md) and the
[code-backed review](../privacy-processing-review-2026-10-29.md) for source facts,
remaining operational follow-ups and the separation of local testing from actual
production deployment. Updating text does not implement the controls it describes.

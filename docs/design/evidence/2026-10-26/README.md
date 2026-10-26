# Android pass-3 native evidence

Selected unedited emulator captures, not catalogue artwork or production data.
Source/APK hashes, viewport, density, locale and theme are in
[manifest.json](manifest.json). The test-only caption is intentionally visible.

- [Compact populated chat](chat.png)
- [S03 layers and persistent footer](map-layers.png)
- [S09 supported incident types](map-report.png)
- [S42 free event detail and RSVP](event-detail.png)
- [Crown Hunt statistics](crown-stats.png)
- [S07 saved-place filters](saved-places-manage.png)

Map-shell fixtures explicitly use the stub map for geometry. Other page captures
exercise production Compose components with local inputs, rather than the full
authenticated outer shell. No photo loading, GPS, backend mutation, Play
purchase, real-member roster or end-to-end route success is implied.

Read [the validation report](../../android-redesign-validation.md) for executed
tests and remaining configured/device gates. Full 84-view/platform acceptance
remains separate from successful native component captures.

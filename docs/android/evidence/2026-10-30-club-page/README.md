# Club page evidence

Local fixture data only, native production SharedClubRoute, source baseline
6756e8f. Two representative accepted baseline images are committed here;
the complete ten-image baseline was inspected at the parent-provided Downloads
location recorded in ../../club-page-structure-audit.md. The rejected first-run
light identity image is not evidence. Parent recovered a cold-boot System UI ANR
before the successful one-test native capture run.

- before-identity-dark.png: gold reading text across identity and statistics.
- before-members-dark.png: no avatars, persistent per-member management rows.

![Before: identity and statistics](before-identity-dark.png)

![Before: member rows](before-members-dark.png)

After implementation, the capture fixture still calls the actual production
route and uses a neutral local-test-data banner. Use a fresh clubPageCaptureRun
such as after-20261030 to keep captures separate; light/dark and font-scale
arguments remain available. Parent owns builds, native tests and screenshots.
Parent completed code QA at `4f7d48efb612084780d3921dd047a11b7e2d101d`:
131/131 filtered JVM tests, lint/debug/test APKs, eleven native behavior tests,
and two successful capture invocations (twenty images at scales 1.0/2.0).

The after-image review found incomplete drawing in some scrolled normal-scale
captures, despite correct content in other captures of the same calendar/roster.
See [the visual QA report](design-qa.md) for exact paths, the inherited gray-button
assessment and the narrow follow-up. Visual sign-off is blocked; representative
after PNGs have not been promoted into this accepted evidence set yet.
No production, TalkBack, physical-device or two-device certification is implied.

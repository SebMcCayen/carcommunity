# Event end dates and times

## Behaviour

Android's shared create/edit form offers an optional end date and time after the
start has been chosen. It supports overnight and multi-day events, preserves an
existing end when editing, and lets the creator clear it. While saving, both the
picker and clear action are disabled. Explicit ends must be strictly later than
the start and no more than 72 elapsed hours later, matching the existing backend
validation. The end is not inferred as "tomorrow" from an earlier clock time:
choose the following date explicitly.

Omitting/clearing the end retains the existing backend default: end of the start
date in `Europe/Stockholm`. This is explained beside the field. Stored timestamps
are UTC instants. Android pickers and event displays use the phone's timezone;
the public website uses Stockholm time. DST transitions must not silently turn
an elapsed interval into an apparently zero-length one.

`CreateEventInput.endsAtMillis` already exists. Creates send its ISO value when
set; updates now manage `endsAt` too, sending explicit null when cleared. Edit
prefill retains the stored end. List, detail, share and calendar surfaces already
consume the event's end; map detail now uses the same `EventSchedule` formatter.
No new event fields, billing gates or backend duration rules are introduced.
Admin already offers an end field and needs no parallel UI change.

## Homepage feed and public detail

Homepage repository PR:
[SebMcCayen/kungsbacka-car-community-homepage#15](https://github.com/SebMcCayen/kungsbacka-car-community-homepage/pull/15).

Each generated entry retains `date` and `time` for old consumers and adds:

| Field      | Meaning                                                                |
| ---------- | ---------------------------------------------------------------------- |
| `startsAt` | Canonical UTC ISO start instant                                        |
| `endsAt`   | Canonical UTC ISO effective end, including the existing legacy default |

Only opted-in published events are eligible. Upcoming and ongoing entries are
sorted by start and excluded at `now >= endsAt`. The homepage also filters on the
client, so an event expires without waiting for the next backend regeneration.
Background browser tabs reconcile when resumed. Legacy feeds without ISO fields
keep their previous fallback until regenerated; generated JSON is never edited
by hand.

Cards and public detail pages show the interval, adding an end date across
midnight and offsets across DST changes. JSON-LD end dates and existing calendar
links use the same actual instants. Countdown targets the next future start;
ongoing events do not cause a negative countdown. The public detail endpoint's
existing six-hour grace remains separate from homepage-list expiry.

## Verification and delivery

- Test create/edit payloads, preserving and clearing end, strict ordering, the
  72-hour boundary, overnight/DST conversion and invalid-input no-write paths.
- Check native form pickers and saving states, and existing interval displays.
- Test feed effective ends, ongoing/exact-boundary filtering, no-end legacy
  defaults, public HTML/JSON-LD/ICS consistency, escaping and homepage fallback.
- Verify homepage behaviour in different browser timezones and narrow layouts.

Deliver the backward-compatible homepage consumer first, then the backend
producer/public detail functions through the normal reviewed deployment. The
feed gains ISO fields on its next event-triggered or scheduled regeneration;
merging code alone does not regenerate existing published JSON. Release a new
Android build after review to expose the form controls. No production deploy,
manual feed write or Play publication is performed by this implementation task.

Android is the native implementation scope for this machine. Shared Swedish /
English strings and generated Swift catalog companions are included; the iOS
form work remains with the separate Mac implementation. This is not an iOS
runtime verification claim.

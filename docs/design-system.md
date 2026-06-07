# KCC Crown UI

KCC Crown UI is the design system for the app. It defines the visual language, interaction patterns, and reusable UI rules for the MVP brand Kungsbacka Car Community (KCC), while keeping the codebase ready for future national branding.

## Design vision

KCC Crown UI should feel modern, clean, premium, automotive-inspired, trustworthy, and community-focused.

The experience should:

- feel polished without feeling luxurious to the point of distraction
- communicate trust and safety in live location features
- support quick action during driving and event participation
- reinforce community identity through subtle, consistent brand signals
- stay brand-ready so the same system can support other communities later

Start screen priority order:

1. Share/stop live location
2. Community status
3. Next event
4. Map
5. Member value

## Brand-ready design principles

- Build for configurable branding, not one fixed name.
- Do not hardcode `KCC` text directly in components.
- Use brand config for names, marks, partner labels, and future national variants.
- Use i18n for all user-facing strings.
- Use design tokens, not random hardcoded colors, spacing, or radius values.
- Support light mode, dark mode, and system theme.
- Default theme behavior must follow the system setting.
- Use the crown as a key brand element, but do not overuse it.
- Prioritize clarity and safety over decoration in driving-related flows.

## MVP brand

For the MVP, the active brand is:

- Full name: **Kungsbacka Car Community**
- Short name: **KCC**
- Design system name: **KCC Crown UI**

Brand-ready implementation guidance:

- expose full brand name, short name, and legal/marketing labels through brand config
- expose localized brand strings through i18n
- keep crown assets and references replaceable through config

Example Swedish product text:

- App title: `Kungsbacka Car Community`
- Short label: `KCC`
- Event label: `KCC-träff`

## Crown mark usage

The crown is a signature brand asset and should signal official identity, trust, and premium quality.

Use the crown for:

- app icon
- splash screen
- official events
- badges
- empty states
- official map markers
- subtle brand details

Do not use the crown for:

- every card or list item
- decorative repetition in dense screens
- generic user-generated content
- critical controls where a simple system icon is clearer

Rules:

- keep enough clear space around the crown
- avoid stretching, rotating, outlining, or recoloring outside the approved palette
- prefer subtle placement on surfaces and headers rather than repeated inline usage
- use stronger crown emphasis only for official KCC-owned experiences

## Color palette

Core palette:

| Token purpose             | Color         | Hex       |
| ------------------------- | ------------- | --------- |
| Brand Gold                | Crown Gold    | `#EAB54B` |
| Primary dark neutral      | Dark Charcoal | `#3F3E3B` |
| Deep background / ink     | Ink Black     | `#040211` |
| Primary light neutral     | Warm Ivory    | `#F8F6EF` |
| Secondary light neutral   | Soft Sand     | `#F0EBDC` |
| Mid neutral               | Muted Grey    | `#6D6C6D` |
| Border / disabled neutral | Silver Grey   | `#B4B1AD` |

Functional color rules:

- use green for active live location and positive states
- use red for errors, stop states, and destructive actions
- use gold for primary brand actions, highlights, official KCC elements, and premium details
- do not use gold as normal body text on light backgrounds if contrast is poor

## Light theme

Light theme should feel airy, premium, and readable.

Recommended balance:

- page background: Warm Ivory
- secondary surfaces: Soft Sand
- primary text: Ink Black
- secondary text: Muted Grey or Dark Charcoal depending on contrast need
- borders/dividers: Silver Grey
- primary brand accents: Crown Gold

Example light token values:

- `color.background.page = #F8F6EF`
- `color.background.surface = #FFFFFF`
- `color.background.subtle = #F0EBDC`
- `color.text.primary = #040211`
- `color.text.secondary = #6D6C6D`
- `color.border.default = #B4B1AD`
- `color.brand.primary = #EAB54B`

## Dark theme

Dark theme should feel calm, sharp, and premium without becoming harsh.

Recommended balance:

- page background: Ink Black
- elevated surfaces: Dark Charcoal
- primary text: Warm Ivory
- secondary text: Silver Grey
- borders/dividers: Muted Grey
- gold used as an accent, not as dense paragraph text

Example dark token values:

- `color.background.page = #040211`
- `color.background.surface = #3F3E3B`
- `color.background.subtle = #2A2927`
- `color.text.primary = #F8F6EF`
- `color.text.secondary = #B4B1AD`
- `color.border.default = #6D6C6D`
- `color.brand.primary = #EAB54B`

## System theme behavior

Theme modes:

- Light
- Dark
- System

Rules:

- default setting is **System**
- app theme follows the device setting when System is selected
- do not treat theme as a one-time startup choice; it must react to system theme changes
- preserve semantic meaning across themes instead of swapping arbitrary colors
- keep official map markers and brand accents recognizable in both themes

Example Swedish settings text:

- `Tema`
- `Ljust`
- `Mörkt`
- `Följ system`

## Design tokens

All reusable styling decisions should map to tokens.

Example token groups:

- color
- typography
- spacing
- radius
- elevation
- opacity
- icon size
- component state tokens

Example token naming:

- `color.brand.primary`
- `color.background.page`
- `color.text.primary`
- `color.status.success`
- `font.size.body.md`
- `space.4`
- `radius.lg`
- `elevation.2`

Rules:

- prefer semantic tokens for components and states
- avoid raw hex values or one-off spacing values in component code
- allow brand config to override brand-level tokens where appropriate

## Typography

Typography should feel modern, clear, and premium.

Roles:

- display: hero moments only
- heading: page and section hierarchy
- title: card and sheet titles
- body: standard reading text
- label: controls, pills, chips, buttons
- caption: metadata and supporting text

Example token values:

- `font.family.primary = System UI stack`
- `font.size.display.sm = 32`
- `font.size.heading.lg = 24`
- `font.size.title.md = 18`
- `font.size.body.md = 16`
- `font.size.body.sm = 14`
- `font.size.caption = 12`
- `font.weight.regular = 400`
- `font.weight.medium = 500`
- `font.weight.semibold = 600`
- `lineHeight.body.md = 24`

Rules:

- default body size should remain scalable
- avoid overly condensed uppercase labels
- preserve strong hierarchy on map-heavy screens without crowding the viewport

Example Swedish text styles:

- Heading: `Nästa träff`
- Body: `Din position delas med communityn just nu.`
- Caption: `Uppdaterad nyss`

## Spacing

Spacing should create a calm, breathable layout.

Example spacing scale:

- `space.0 = 0`
- `space.1 = 4`
- `space.2 = 8`
- `space.3 = 12`
- `space.4 = 16`
- `space.5 = 20`
- `space.6 = 24`
- `space.8 = 32`
- `space.10 = 40`
- `space.12 = 48`

Guidelines:

- use tighter spacing only for dense metadata
- prefer `space.4` and `space.6` for standard layouts
- give primary actions and live location controls generous separation

## Radius

Radius should feel refined and modern.

Example scale:

- `radius.sm = 8`
- `radius.md = 12`
- `radius.lg = 16`
- `radius.xl = 24`
- `radius.full = 9999`

Guidelines:

- inputs and buttons should feel approachable, not sharp
- cards and sheets should use softer large radii
- status pills and badges may use full radius

## Elevation and surfaces

Elevation should be subtle and functional.

Example tokens:

- `elevation.0 = none`
- `elevation.1 = low`
- `elevation.2 = medium`
- `elevation.3 = high`

Surface rules:

- use contrast and border separation before heavy shadow
- in dark mode, prefer tonal separation over dramatic shadows
- bottom sheets and dialogs may use the highest controlled elevation
- map overlays should stay readable without covering too much of the map

## Icons

Icons should be simple, legible, and consistent with an automotive-inspired UI.

Rules:

- use clean outlined or minimally filled icons consistently
- pair icons with labels for critical actions when possible
- do not rely on icon meaning alone for destructive or safety-sensitive actions
- crown iconography is reserved for official brand contexts

Example Swedish labels:

- `Dela plats`
- `Stoppa delning`
- `Nästa träff`

## Map markers

Map markers must clearly distinguish official items, people, and promotional placements.

Marker roles:

- official KCC event marker
- official KCC point of interest marker
- member live location marker
- partner marker
- billboard marker

Rules:

- official KCC markers may use crown details
- user/member markers must not look official
- billboard markers must be clearly non-intrusive and identifiable as marketing
- active live location states should use green signals
- markers must remain readable in light and dark map styles

Reusable component:

- `KccMapMarker`

## Buttons

Reusable component:

- `KccButton`

Variants:

- primary
- secondary
- tertiary
- destructive
- ghost / map overlay

Rules:

- primary buttons use gold emphasis for brand-led actions
- destructive buttons use red
- large stop/hide actions must be easy to find in driving mode
- button states must include default, pressed, disabled, and loading

Example Swedish labels:

- `Dela min position`
- `Stoppa delning`
- `Dölj mig`
- `Öppna karta`

## Cards

Reusable component:

- `KccCard`

Use cards for:

- next event summaries
- member value modules
- partner highlights
- official announcements
- social content previews

Rules:

- keep hierarchy clear with title, metadata, and action area
- use subtle crown details only for official or premium content
- avoid overly tall cards that push primary actions below the fold on start screen

## Inputs

Reusable component:

- `KccInput`

Rules:

- prioritize legibility and touch size
- support labels, helper text, error text, and disabled states
- error states must not rely on color alone
- avoid chat input in driving mode while moving

Example Swedish text:

- Label: `Sök plats`
- Helper: `Skriv ett område eller en adress`
- Error: `Det gick inte att spara. Försök igen.`

## Bottom sheets

Reusable component:

- `KccBottomSheet`

Use for:

- map details
- event previews
- partner details
- sharing controls

Rules:

- sheets should feel anchored, not abrupt
- use strong title hierarchy and clear primary action placement
- allow map context to remain visible where possible
- in driving mode, simplify content and action count

## Dialogs

Reusable component:

- `KccDialog`

Use dialogs only for high-importance confirmation or blocking information.

Rules:

- do not use dialogs for billboard marketing
- destructive actions should confirm clearly
- keep dialog copy short and direct

Example Swedish text:

- Title: `Stoppa delning?`
- Body: `Din position i realtid blir inte längre synlig för andra.`
- Confirm: `Ja, stoppa`
- Cancel: `Avbryt`

## Status pills

Reusable component:

- `KccStatusPill`

Use for quick, semantic state communication.

Examples:

- live now
- hidden
- official
- upcoming
- full

Rules:

- must combine color with text and/or icon
- should remain compact but readable

Example Swedish labels:

- `Live nu`
- `Dold`
- `Officiell`
- `Snart`

## Badges

Reusable component:

- `KccBadge`

Use badges for identity, recognition, or earned states.

Rules:

- official KCC badges may include crown usage
- achievement or membership badges should keep a premium but restrained tone
- do not confuse badges with urgent status messaging

## Avatars

Reusable component:

- `KccAvatar`

Rules:

- use avatars for member identity, not official KCC authority
- official organization identities should use a clearly distinct branded treatment
- provide fallback initials and accessible labels when images are missing

## List items

Reusable component:

- `KccListItem`

Rules:

- use list items for structured rows such as events, locations, partners, and settings
- keep tap targets large and metadata easy to scan
- support leading visuals, primary text, secondary text, and trailing status or navigation affordances without crowding

## Live location UI

Live location is a core trust feature and should feel clear and safe.

Rules:

- active live location uses green for status and confirmation
- make share/stop live location a top start screen priority
- communicate current visibility state clearly
- show privacy-relevant state changes immediately
- avoid clutter near map and location controls

Example Swedish text:

- `Du delar din position i realtid`
- `Din position är dold`
- `Synlig för communityn`
- `Stoppa delning`

## Driving mode UI

Driving mode must be simplified.

Required priorities:

- large map
- clear live location status
- large stop/hide actions
- no chat input while moving
- no Kronjakt collection while moving
- minimal distraction

Rules:

- reduce secondary content and promotional emphasis
- keep overlays sparse and high contrast
- use larger tap targets than standard mode
- calm billboards further in driving mode

Example Swedish text:

- `Körläge aktivt`
- `Fokusera på vägen`
- `Stoppa delning`

## Event UI

Events should feel official, social, and easy to scan.

Rules:

- distinguish official KCC events from community-created content
- official events may use crown mark and gold highlights carefully
- start screen should surface the next event clearly
- event cards should prioritize title, time, place, and status

Example Swedish text:

- `Nästa träff`
- `Officiell KCC-träff`
- `Anmäl intresse`

## Partner UI

Partner experiences should feel integrated but not overpower the community product.

Rules:

- identify partner content clearly
- maintain KCC Crown UI styling rather than adopting inconsistent brand treatments inside the app shell
- use premium but restrained presentation

Example Swedish text:

- `Partnererbjudande`
- `Visa mer`

## Digital billboard UI

Digital billboards are allowed, but must stay controlled and transparent.

Rules:

- must be marked as `Marknadsföring` or `Sponsrad placering`
- must not appear as popups
- must not block app functions
- must be calm in driving mode
- must be visually distinct from official KCC content and member content

Example Swedish labels:

- `Marknadsföring`
- `Sponsrad placering`

## Kronjakt UI

Kronjakt should feel playful but still fit the premium system.

Rules:

- keep it secondary to safety and live location clarity
- do not allow collection while moving in driving mode
- use gold details carefully to signal reward and brand relevance
- maintain readable rules and progress indicators

Example Swedish text:

- `Kronjakt`
- `Samla kronor`
- `Inte tillgängligt i körläge`

## Social sharing cards

Social sharing cards should follow KCC Crown UI and include the brand mark.

Rules:

- include the brand mark
- use approved palette and type hierarchy
- never include exact live location
- never include other users
- never include chat
- never include personal data
- never include locked event details
- prefer celebratory, public-safe summaries

Example Swedish sharing text:

- `På väg till kvällens träff med KCC`
- `Ses på nästa träff i Kungsbacka`

## Empty states

Reusable component:

- `KccEmptyState`

Rules:

- empty states may use subtle crown details
- message should explain the next best action
- keep tone warm and helpful

Example Swedish text:

- `Inga träffar just nu`
- `Håll utkik efter nästa officiella träff.`
- CTA: `Utforska kartan`

## Loading states

Reusable component:

- `KccLoadingState`

Rules:

- use calm loading feedback
- avoid heavy motion where it can distract drivers
- skeletons are preferred for cards and lists when structure is known
- loading text should set expectation when waits are noticeable

Example Swedish text:

- `Laddar karta...`
- `Hämtar nästa träff...`

## Error states

Reusable component:

- `KccErrorState`

Rules:

- use red for error emphasis
- provide plain-language recovery guidance
- never rely on red alone; include text and icons where helpful
- destructive states and system failures should be clearly distinct from neutral warnings

Example Swedish text:

- `Något gick fel`
- `Det gick inte att hämta kartan.`
- CTA: `Försök igen`

## Accessibility

Accessibility requirements:

- adequate contrast
- large tap targets
- screen reader labels
- not relying on color alone
- scalable text

Design guidance:

- ensure gold accents still meet contrast expectations in context
- support dynamic type and layout reflow
- label icon-only buttons for assistive tech
- keep driving mode controls large, simple, and spaced apart
- preserve semantic structure in dialogs, sheets, and cards

## i18n and Swedish MVP text

All user-facing text must go through i18n.

Rules:

- Swedish is the MVP content language for examples and initial copy
- components must not hardcode `KCC` text directly
- use brand config and i18n together for brand-bearing strings
- keep Swedish copy short, direct, and community-oriented

Examples:

- `Dela min position`
- `Nästa träff`
- `Följ system`
- `Sponsrad placering`
- `Din position är dold`

Brand-ready pattern:

- brand name placeholders should come from config
- translatable strings should support future non-Swedish locales without component rewrites

## Design rules for developers

Developer rules:

- always use reusable components where they fit: `KccButton`, `KccCard`, `KccInput`, `KccBadge`, `KccAvatar`, `KccListItem`, `KccBottomSheet`, `KccDialog`, `KccMapMarker`, `KccStatusPill`, `KccEmptyState`, `KccErrorState`, `KccLoadingState`
- always use design tokens for color, spacing, radius, and typography
- do not hardcode brand text in components
- do not hardcode random hex colors in components
- respect light, dark, and system theme behavior
- default theme setting must be system
- use green only for positive or active live location states
- use red only for errors, stop states, and destructive actions
- use gold for primary brand actions, official KCC elements, highlights, and premium details
- avoid gold paragraph text on light surfaces when contrast is weak
- keep the crown reserved for meaningful official brand moments
- keep driving mode minimal and non-distracting
- ensure billboard UI is labeled and non-blocking
- ensure social sharing output excludes sensitive or private information
- check accessibility before shipping new UI

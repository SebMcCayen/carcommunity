# Produktbeslut (Source of Truth)

Detta dokument är den låsta produktsanningen för `carcommunity` och ska användas av utvecklare, maintainers och GitHub Copilot vid produkt- och prioriteringsbeslut.

## Product vision

- Bygga en säker, inkluderande och engagerande community-app för bilintresserade.
- Live location, event och community-funktioner är kärnan i MVP.
- MVP ska vara praktiskt användbar direkt, med MVP-light nivå där det minskar risk och komplexitet.

## MVP brand and brand-ready strategy

- Appen lanseras som **Kungsbacka Car Community (KCC)**.
- Repository-namnet är **carcommunity**.
- Kodbasen måste vara brand-ready för framtida nationellt namn eller flera lokala communities.
- Kod och interna identifierare ska, där rimligt, undvika hårdkodning av KCC.

## Platforms

- The mobile platform consists of two separate native applications: iOS (Swift / SwiftUI) and Android (Kotlin / Jetpack Compose).
- Both native apps provide equivalent product functionality, security, privacy, localization, and accessibility.
- Cross-platform mobile frameworks (React Native, Expo, Flutter, Kotlin Multiplatform) are not used.
- The legacy `apps/mobile` (React Native / Expo) client was removed on 2026-07-28.

## Repository and Open Source principles

- Detta är ett open source-monorepo för app, backend och administration.
- Produktbeslut dokumenteras här och ska vara spårbara i repo-processen.
- Inga hemligheter (secrets, nycklar, credentials) får committas.

## Mobile app

- Mobilappen är huvudytan för community-användare.
- Live location, events, social funktioner och medlemsfunktioner prioriteras i MVP.
- User-facing språk i MVP är svenska.

## Backend

- Backend använder Cloud Functions for Firebase (2nd gen), Node.js 22, TypeScript.
- Cloud Firestore är primär durable databas; Firebase Realtime Database används för kortlivad realtidsdata (live location, presence).
- Firebase Authentication hanterar autentisering för iOS, Android och admin web.
- Firebase Admin SDK används för serverprivilegier. PostgreSQL och Prisma användes endast av det borttagna `services/api` och ingår inte i arkitekturen.
- Backend är source of truth för autentisering, admin-roll (via Firebase custom claims), subscription, access checks, Kronpoäng och andra känsliga beslut.

## Admin web

- Admin web används för tyngre administration och överblick.
- Admin web ansvarar för arbetsflöden som kräver mer detaljerade verktyg än mobil admin-yta.

## Existing public website responsibility

- Publik webb hanterar offentlig appinformation, support, policies, terms samt information om konto-/databorttagning.
- Webbplatsen är den offentliga informationsytan utanför appen.

## Authentication and accounts

- iOS använder Sign in with Apple via Firebase Authentication i MVP.
- Android använder Google Sign-In via Firebase Authentication i MVP.
- Admin web använder Google Sign-In via Firebase Authentication, om inte annat Firebase-stött adminprovider godkänns senare.
- Kontolänkning mellan Apple och Google ingår inte i MVP.
- Datamodellen ska förberedas så kontolänkning kan stödjas senare.
- Cloud Functions och Firebase Security Rules är auktoritativa för säkerhetskänsliga operationer.
- Roller, suspension och entitlement styrs av Firebase custom claims och auktoritativa Firestore-dokument satta av backend; aldrig av klienten.
- Firebase SDK hanterar token-persistence och token-refresh; native-appar ska inte manuellt persistera Firebase ID-tokens.
- Appen är 18+ i MVP.

## Account deletion

- Kontoborttagning måste finnas inne i appen.
- Publik webb ska också innehålla information och vägledning för konto-/databorttagning.

## Subscription model

- De varumärkesneutrala nivåerna är **Community**, **Plus** och **Supporter**.
- Community är gratis. Plus kostar **39 SEK/månad** och Supporter **119 SEK/månad**.
- Beloppen 39/119 SEK är godkända svenska listpriser för planering och adminrapportering;
  checkout ska alltid visa butikens lokaliserade pris.
- Endast månadsplaner stöds. Ingen årsplan eller introduktionsrabatt ingår.
- De permanenta produkt-id:na är `plus_monthly` och `supporter_monthly`; de får inte bytas eller
  härledas från visningsnamn.
- Kanonisk tier-typ är `community | plus | supporter`.
- Legacy-entitlement `none | member_monthly` behålls för kompatibilitet:
  `none` mappas till Community och `member_monthly` mappas uttryckligen till Plus.
- `subscriptions/{uid}.tier` införs rullande och är därför valfritt under migreringen. Ett explicit
  Supporter-värde bevaras som livscykeldata efter avslut, men `entitlement: none` ger alltid
  Community-åtkomst. Både aktiv Plus och aktiv Supporter projiceras till legacy-fältet och claimen
  `activeMember: true`.
- Community / Plus / Supporter får ha högst 2 / 5 / 10 fordon i garaget.
- Körhistorik är de 5 senaste för Community, rullande 90 dagar för Plus och obegränsad för
  Supporter.
- Plus ger exakt liveposition för andra användare, fullständiga eventdetaljer och
  partnererbjudanden. Supporter ska vara en verifierbar superset av samtliga Plus-förmågor.
- Supporter-badge visas som standard men är frivillig och kan alltid döljas av användaren.
- Detta beslut etablerar kontrakt och migreringsgrund. Det aktiverar inte paywalls,
  `MemberGating`, butiksköp eller någon provider-adapter.
- User-subscription hanteras via Apple/Google billing.
- Businessbetalningar sker via separat fakturering, inte via in-app purchase.

## Roles and permissions

- Supporter är en subscription tier, inte en behörighetsroll. Ingen separat supporter-roll skapas.
- Admin behöver inte subscription.
- Backend avgör åtkomst med hårda access checks.

## Live location sharing

- Live location är en kärnfeature.
- Delning måste vara opt-in, manuellt startad, tidsbegränsad, tydligt synlig och enkel att stoppa.
- **"Dölj mig nu"** måste stoppa delning och omedelbart ta bort senaste backend-position — och samtidigt ta bort användaren ur närhetsupptäckt (discovery-dokumentet raderas direkt), så att en delare försvinner för alla på en gång.
- **Publik närhetsupptäckt (beslut 2026-07-21):** en fristående ("Single") live-delning är synlig för andra användare i närheten medan sessionen är aktiv — vald PUBLIK/NÄRHET-modell, inte enbart vänner eller konvoj. En delare hittas via `live.listNearby` (samma närhetsmönster som incidents). Detta gäller ENDAST aktiva, opt-in-startade sessioner: ingen delas automatiskt, och delningen upphör omedelbart vid stopp/"dölj mig nu" eller när sessionen/positionen åldras ut.
- Ingen automatisk publik positionshistorik: endast den aktuella positionen under en aktiv session exponeras — ingen historik lagras eller visas publikt, och en stoppad session lämnar inget publikt spår.
- Egen delning exkluderar blockerade relationer (bägge riktningar) och suspenderade konton; en delare kan aldrig upptäckas av någon de blockerat eller som blockerat dem.
- Gratisanvändare kan dela sin egen live location.
- Aktiv subscription krävs för att se andras live positioner (avsedd framtida grind; medlemsgrindning är för närvarande avstängd i backend, så alla inloggade icke-suspenderade användare kan se just nu).
- Gratisanvändare kan se begränsad community-status men inte exakta live positioner.

## Blocking and suspension

- Admin-suspension blockerar appåtkomst omedelbart i backend, oavsett aktiv subscription.
- Suspenderade användare ska fortfarande kunna nå support, subscriptionshantering, kontoborttagning, policies och terms.
- Adminåtgärder måste loggas i audit log.

## Events and RSVP

- Gratisanvändare kan se att event finns, men detaljinformation kräver subscription.
- Eventfunktionalitet ska stödja tydlig RSVP-hantering på MVP-nivå.

## Navigation

- In-app navigation som Google Maps ingår inte i MVP.
- Använd deep links till Apple Maps / Google Maps.

## Maps

- Kartor används för live location och eventrelaterad orientering.
- Exakt live position för andra användare är subscriptionsstyrd.

## Event chat

- Event chat är event-baserad textchat i MVP.
- Inga privata DMs, bilder eller video i chat i MVP.

## Group driving

- Group driving är MVP-light och kopplad till events.

## Driving mode

- Driving mode ska främja säkert användande.
- Funktionalitet får inte uppmuntra interaktion som ökar risk under körning.

## Saved drives

- Saved drives sparas endast efter uttrycklig användarhandling när live sharing stoppats.
- Delning av saved drives ska inte inkludera exakt rutt som standard.
- Toppfart och speed-baserad ranking ingår inte i MVP.

## Social sharing

- Social delning i MVP ska vara säker, enkel och integritetsmedveten.
- Delningsnivåer får inte kringgå låsta integritets- och subscriptionsregler.

## Vehicles / Garage

- Fordon/Garage ingår som community-kontext för användare och events.
- Funktionalitet hålls MVP-light där det minskar komplexitet.

## Badges and gamification

- Badges/gamification tillåts om det stödjer community-engagemang utan att driva osäkert beteende.
- Inga mekaniker som premierar riskfylld körning.

## Kronpoäng

- Kronpoäng är digitala poäng utan kontantvärde.
- Kronpoäng kan i MVP inte köpas, säljas, överföras eller växlas mot pengar.
- Backend är source of truth för poängberäkning och beslut.

## Kronjakt

- Kronjakt får endast använda admin-godkända säkra områden.
- Kronjakt får inte uppmuntra fortkörning, osäkra stopp, snabbast-till-plats-beteende eller riskkörning.
- Kronjakt-claims kräver backendvalidering och anti-fraud-kontroller.

## Partners / companies

- Partnerföretag kan vara synliga även för gratisanvändare.
- Partners exponeras utan att få tillgång till persondata.

## Partner offers

- Partnererbjudanden kräver subscription.

## Partner applications

- Företagsansökningar hanteras som separat partnerflöde med adminbedömning.
- Affärsvillkor och betalning hanteras utanför in-app purchase.

## Partner insights and aggregated statistics

- Partnerstatistik är opt-in, aggregerad och privacy-safe.
- Företag får aldrig persondata, live location, rutter, körhistorik eller individuell spårning.
- Passeringsstatistik kräver explicit opt-in och minsta aggregeringströskel på minst 10 unika användare.

## Digital sponsored billboards

- Digitala sponsrade billboards är tillåtna som företags-addon.
- Billboardplaceringar måste markeras som marknadsföring/sponsrad placering.
- Billboards får inte vara popups, blockera UI eller uppmuntra interaktion under körning.

## Admin functionality in the mobile app

- Mobil admin-yta är för snabba åtgärder.
- Fokus: snabb moderering och operativa beslut i fält.

## Admin functionality in the admin web

- Admin web är för tyngre administration, konfiguration och uppföljning.
- Komplexa arbetsflöden ska primärt ligga här.

## Error logging and GitHub Issues

- Appfel kan publiceras till GitHub Issues för spårning.
- All data måste saneras innan publicering till GitHub Issues.
- Inga personuppgifter, tokens, credentials eller annan känslig information får exponeras.

## GitHub Copilot process

- Detta dokument är produktens source of truth för Copilot-stödd utveckling.
- Copilot-förslag ska följa låsta beslut här före antaganden.
- Vid konflikt mellan antagande och detta dokument gäller detta dokument.

## GitHub Releases and version info

- Releaser ska tydligt kommunicera version och ändringsomfattning.
- Ingen uppdelad releasefas i MVP; planerad MVP-scope gäller i sin helhet.
- Features kan ändå implementeras MVP-light där det är rimligt.

## Language and i18n

- User-facing språk i MVP är svenska.
- Engelska ska förberedas genom i18n-struktur i MVP, men fullständig engelsk översättning är inte ett MVP-krav.

## Design system: KCC Crown UI

- Appen använder **KCC Crown UI** baserat på logotyp, krona, guld, charcoal, ink black och warm ivory.
- Stöd för light/dark/system theme krävs.

## External data sources

- Externa datakällor får endast användas när de stödjer produktmål och integritetskrav.
- Känsliga beslut och behörighet måste alltid verifieras i backend oavsett extern källa.

## Performance-first principles

- Prestanda prioriteras i alla lager (mobil, backend, admin).
- Kärnflöden (live location, events, auth, subscriptionskontroller) ska prioriteras för stabil och snabb upplevelse.
- Feature flags måste finnas för riskfyllda features.

## Compliance decisions

- Endast Production Firebase-miljö används för MVP.
- Ingen DPIA görs i MVP.
- Integritets- och säkerhetskrav upprätthålls ändå genom minimidata, tydliga kontroller och sanerad felrapportering.

## Production-only environment

- MVP körs i en enda Production Firebase-miljö.
- Drift, konfiguration och releasebeslut utgår från en produktionscentrerad modell.
- Firebase Emulator Suite används för lokal utveckling och CI-tester.

## Features excluded from MVP or future goals

- Ingen police reporting-feature.
- CarPlay och Android Auto är framtida mål, inte MVP.
- Ingen privat DM, bild- eller videochat i MVP.
- Ingen in-app turn-by-turn navigation i MVP.
- Ingen account linking mellan Apple/Google i MVP.
- Ingen årsplan i subscription i MVP.
- Ingen supporter-roll i MVP.
- Ingen toppfarts-/speedranking i MVP.

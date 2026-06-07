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
- Mobilappen ska stödja iOS och Android från en gemensam kodbas.
- Rekommenderad mobilstack är React Native / Expo.

## Repository and Open Source principles
- Detta är ett open source-monorepo för app, backend och administration.
- Produktbeslut dokumenteras här och ska vara spårbara i repo-processen.
- Inga hemligheter (secrets, nycklar, credentials) får committas.

## Mobile app
- Mobilappen är huvudytan för community-användare.
- Live location, events, social funktioner och medlemsfunktioner prioriteras i MVP.
- User-facing språk i MVP är svenska.

## Backend
- Backend använder Node.js senaste LTS.
- PostgreSQL är rekommenderad databas.
- Backend är source of truth för autentisering, admin-roll, subscription, access checks, Kronpoäng och andra känsliga beslut.

## Admin web
- Admin web används för tyngre administration och överblick.
- Admin web ansvarar för arbetsflöden som kräver mer detaljerade verktyg än mobil admin-yta.

## Existing public website responsibility
- Publik webb hanterar offentlig appinformation, support, policies, terms samt information om konto-/databorttagning.
- Webbplatsen är den offentliga informationsytan utanför appen.

## Authentication and accounts
- iOS använder Apple-login i MVP.
- Android använder Google-login i MVP.
- Kontolänkning mellan Apple och Google ingår inte i MVP.
- Datamodellen ska förberedas så kontolänkning kan stödjas senare.
- Appen är 18+ i MVP.

## Account deletion
- Kontoborttagning måste finnas inne i appen.
- Publik webb ska också innehålla information och vägledning för konto-/databorttagning.

## Subscription model
- Endast en subscription i MVP: **KCC Medlem Månad**.
- Ingen årsplan i MVP.
- Intern entitlement-benämning ska vara generisk, t.ex. `member_monthly`.
- User-subscription hanteras via Apple/Google billing.
- Businessbetalningar sker via separat fakturering, inte via in-app purchase.

## Roles and permissions
- Ingen supporter-roll i MVP.
- Admin behöver inte subscription.
- Backend avgör åtkomst med hårda access checks.

## Live location sharing
- Live location är en kärnfeature.
- Delning måste vara opt-in, manuellt startad, tidsbegränsad, tydligt synlig och enkel att stoppa.
- **"Dölj mig nu"** måste stoppa delning och omedelbart ta bort senaste backend-position.
- Ingen automatisk publik positionshistorik.
- Gratisanvändare kan dela sin egen live location.
- Aktiv subscription krävs för att se andras live positioner.
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
- Endast Production-miljö i Azure används för MVP.
- Ingen DPIA görs i MVP.
- Integritets- och säkerhetskrav upprätthålls ändå genom minimidata, tydliga kontroller och sanerad felrapportering.

## Production-only environment
- MVP körs endast i Production-miljö i Azure.
- Drift, konfiguration och releasebeslut utgår från en produktionscentrerad modell.

## Features excluded from MVP or future goals
- Ingen police reporting-feature.
- CarPlay och Android Auto är framtida mål, inte MVP.
- Ingen privat DM, bild- eller videochat i MVP.
- Ingen in-app turn-by-turn navigation i MVP.
- Ingen account linking mellan Apple/Google i MVP.
- Ingen årsplan i subscription i MVP.
- Ingen supporter-roll i MVP.
- Ingen toppfarts-/speedranking i MVP.

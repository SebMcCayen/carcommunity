# Förslag: snabbare Android-menyer med delat sessionstillstånd

> **Status:** Förslag för produktgodkännande — ingen cache- eller backendändring implementeras här.
> **Datum:** 2026-10-28. Granskad CarCommunity-kod: da5a9acc.
> **Rekommendation:** Visa säker lokal data direkt, dela redan pågående läsningar och uppdatera i bakgrunden. Begränsa förladdning till små prioriterade datamängder efter att kartan blivit användbar.

## Vad som är verifierat i koden

Det finns ännu ingen uppmätt jämförelse av laddningstid på en fysisk enhet. Tabellen beskriver konkreta kodvägar och möjliga kostnader, inte bevisade antal sekunder.

| Område | Nuläge | Föreslagen första förbättring |
| --- | --- | --- |
| Klubbmeny | Tillstånd ägs av routens remember; läser state och därefter katalog för icke-medlemmar. Åtkomst-/refresh-ändringar kan starta om arbetet | Äg små resultat i inloggningssessionen och anslut routen till samma pågående hämtning |
| Klubbåtkomst | Flera kalla flöden läser konfiguration; observeAccess läser enabled igen. Endast serverbekräftade dokument ger privat åtkomst | Dela en aktuell konfigurations-/åtkomstström; bevara krav på serverbevis |
| Klubbbackend | Tillståndsvägen gör legacy-kontroller och kan läsa upp till 25 inbjudningsföräldrar seriellt | Mät delsteg; gör begränsad batch/parallell läsning där semantiken kan bevaras |
| Event | Både skal och eventroute samlar kalla flöden | Dela samma publicerade eventström inom sessionen |
| DM-märke | Skalet laddar och berikar konversationslistan för oläst-status | Utvärdera befintlig dmUnreadTotal för märket; ladda inkorgen när den behövs |
| Garage/medlemskap | Garaget är redan synlighetsstyrt och abonnemanget har en skalägd observerare | Bevara fungerande ägarskap och optimera bara uppmätt dubbelt arbete |

Källor: [SharedClubRoute](../../apps/android/app/src/main/java/com/kungsbackacarcommunity/app/clubs/SharedClubRoute.kt), [FirebaseClubRepository](../../apps/android/app/src/main/java/com/kungsbackacarcommunity/app/clubs/FirebaseClubRepository.kt), [AuthenticatedApp](../../apps/android/app/src/main/java/com/kungsbackacarcommunity/app/AuthenticatedApp.kt), [EventsRoute](../../apps/android/app/src/main/java/com/kungsbackacarcommunity/app/events/EventsRoute.kt), [klubbbackend](https://github.com/SebMcCayen/driver/blob/bc2597e3137904e0eec6dd6a91ce372d3da119fd/functions/src/shared-clubs.ts).

Vanlig omkomposition gör inte i sig ett nytt klubbanrop: effektens nycklar och routens livslängd är avgörande. Kallstart av serverfunktioner, åtkomstbevis och bildhämtning ska mätas separat innan deras andel av väntetiden påstås.

## Inspiration och befintliga byggstenar

Androids officiella [offline-first-vägledning](https://developer.android.com/topic/architecture/data-layer/offline-first) rekommenderar att lokal data kan visas utan att invänta nätet. Exempelappen [Now in Android](https://github.com/android/nowinandroid/blob/main/docs/ArchitectureLearningJourney.md) visar repository-ägda dataströmmar, lokal datakälla och separat synkning. Vi kan använda mönstret utan att införa dess hela Room-/WorkManager-arkitektur i första etappen.

[Androids startup-vägledning](https://developer.android.com/topic/performance/appstartup/analysis-optimization) rekommenderar att icke-kritiska operationer skjuts upp och att starttiden mäts. Att ladda alla menyer, privata chattar, bilder och listor samtidigt kan försena första användbara kartan och öka nättrafik och läsningar.

[Firestore har normalt diskcache på Android](https://firebase.google.com/docs/firestore/manage-data/enable-offline). Den hjälper dokument-/frågeläsningar men är inte automatiskt en cache för callable-resultat som clubsState och clubsList. Dessutom avvisar klubbkoden medvetet cachade dokument som bevis på medlemskap.

Återanvänd appens StateFlow-koordinatorer samt [DriverFeedSessionOwner](../../apps/android/app/src/main/java/com/kungsbackacarcommunity/app/driverfeed/DriverFeedSessionOwner.kt) och generationskontrollerna i [DriverFeedCoordinator](../../apps/android/app/src/main/java/com/kungsbackacarcommunity/app/driverfeed/DriverFeedCoordinator.kt). En profilcache finns redan i [FirebaseLiveProfileRepository](../../apps/android/app/src/main/java/com/kungsbackacarcommunity/app/profile/FirebaseLiveProfileRepository.kt); kopiera inte dess obegränsade nyckeltillväxt utan storleksgräns.

## Föreslaget laddningsbeteende

| Datatyp | Cache/visning | Uppdatering |
| --- | --- | --- |
| Offentlig klubbkatalog och katalogsäkra klubbkort | Högst första sidan, 25 klubbar, i sessionens begränsade minnescache | Friskhet 60 sekunder; visa senast kända data vid uppdatering, ny hämtning vid behov och manuell retry |
| Publicerade event | En delad, livscykelstyrd repository-ström | Befintlig serveruppdatering, inte en extra prenumeration per meny |
| Egen profilsammanfattning/oläst-märke | Återanvänd skalets redan aktuella tillstånd | Befintliga säkra observerare; inventera lättare oläst-aggregat |
| Privat klubbchatt, medlemslista och kalender | Session-/klubbägda, bara under bekräftad åtkomst; ingen generell offentlig cache | Befintliga serverbevis, realtidsändringar och nollställning vid åtkomstförlust |
| Bilder och loggor | Befintlig bildcache med tydlig storleksgräns | Ny bildversion/URL ogiltigförklarar gammal bild |
| Sällan använda detaljsidor | Ladda när användaren väljer dem | Återanvänd en lämplig liten, sessionsägd senaste vy om den är säker |

Menyn får ett tillstånd som skiljer på första hämtning, visningsbar data, uppdatering och fel. Befintlig säker data försvinner inte bakom en helskärmssnurra vid en vanlig refresh. Ett uppdateringsfel visar en liten indikation och retry; en första hämtning får en tidsgräns och ett tydligt fel i stället för evig laddning.

För privata vyer gäller fortsatt åtkomstkontroll. När bevis saknas eller behörighet förloras ska innehållet döljas, även om en kopia råkar finnas i minnet eller Firestore. ”Senast känd data” är inte ett medlemskap eller en abonnemangsrättighet.

## Teknik och etapper efter godkännande

1. **Mät och dela ägarskap.** Inför spårpunkter för menytryck → första innehåll, serverbekräftad åtkomst, callable-tid, cachens ålder/träff, avbrutna anrop och antal prenumerationer. Använd Macrobenchmark/Perfetto för referensmätning. Detta är diagnostik, inte uppladdning av chatttext eller användarlistor.
2. **Inför ett litet ClubSessionStore.** Äg det av aktuell autentiserad session. Nyckla resultat och pågående hämtningar med UID, sessionsgeneration, argument och åtkomstversion. Samma samtidiga fråga ska ansluta till samma arbete. Separera katalogsäkra klubbkort från invitations-/medlems-/chattdata.
3. **Dela aktuella flöden.** En konfigurations-/åtkomstström per session och en eventström där de redan används. Använd stateIn/shareIn med medveten start-/stopplivslängd. Senast återspelat behörighetstillstånd får inte ersätta ny serverbekräftelse. Behåll [ClubChatRoutes återställning i bakgrunden](../../apps/android/app/src/main/java/com/kungsbackacarcommunity/app/clubs/ClubChatRoute.kt).
4. **Förbättra sekventiellt backendarbete.** Läs inbjudningsföräldrar i avgränsade batcher, och låt roster/kalender ladda när synliga. Hoppa över återkommande legacy-skanning först om det finns en verifierad migrationsmarkör som garanterar samma säkerhet; auktoritativa skrivkontroller ligger kvar.
5. **Pröva begränsad förladdning.** Efter obligatorisk uppdatering, inloggning, onboarding och första användbara kartan: högst två extra läsjobb per session, ett samtidigt, och en mjuk startbudget på tre sekunder. Välj exempelvis första klubbkatalogsidan när funktionen är aktiv och användningen motiverar det. Ett menytryck ansluter till redan pågående jobb. Pausa schemaläggning när appen går i bakgrunden eller saknar nät.

Förladdning får inte markera chattar som lästa, starta en live-session, ändra RSVP eller hämta privata chattarkiv i bakgrunden. Ingen ny separat prenumeration för varje dold meny. Ändra inte funktioners skalning eller inför en betald cachetjänst som första åtgärd.

## Säker uppdatering och verifiering

Töm känsligt tillstånd och stoppa arbeten vid utloggning/kontobyte, spärrat eller raderat konto, avstängd funktion, klubbbyte, borttaget medlemskap, rolländring, arkivering, förlorat serverbevis eller nekad privat läsning. Sena svar ska avvisas av sessions-/generationskontroller. Bevara live-radering och befintliga [klubbåtkomsttester](../../apps/android/app/src/androidTest/java/com/kungsbackacarcommunity/app/clubs/ClubChatRevocationTest.kt).

Efter lyckad egen mutation invalidieras just berörda nycklar. För publika listor räcker kort friskhetsfönster och uppdatering vid synlighet; börja inte skriva serverdokument för varje cacheträff. TTL, begränsat antal poster och minnestak ska finnas från början. Diskcache för fler callable-resultat får bli en separat etapp med tydligt kontoägarskap och rensning.

Föreslagna acceptansmål, inte uppmätta resultat:

- Varm klubbkatalog visar första innehåll inom 100 ms på definierad referensenhet.
- En samtidig upstream-fråga/prenumeration per identisk nyckel; ingen ökning av privata bakgrundsprenumerationer vid start.
- Minst 30 procent lägre p95 för klubbens kallöppning efter en riktig baslinjemätning; två sekunder på en definierad referensanslutning är ett undersökningsmål, inte ett löfte om mobilt nät.
- Första kartans användbarhet får inte försämras av förladdningen.
- Testa offline, långsamt nät, refresh-fel med gammal säker data, upprepad navigering, kontobyte, avbrutna/sena svar samt borttagning ur klubb under pågående hämtning.

**Beslut som efterfrågas:** Godkänna mätning, delat sessionstillstånd och den begränsade klubb-/eventcachen först. Förladdning av högst två små läsjobb kommer därefter om mätningen visar nytta; full förladdning av alla menyer ingår inte i rekommendationen.

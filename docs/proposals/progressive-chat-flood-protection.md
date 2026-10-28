# Förslag: stegvis skydd mot chattspam

> **Status:** Förslag för produktgodkännande — inte en implementation eller driftsättning.
> **Datum:** 2026-10-28.
> **Kodbas:** CarCommunity da5a9acc; delad klubbbackend i Driver bc2597e3.
> **Rekommendation:** Gemensam serverkontrollerad sändningsbudget och korta, stegvis längre pauser. Behåll vanliga emojis och befintlig moderering.

## Varför den nuvarande kontrollen inte räcker

Granskningen visar olika skydd i olika chattar. Den visar inte den faktiska spammarens trafik, produktionskonfiguration eller uppmätta anropstider.

| Yta | Befintlig kontroll | Lucka |
| --- | --- | --- |
| Community | Aktivt konto, App Check, högst 2 000 UTF-16-enheter, valfri clientId och idempotent create | Ingen frekvensbegränsning i sändningsvägen |
| Konvoj | Samma textgräns och återförsöksskydd, kontroll av konvojmedlemskap | Ingen frekvensbegränsning |
| DM | Vänskap, blockering i båda riktningar, App Check, 2 000 UTF-16-enheter och transaktionell deduplicering | Ingen frekvensbegränsning |
| Eventchatt | 1 000 UTF-16-enheter; ungefär fem meddelanden per 30 sekunder över event | Räkning och skrivning sker separat, samtidiga anrop kan passera; saknar idempotensnyckel |
| Klubb, båda apparna | Delad callable, transaktionell medlemskontroll, operationskvitto och 30 sändningar per minut/UID | Fast gräns utan eskalering; annat längdmått: 2 000 kodpunkter och extra transportgräns |

Källor: [Community](../../functions/src/chatchannels/communityChat.ts), [konvoj](../../functions/src/chatchannels/convoyChat.ts), [DM](../../functions/src/dm/manageDirectMessages.ts), [event](../../functions/src/events/postChatMessage.ts), [textkontrakt](../../functions/src/chatchannels/chat-core.ts), [klubbadapter](../../apps/android/app/src/main/java/com/kungsbackacarcommunity/app/clubs/FirebaseClubRepository.kt), [delad klubbbackend](https://github.com/SebMcCayen/driver/blob/bc2597e3137904e0eec6dd6a91ce372d3da119fd/functions/src/shared-clubs.ts).

App Check ersätter inte en sändningsbudget. En godkänd installation kan fortfarande skicka många legitimerade anrop. Klientens inaktiverade skicka-knapp är bra återkoppling men servern måste fatta beslutet.

## Föreslaget beteende

Förslag till försiktiga startvärden, att godkänna och kalibrera:

| Situation | Åtgärd |
| --- | --- |
| Normal konversation | Tillåt en kort burst på sex normala meddelanden; fyll på en plats varannan sekund |
| Första överskridandet | Pausa sändning i fem sekunder |
| Nytt överskridande efter pausen | 30 sekunder |
| Ytterligare överskridande | Två minuter |
| Fortsatt överskridande | Tio minuter, föreslaget automatiskt maximum |
| 30 minuter utan överträdelse | Nollställ eskaleringsnivån; återställning räknas på serverns tid |

Budgeten följer kontot över Community, konvoj, DM, event och klubb; kanalbyte eller byte mellan apparna ger inte en ny budget. Behåll befintliga strängare event-/klubbgränser under införandet. En eventuell separat rumsbudget införs först efter kalibrering, med ett litet begränsat antal rumsnycklar.

Under en aktiv paus returneras samma återstående tid. Upprepade nätverksförsök ska inte automatiskt förlänga straffet. Nästa verkliga överträdelse efter pausen höjer nivån. Läsning, övriga appfunktioner och möjligheten att rapportera innehåll fortsätter fungera. Automatiken stänger inte konton och tar inte bort redan publicerade meddelanden.

## Emoji och stora meddelanden

En emoji eller ett meddelande med flera emojis är tillåtet. Bedöm framför allt sändningshastighet och upprepning, även för bokstäver och symboler.

- Identifiera till exempel minst 16 identiska grafem i följd, eller minst 40 grafem där 80 procent är samma tecken. Testa detta först utan att verkställa extra påföljd.
- Efter kalibrering kan stark upprepning kosta tre budgetplatser i stället för en. Snabba identiska meddelanden i samma rum kan också få högre kostnad.
- Räkna användarupplevda tecken med korrekt grafemsegmentering. Familjeemojis, flaggor, hudtoner och kombinerade svenska tecken får inte räknas som ett godtyckligt antal separata emojis.
- Normalisera endast en kopia för detektering; ändra inte användarens text. Behåll nuvarande transportgränser i första etappen och dokumentera skillnaden mellan UTF-16 och kodpunkter.
- Ett separat, godkänt UI-skydd kan fälla ihop mycket långa meddelanden bakom ”Visa hela”, så att ett enda meddelande inte fyller hela chattvyn. Det är inte ett sändningsskydd i sig.

## Server, klient och kostnadsgränser

Inför en ren ChatAbusePolicy och en transaktionell ChatSendGuard. Båda backendkodbaserna ska använda samma versionssatta policy och samma privata, serverägda UID-tillstånd. Klubbchatten skrivs i Driver-backenden och måste därför omfattas där också.

I transaktionen: kontrollera befintlig behörighet, återlämna ett redan accepterat operationskvitto, pröva budgeten och skriv sedan meddelande och budget atomiskt. Bevara auktorisationskontroller vid övergången; cache får aldrig ge sändningsrätt. Samtidiga anrop och flera Cloud Functions-instanser ska dela samma beslut.

Om en ny överträdelse ger paus ska den nya spärren först sparas i transaktionen. Kasta callable-felet efter att transaktionen har committats; ett kast inne i transaktionen skulle annars rulla tillbaka pausen. Notifieringar och andra följdverkningar körs bara en gång för accepterade meddelanden.

Planera för ungefär en extra tillståndsläsning och tillståndsskrivning per ny accepterad sändning, utöver befintliga läsningar och transaktionsåterförsök. Ett redan aktivt oförändrat stopp kräver ingen ny tillståndsskrivning. Avvisade anrop kostar fortfarande anrops-/läskapacitet; lösningen eliminerar inte all missbruksdebitering.

Håll tillståndet litet, exempelvis högst 8 KiB/UID, med begränsade senaste fingeravtryck. Spara inga råa meddelanden i spärrdokument eller telemetri. Föreslagen fysisk TTL är sju dagars inaktivitet; logisk återställning bygger alltid på tidsstämplar och väntar inte på Firestores TTL-radering. Ändra inte befintliga meddelande-/idempotenskvittensers livslängd som en sidoeffekt.

Klienterna behöver ett gemensamt felkontrakt med kod och retryAfterMs. Visa exempelvis ”Du skickar väldigt snabbt. Försök igen om 30 sekunder”, behåll utkastet och visa nedräkning vid skicka-knappen. Ingen automatisk omsändning när timern går ut. Äldre klienter ska få ett vanligt säkert fel; deras befintliga begäranden fortsätter vara kompatibla. Lägg till valfri clientId i eventchatten och återanvänd samma ID vid återförsök.

## Etapper efter godkännande

1. Felkontrakt, event-idempotens och tydlig nedräkning i Android och delad klubbklient.
2. Transaktionell grundbudget och eskalering, först med observation utan innehållsloggning och sedan bakom en serverstyrd aktiveringsflagga.
3. Kalibrera upprepningskostnad och eventuell rumsbudget; koppla administrativa översikter till aggregerade överträdelser och befintlig moderering.

Behåll rapportering, manuella moderatorbeslut och granskningsloggar. Ingen abonnemangsnivå köper undantag från skyddet. Publicering och aktivering av backendändringar är en separat, planerad produktionsåtgärd efter tester.

Verifiera normal konversation, Unicode, samtidig sändning med nya ID:n, Android/Driver samtidigt, kanalhopp, återförsök efter tappad kvittens, serverklockans gränser, borttaget medlemskap, blockering, utloggning och gamla klienter. Testa att nekade sändningar inte skapar meddelanden, notifieringar eller dubbletter, och att ett aktivt stopp inte orsakar en skrivning per upprepat anrop.

**Beslut som efterfrågas:** Godkänna grundbudget och paustrappa ovan; innehållsbaserad upprepningskostnad börjar som observation och får aktiveras efter kalibrering.

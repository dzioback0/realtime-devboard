# Realtime DevBoard

Kursinio darbo I dalis: projektavimo dokumentas

## 1. Problema ir idėja

**Sistema vienu sakiniu:**
„Realtime DevBoard“ – realiojo laiko bendradarbiavimo virtuali lenta programuotojams, leidžianti kartu kurti diagramas, tekstinius elementus ir vykdomus programinio kodo blokus.

**Problema ir dabartinis procesas:**  
Programinės įrangos kūrimo komandos bendradarbiavimo lentas naudoja diagramoms, architektūrai ir pastaboms, tačiau norint parodyti programinio kodo veikimą dažniausiai reikia papildomai naudoti IDE ar terminalą. „Realtime DevBoard“ leis kodo fragmentą pateikti kaip atskirą lentos elementą, jį vykdyti ir rezultatą matyti Execution Console elemente.

**Nauda:**  
Schemos, paaiškinimai, kodo fragmentai ir jų vykdymo rezultatai bus pateikiami vienoje bendroje aplinkoje, todėl sumažės poreikis persijungti tarp kelių programų.

**Naudotojai:**  
Programuotojai ir programinės įrangos kūrimo komandos. Jie galės kartu redaguoti lentą, matyti pakeitimus realiu laiku, rašyti ir vykdyti kodo fragmentus.

**Prielaidos:**  
Sistema skirta nedidelių kodo fragmentų demonstravimui, o ne pilnaverčiam programinės įrangos kūrimui. Pirmojoje versijoje bus palaikomas Java kodas ir nebus suteikiama tiesioginė prieiga prie serverio operacinės sistemos.

## 2. Apimtis

| Funkcija | Naudotojo veiksmas | Tipas |
|---|---|---|
| Lentos redagavimas | Kurti, keisti ir perkelti elementus | Pagalbinė |
| Realiojo laiko bendradarbiavimas | Matyti kitų naudotojų pakeitimus | Pagalbinė |
| Code Block | Rašyti kodą su sintaksės paryškinimu | Pagalbinė |
| Kodo vykdymas | Vykdyti Code Block kodą izoliuotoje aplinkoje | **Pagrindinis modulis** |
| Execution Console | Matyti programos rezultatą ir klaidas | Pagrindinio modulio dalis |

**Į kursinio darbo apimtį neįeina:**  
pilnavertis Linux terminalas ir IDE, Git integracija, išorinių paketų diegimas, kiekvieno teksto simbolio sinchronizavimas ir pilnas Miro funkcionalumas.

## 3. Pagrindinis modulis

**Pavadinimas ir atsakomybė:**  
`Code Execution Engine` – saugaus naudotojo programinio kodo vykdymo modulis.

**Logika, kurią reikės projektuoti ir testuoti:**  
Modulis parinks programavimo kalbai tinkamą `CodeExecutor`, vykdys kodą izoliuotoje aplinkoje, kontroliuos vykdymo laiką ir resursus bei grąžins vykdymo būseną ir programos išvestį.

**Įvestis:**  
Pasirinkta programavimo kalba ir vykdomas programinio kodo fragmentas. Pavyzdžiui:

```java
public class Main {
    public static void main(String[] args) {
        System.out.println("Hello");
    }
}
```

**Išvestis:**  
Vykdymo rezultatas: programos išvestis arba klaidos pranešimas bei vykdymo būsena. Pavyzdžiui:

```text
Hello
```

Būsena: `SUCCESS`.

**Veikimo eiga:**  
Gaunamas kodas → patikrinama kalba → parenkamas `CodeExecutor` → kodas vykdomas izoliuotoje aplinkoje → kontroliuojami resursai → grąžinamas rezultatas.

### Taisyklės arba sprendimo žingsniai

1. Nepalaikomos kalbos kodas nevykdomas ir grąžinama `UNSUPPORTED_LANGUAGE`.
2. Naudotojo kodas vykdomas atskirai nuo pagrindinės aplikacijos proceso.
3. Viršijus vykdymo laiko limitą procesas nutraukiamas ir grąžinama `TIMEOUT`.
4. Nepavykus sukompiliuoti kodo grąžinama `COMPILATION_ERROR` ir klaidos pranešimas.

### Scenarijai būsimiems testams

| Scenarijus | Įvestis | Laukiamas rezultatas |
|---|---|---|
| Įprastas | Java kodas išveda `"Hello"` | `SUCCESS`, išvestis `"Hello"` |
| Ribinis | Begalinis ciklas, limitas  7s | `TIMEOUT` |
| Klaida | Java sintaksės klaida | `COMPILATION_ERROR` ir klaidos tekstas |

## 4. Kokybės atributas

**Pasirinktas atributas:**  
Saugumas (Security).

**Kodėl svarbus šiai sistemai:**  
Sistema vykdys naudotojo pateiktą kodą, todėl jis neturi galėti neribotai naudoti resursų ar pasiekti pagrindinės sistemos duomenų.

**Tikrinimo scenarijus ir sąlygos:**  
Paleidžiama Java programa su begaliniu ciklu ir 7 s vykdymo limitu.

**Sėkmės kriterijus:**  
Po 7 s vykdymas nutraukiamas, grąžinama `TIMEOUT`, o pagrindinė sistema lieka veikianti.

**Numatytas projektavimo sprendimas:**  
Kodas bus vykdomas izoliuotoje aplinkoje su laiko, CPU ir RAM apribojimais bei ribota prieiga prie sistemos resursų.

**Kaip patikrinsiu vėlesniame etape:**  
Integraciniais testais su begaliniu ciklu, per dideliu atminties naudojimu ir bandymu pasiekti neleistinus resursus.

**Sprendimo kaina arba ribojimas:**  
Izoliuotas vykdymas padidina sistemos sudėtingumą ir resursų sąnaudas.

## 5. Pradinė sistemos struktūra

```text
 Web Client
     │
REST / WebSocket
     │
Spring Boot
 ├── Collaboration Service
 ├── Execution Service → CodeExecutor → Isolated Runtime
 └── PostgreSQL
```

| Sistemos dalis | Atsakomybė |
|---|---|
| Web klientas | Lentos ir jos elementų atvaizdavimas |
| Spring Boot | API ir sistemos logika |
| Collaboration Service | Realaus laiko pakeitimų sinchronizavimas |
| Execution Service / CodeExecutor | Kodo vykdymo valdymas |
| PostgreSQL | Sistemos duomenų saugojimas |
| Isolated Runtime | Saugus naudotojo kodo vykdymas |

**Planuojamos technologijos ir pasirinkimo priežastys:**  
**Java + Spring Boot** – backend ir WebSocket komunikacijai; **PostgreSQL** – duomenų saugojimui; **JUnit ir Mockito** – testams; **Docker ir Docker Compose** – izoliacijai ir sistemos paleidimui; **TypeScript pagrįstas web klientas** – vartotojo sąsajai.

Sistema bus projektuojama pagal cloud-first principus: konfigūracija perduodama per aplinkos kintamuosius, API neturėtų priklausyti nuo konkrečios serverio sesijos būsenos, o sistemos komponentai bus paleidžiami konteineriuose.

## 6. AI panaudojimas

### AI rengiant šį dokumentą

| Priemonė ir užduotis | Kam panaudojau | Ką atmečiau arba perrašiau ir kodėl | Kaip patikrinau |
|---|---|---|---|
| ChatGPT – techninių sprendimų aptarimas | Naudojau kaip papildomą priemonę idėjoms ir galimiems realizavimo variantams apsvarstyti | Galutinę sistemos idėją, funkcijas ir projekto ribas pasirinkau pats. AI pasiūlytus variantus keičiau pagal savo sumanymą, per sudėtingų arba nereikalingų funkcijų atsisakiau | Patikrinau, ar pasirinkti sprendimai atitinka užduoties reikalavimus, yra suprantami ir realiai įgyvendinami kursinio darbo metu |
### Planuojamas AI naudojimas kuriant sistemą

**Kur ir kam naudosiu AI:**  
Kodo paaiškinimui, klaidų analizei, architektūros ir testų idėjoms.

**Kaip tikrinsiu pasiūlymus ir sugeneruotą kodą:**  
Kodą peržiūrėsiu, pritaikysiu projektui ir tikrinsiu automatizuotais testais.

**Ar AI bus sistemos funkcionalumo dalis:**  
Ne.

## 7. Tolesnių darbų planas

| Darbas | Rezultatas |
|---|---|
| Sukurti backend ir domeno struktūrą | Spring Boot projektas ir pagrindiniai lentos modeliai |
| Sukurti Code Execution Engine | Java kodas vykdomas ir grąžinamas rezultatas |
| Įgyvendinti izoliuotą vykdymą | Veikia laiko ir resursų ribojimai |
| Įgyvendinti real-time sinchronizavimą | Keli naudotojai mato tos pačios lentos pakeitimus |

**Būsimo prototipo veikimo scenarijus:**  
Du naudotojai atidaro tą pačią lentą. Vienas sukuria Java Code Block, išvedantį `"Hello"`, o kitas realiu laiku pamato elementą. Paspaudus `Run`, kodas vykdomas izoliuotoje aplinkoje ir abiem naudotojams Execution Console parodoma `"Hello"`.

| Rizika arba neaiškumas | Kaip sumažinsiu |
|---|---|
| Saugaus kodo vykdymo sudėtingumas | Pirmiausia bus kuriamas minimalus Java prototipas su laiko limitu |
| Real-time konfliktų sudėtingumas | Sinchronizavimas vyks elementų, o ne simbolių lygiu |
| Per didelė whiteboard apimtis | Bus realizuojami tik pagrindiniai lentos elementai |

## Šaltiniai, jei naudojote

Išoriniai informacijos šaltiniai rengiant projekto aprašymą nenaudoti.

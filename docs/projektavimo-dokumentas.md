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
4. Nepavykus sukompiliuoti kodo grąžinama `COMPILATION_ERROR` ir kompiliatoriaus diagnostika.
5. Naudotojo kodas ir jo kompiliavimas vykdomi atskirame trumpalaikiame Docker konteineryje, ne aplikacijos JVM. Viršijus 7 s vykdymo arba 15 s kompiliavimo limitą, pašalinamas visas konteineris (`docker rm -f`) su visais jo procesais.
6. `MEMORY_LIMIT_EXCEEDED` grąžinama tik turint įrodymą: branduolio cgroup `memory.events` rodo `oom_kill > 0` (konteinerio 256 MiB riba) arba programa baigėsi neperimtu `java.lang.OutOfMemoryError` (heap riba `-Xmx128m`). Vien exit kodas 137 reiškia `RUNTIME_ERROR`.
7. Viršijus 64 KiB bendrą stdout + stderr išvestį, konteineris pašalinamas ir grąžinama `OUTPUT_LIMIT_EXCEEDED`.
8. Docker infrastruktūros klaida grąžinama kaip `EXECUTION_ERROR` ir niekada nemaskuojama kaip sėkmė.

### Izoliuoto vykdymo ribos (įgyvendinta, `DockerJavaExecutor`)

| Sritis | Riba | Laukiama būsena / elgsena |
|---|---|---|
| Vykdymo laikas | 7 s nuo `java` paleidimo | `TIMEOUT`, pašalintas visas konteineris |
| Kompiliavimas | 15 s | `TIMEOUT` („Compilation exceeded 15 s“) |
| RAM | 256 MiB konteineriui, `--memory-swap` lygus ribai (swap nėra) | `MEMORY_LIMIT_EXCEEDED` (kernel OOM kill) |
| Java heap | `-Xmx128m` | `MEMORY_LIMIT_EXCEEDED` („Java heap…“) |
| CPU | `--cpus 0.5` | apkrova ribojama; ilgas darbas baigiasi `TIMEOUT` (atskiros CPU būsenos nėra) |
| Failai | read-only šaknis; rašoma tik `/work` ir `/tmp` (tmpfs 16 MiB, `noexec`); jokių host prijungimų | neleistinas rašymas nepavyksta |
| Tinklas | `--network none` | prisijungimas nepavyksta |
| Išvestis | 64 KiB stdout + stderr | `OUTPUT_LIMIT_EXCEEDED` |
| Procesai / gijos | `--pids-limit 128` | papildomos gijos / procesai nesukuriami |
| Teisės | naudotojas 65534, `--cap-drop ALL`, `no-new-privileges`, ne privileged | — |
| Apkrova | 2 vykdymai vienu metu, kodas ≤ 64 KiB | HTTP 429 / 413 |

Pradinės ribos iš užduoties nepakeistos. Papildomai pasirinkta `-Xmx128m`, kad JVM heap riba būtų žemiau konteinerio ribos ir įprastas atminties kaupimas baigtųsi aiškiu `OutOfMemoryError`. `--pids-limit 128` parinktas, nes Docker gijas skaičiuoja kaip procesus, o JVM ir `javac` naudoja kelias dešimtis gijų.

### Scenarijai būsimiems testams

| Scenarijus | Įvestis | Laukiamas rezultatas |
|---|---|---|
| Įprastas | Java kodas išveda `"Hello"` | `SUCCESS`, išvestis `"Hello"` |
| Ribinis | Begalinis ciklas, limitas  7s | `TIMEOUT` |
| Klaida | Java sintaksės klaida | `COMPILATION_ERROR` ir klaidos tekstas |
| Vykdymo išimtis | `1 / args.length` | `RUNTIME_ERROR`, `ArithmeticException` |
| Heap kaupimas | `while (true) list.add(new byte[1_000_000])` | `MEMORY_LIMIT_EXCEEDED` (Java heap) |
| Konteinerio RAM | vaikinis `java -Xmx1g` pildo atmintį | `MEMORY_LIMIT_EXCEEDED` (kernel OOM kill), vaikas baigiasi kodu 137 |
| CPU | 4 gijos sukasi 3 s | `cpu.max 50000 100000`, CPU laikas / sieninis laikas < 0,75 |
| Failai | rašymas į `/work`, `/etc`, `/opt/java`, `/`, 20 MiB failas; host kontrolinis failas; `/proc/self/mountinfo` | pavyksta tik rašymas į `/work`; host failas nematomas; nėra host prijungimų |
| Tinklas | jungtis prie testo paleisto veikiančio serverio | iš konteinerio nepavyksta; kontrolinis prisijungimas iš testo pavyksta |
| Vaikinis procesas | `sleep 1000` + laukimas | `TIMEOUT`; po testo nelieka nė vieno užduoties konteinerio |
| Neribota išvestis | begalinis `println` | `OUTPUT_LIMIT_EXCEEDED`, išvestis ≤ 64 KiB |
| Sistema po ribų | `/api/health` vykdymo metu, naujas Hello po `TIMEOUT` | atsako < 2 s, `SUCCESS` |
| Darbo erdvės | `alice` bando skaityti / keisti / vykdyti Beta lentą per REST ir WebSocket | 404 / atmestas handshake |

Testų būsena (2026-11-15, Docker Desktop 29.9.3 / WSL 2, cgroup v2): visi scenarijai paleisti ir praėjo. `./mvnw test` praėjo 21/21; `./mvnw verify` praėjo papildomai `DockerSandboxIT` 13/13, `ExecutionApiIT` 1/1 ir `BrowserE2EIT` 1/1. Prieš įdiegiant Docker `BrowserE2EIT` nepraėjo, nes Run sąžiningai parodė `EXECUTION_ERROR: Docker is not available`.

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
Integraciniais testais su tikru Docker (`DockerSandboxIT`, `ExecutionApiIT`, `./mvnw verify`): begalinis ciklas, heap ir konteinerio atminties perpildymas, CPU kvota, failų sistema, tinklas, vaikiniai procesai, gijų riba, neribota išvestis ir API atsakas vykdymo metu. Mock testai naudojami tik sprendimų logikai, nes jie neįrodo resursų ribojimo.

**Sprendimo kaina arba ribojimas:**  
Izoliuotas vykdymas padidina sistemos sudėtingumą ir resursų sąnaudas: konteinerio paleidimas ir kompiliavimas su 0,5 CPU užtrunka kelias sekundes. Aplikacijai reikia Docker socket prieigos, kuri prilygsta root teisėms Docker serveryje. Tai pasitikėjimo riba: socket'ą turi tik aplikacija, vykdymo konteineriai jo neturi. Docker nėra absoliuti apsauga nuo branduolio ar runtime spragų.

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

**Įgyvendinta struktūra (2026-11-15):**

```text
 Web Client (TypeScript, be framework)
     │
REST (keitimai, Run) / WebSocket (tik pranešimai serveris → klientas)
     │
Spring Boot (Spring Security sesijos, darbo erdvių patikra)
 ├── BoardController + BoardSocketHandler (Collaboration)
 ├── ExecutionService → DockerJavaExecutor → docker CLI → atskiras konteineris kiekvienam Run
 └── PostgreSQL (JPA)
```

- **Multitenancy:** Workspace yra tenant. Naudotojas turi narystę darbo erdvėje, lentos priklauso darbo erdvei, elementai ir paskutinis vykdymo rezultatas priklauso lentai. Lenta visada ieškoma kartu su naryste, o svetima lenta grąžina 404.
- **Bendradarbiavimas:** sinchronizuojami elementai, ne simboliai. Politika: paskutinis įrašas laimi visam elementui. WebSocket prenumerata fiksuojama per handshake po narystės patikros.
- **Atskiras `CodeExecutor` interfeisas nekurtas,** nes palaikoma tik Java. `ExecutionService` patikrina kalbą ir grąžina `UNSUPPORTED_LANGUAGE`.

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

### AI refleksija: prototipo kūrimas (Claude Code, 2026-11-15)

Prototipo kodą, testus ir šio dokumento atnaujinimą sugeneravo Claude Code pagal mano parengtą užduoties aprašą. Žemiau pateikti konkretūs šios sesijos pasiūlymai.

| Pasiūlymas | Sprendimas | Priežastis |
|---|---|---|
| Kiekvienam Run atskiras konteineris, kurio PID 1 yra `sleep`, o `javac` / `java` paleidžiami per `docker exec` | panaudota | atskiri kompiliavimo ir vykdymo limitai viename konteineryje; `docker rm -f` nužudo visus procesus |
| `--rm` ir baigtinis `sleep` kaip atsarginis valymas | panaudota | konteineris dingsta net nulūžus aplikacijai |
| OOM nustatyti pagal cgroup `memory.events` | panaudota | exit 137 neįrodo atminties viršijimo (tai gali būti bet koks SIGKILL) |
| Du atminties keliai: `-Xmx128m` (heap) ir 256 MiB konteinerio riba (vaikinis JVM) | panaudota, pakeista | pradinė lentelė turėjo tik konteinerio ribą; heap riba duoda aiškesnę klaidą, o testas su `java -Xmx1g` įrodo konteinerio ribą |
| Docker valdyti per CLI (`ProcessBuilder`), ne per docker-java biblioteką | panaudota | mažiau priklausomybių, komandas lengva parodyti ir patikrinti |
| Atskira `CPU_LIMIT_EXCEEDED` būsena | atmesta | kvota tik lėtina, patikimo nustatymo mechanizmo nėra |
| WebSocket kaip tik serverio pranešimų kanalas (be STOMP / SockJS) | panaudota | visa autorizacija ir validacija lieka REST, mažiau tikrintinų WS žinučių |
| React / Vite klientas | atmesta | prototipui pakanka TypeScript su `tsc` |
| Testcontainers PostgreSQL testams | atmesta | API testams pakanka H2, ir jie veikia be Docker; produkcijoje naudojama PostgreSQL |
| Mock testas izoliacijai įrodyti | atmesta | mock neįrodo resursų ribojimo, todėl naudojami tikri Docker integraciniai testai |
| Atskiras vykdymo mikroservisas | atmesta | nereikalingas sudėtingumas; pasitikėjimo riba dokumentuota |

Tikrinimas: iš pradžių Docker nebuvo įdiegtas, todėl izoliacijos testai buvo tik parašyti. Įdiegus Docker Desktop, `./mvnw verify` praėjo visi 36 testai (21 vienetinis / API testas ir 15 integracinių / E2E testų). `docker compose up --build` paleistas ir patikrintas rankiniu būdu: Hello grąžino `SUCCESS`, o duomenys išliko po perkrovimo.

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

Išoriniai informacijos šaltiniai rengiant projekto aprašymą nenaudoti. Kuriant prototipą remtasi bendromis žiniomis apie Docker `docker run` parinktis, Linux cgroup v2 (`memory.events`, `cpu.max`) ir Spring Security; konkretūs dokumentacijos puslapiai šio darbo metu neatidaryti.

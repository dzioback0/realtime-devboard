# realtime-devboard

Realiojo laiko bendradarbiavimo lenta programinės įrangos kūrimo komandoms su vykdomais kodo blokais ir izoliuota vykdymo konsole.

Lentoje kuriami tekstai, figūros (stačiakampis, apskritimas su užrašu), rodyklės ir Java kodo blokai; visų dydis keičiamas už kampo. Kodo bloką galima paleisti: kodas
kompiliuojamas ir vykdomas **atskirame, apribotame Docker konteineryje**, o rezultatas rodomas
bloko Execution Console visiems tos lentos naudotojams.

## Būtinos programos

| Programa | Kam | Patikrinta versija |
|---|---|---|
| Git | projekto parsisiuntimas ir atnaujinimas | — |
| Docker Desktop (Windows: WSL 2) arba Docker Engine + Compose v2 | paleidimas, izoliuotas vykdymas, PostgreSQL | Docker Desktop (per-user, WSL 2), Engine 29.9.3, Compose 5.7.0, cgroup v2 |
| JDK 21+ | tik kūrimui / testams be Docker Compose | JDK 26.0.1 (kodas kompiliuojamas `release 21`) |
| Node.js + npm | tik kliento kūrimui ir naršyklės testui | Node 24.21.0, npm 11.19.0 |
| Google Chrome | tik naršyklės E2E testui | įdiegta versija (Selenium Manager parenka tvarkyklę) |

Java versija suvienodinta: Maven kompiliuoja Java 21 (`pom.xml`), Docker atvaizdai yra
`eclipse-temurin:21`. Spring Boot 4.1.2.

Paleisti sistemą užtenka **Git ir Docker**. JDK, Node.js ir Chrome reikia tik kūrimui ir testams.

## 1. Projekto parsisiuntimas

Projektas paleidžiamas iš savo kompiuteryje turimos repozitorijos kopijos, todėl pirmiausia ją reikia nukopijuoti su Git:

```bash
git clone https://github.com/dzioback0/realtime-devboard.git
cd realtime-devboard
```

Vėliau naujausius pakeitimus parsisiųskite komanda `git pull` ir paleiskite sistemą iš naujo su `docker compose up --build`.

## 2. Paleidimas (Docker Compose)

Komandas vykdykite projekto aplanke (`realtime-devboard`):

```bash
cp .env.example .env        # Windows PowerShell: copy .env.example .env
                            # pakeiskite slaptažodžius .env faile (.env necommitinamas)
docker compose up --build
```

Programa: <http://localhost:8080>. Pirmas paleidimas parsiunčia `eclipse-temurin:21-jdk`
(vykdymo smėlio dėžės atvaizdą), `postgres:17-alpine` ir sukompiliuoja aplikaciją.

**Keli kompiuteriai.** Kiekvienas `docker compose up` sukuria **atskirą** duomenų bazę, todėl lentos tarp
kompiuterių nesisinchronizuoja. Kad keli žmonės dirbtų toje pačioje lentoje, sistemą paleiskite viename
kompiuteryje, o kiti tegul atsidaro `http://<to-kompiuterio-IP>:8080`. Kompiuteriai turi būti tame pačiame
tinkle. Jei tinklas izoliuoja įrenginius (dažna bendrabučių, universitetų ir svečių Wi-Fi tinkluose), padeda,
pavyzdžiui, Tailscale.

### Demonstracinės paskyros

Sukuriamos tik tuščioje duomenų bazėje, slaptažodis yra `DEMO_PASSWORD` iš `.env`.

| Naudotojas | Darbo erdvė | Lenta |
|---|---|---|
| `alice` | Team Alpha | Alpha board (su Hello Java bloku) |
| `adam` | Team Alpha | Alpha board |
| `bob` | Team Beta | Beta secret board |

**Dviejų darbo erdvių scenarijus:** prisijunkite kaip `alice` vienoje naršyklėje ir kaip `adam`
kitoje (arba inkognito lange). Alice paspaudžia **Run**, ir abu mato `SUCCESS` bei `Hello`. Alice
prideda elementą, o Adam jį mato iškart. Tada prisijunkite kaip `bob`: matoma tik Team Beta,
o bandymas atidaryti Alpha lentą (`/#board=<alpha id>` arba API) grąžina 404.

### Stabdymas ir duomenų išvalymas (atskiri veiksmai)

```bash
docker compose down         # sustabdo; duomenys lieka db-data tome
docker compose down -v      # SUSTABDO IR IŠTRINA duomenų bazę (demo duomenys bus sukurti iš naujo)
```

Įprastas `up` / `down` duomenų netrina (`spring.jpa.hibernate.ddl-auto=update` tik sukuria trūkstamas lenteles).

### Paleidimas iš IDE (kūrimui)

```bash
docker compose up -d db sandbox-image
npm --prefix frontend ci && npm --prefix frontend run build    # sugeneruoja src/main/resources/static/app.js
# aplinkos kintamieji: DATABASE_PASSWORD=<POSTGRES_PASSWORD>, DEMO_PASSWORD=<...>
./mvnw spring-boot:run
```

## Testai

| Komanda | Kas paleidžiama | Prielaidos |
|---|---|---|
| `./mvnw test` | vienetiniai testai (būsenų sprendimai, ribos, `docker run` parametrai) ir API testai su tikru HTTP serveriu, H2 DB: prisijungimas, CSRF, REST ir WebSocket darbo erdvių atskyrimas, realaus laiko sinchronizavimas | tik JDK |
| `./mvnw verify` | papildomai `*IT`: `DockerSandboxIT` (tikri konteineriai, scenarijai 1–11), `ExecutionApiIT` (API atsako vykdymo metu), `BrowserE2EIT` (Chrome: Run per UI, dvi sesijos) | veikiantis Docker, Chrome, sukurtas klientas (`npm --prefix frontend run build`) |

Be Docker integraciniai testai **nepraeina** (jie nepraleidžiami tyliai).

### Tikri rezultatai (2026-11-15, Windows 11, JDK 26.0.1, Docker Desktop 29.9.3 / WSL 2)

| Testai | Parašyta | Paleista | Praėjo |
|---|---|---|---|
| `./mvnw test`: 21 testas (DockerJavaExecutorTest 10, ExecutionServiceTest 3, BoardApiTest 7, kontekstas 1) | taip | taip | **taip, 21/21** |
| `DockerSandboxIT`: 13 testų su tikrais konteineriais (81 s) | taip | taip | **taip, 13/13** |
| `ExecutionApiIT`: API atsako vykdymo metu, po `TIMEOUT` vykdomas Hello | taip | taip | **taip, 1/1** |
| `BrowserE2EIT`: Chrome, Run per UI, dvi sesijos | taip | taip | **taip, 1/1** |

Rankiniu būdu patikrinta per `docker compose up --build`:
- `/api/health` atsako `UP`;
- prisijungus kaip `alice`, Run ant Hello bloko grąžino `SUCCESS` ir `Hello` per 2,9 s;
- svetima lenta grąžino 404;
- po vykdymo neliko nė vieno užduoties konteinerio;
- po `docker compose down` ir `up` rezultatas išliko PostgreSQL.

Pastaba: ribos patikrintos Docker Desktop (WSL 2) aplinkoje; kitoje Linux / Docker konfigūracijoje testus reikėtų paleisti iš naujo.

## Code Execution Engine: saugumo ribos

Kiekvienas Run sukuria atskirą trumpalaikį konteinerį (`DockerJavaExecutor`):

```
docker run -d --rm --network none --memory 256m --memory-swap 256m --cpus 0.5 --pids-limit 128
  --read-only --tmpfs /work:size=16m,noexec --tmpfs /tmp:size=16m,noexec --user 65534:65534
  --cap-drop ALL --security-opt no-new-privileges eclipse-temurin:21-jdk sleep 52
docker exec -i <c> sh -c 'cat > /work/Main.java'        # kodas per stdin, jokių host failų
docker exec <c> javac ... /work/Main.java                  # 15 s limitas
docker exec <c> java -Xmx128m ... -cp /work Main            # 7 s limitas
docker rm -f <c>                                           # visada (finally), nužudo VISUS konteinerio procesus
```

| Sritis | Riba | Būsena viršijus |
|---|---|---|
| Vykdymo laikas | 7 s nuo `java` paleidimo | `TIMEOUT`, konteineris su visais procesais pašalinamas |
| Kompiliavimas | 15 s | `TIMEOUT` su pranešimu „Compilation exceeded 15 s“ |
| RAM | 256 MiB visam konteineriui, swap lygus ribai (t. y. jo nėra) | `MEMORY_LIMIT_EXCEEDED`, jei branduolio cgroup `memory.events` rodo `oom_kill > 0` |
| Java heap | `-Xmx128m` | `MEMORY_LIMIT_EXCEEDED` su pranešimu „Java heap…“ (exit 1 ir neperimtas `OutOfMemoryError`) |
| CPU | 0,5 CPU kvota (`cpu.max 50000 100000`) | tik lėtina; ilgas darbas baigiasi `TIMEOUT` (atskiros CPU būsenos nėra) |
| Failai | šaknis read-only; rašoma tik `/work` ir `/tmp` (tmpfs po 16 MiB, `noexec`); host katalogai neprijungti | rašymo klaida programoje |
| Tinklas | `--network none` (tik `lo`) | prisijungimas nepavyksta |
| Išvestis | 64 KiB stdout + stderr bendrai | `OUTPUT_LIMIT_EXCEEDED`, konteineris pašalinamas |
| Procesai / gijos | `--pids-limit 128` | nauja gija / procesas nesukuriamas |
| Naudotojas | `nobody` (65534), be capabilities, `no-new-privileges` | — |
| Lygiagretumas / dydis | 2 vienu metu, kodas ≤ 64 KiB | HTTP 429 / HTTP 413 |

Kitos būsenos: `SUCCESS`, `COMPILATION_ERROR`, `RUNTIME_ERROR` (įskaitant exit 137 be OOM
įrodymo), `UNSUPPORTED_LANGUAGE` (ne Java kalba, kodas nevykdomas), `EXECUTION_ERROR`
(Docker nepasiekiamas arba konteinerio nepavyko paleisti; niekada nerodoma kaip sėkmė).
Įvesties klaidos grąžinamos kaip 400, neprisijungus 401, CSRF klaida 403, svetima ar neegzistuojanti lenta 404.

**Pasitikėjimo riba.** Aplikacija (patikima) valdo Docker per `/var/run/docker.sock`. Ši prieiga
prilygsta root teisėms Docker serveryje, todėl ją turi tik aplikacijos konteineris. Vykdymo
konteineriai socket'o, tinklo, DB ir aplikacijos paslapčių nemato. Docker nėra absoliuti apsauga:
branduolio ar runtime spraga leistų pabėgti iš konteinerio. Stipresnei izoliacijai reikėtų gVisor,
Kata ar atskiros VM.

## Darbo erdvės, autentifikacija, bendradarbiavimas

- Spring Security, formos prisijungimas, serverio sesija, BCrypt maišos, CSRF (SPA režimas, `X-XSRF-TOKEN`). Viešos registracijos nėra.
- Workspace yra tenant. Kiekviena lentos užklausa ieško lentos per narystę (`findByIdAndWorkspace_Members_Username`), todėl kliento pateiktas `id` teisės nesuteikia.
- WebSocket `/ws/boards/{id}`: prieš prisijungimą patikrinama sesija ir narystė, priimami tik to paties origin prisijungimai. Kanalas skirtas tik pranešimams iš serverio, o bet kokia kliento žinutė uždaro ryšį (1008). Visi pakeitimai keliauja per autorizuotą REST.
- Konfliktų politika: **paskutinis įrašas laimi** visam elementui. Sinchronizuojami elementai, ne simboliai; kito naudotojo pakeitimas neperrašo teksto lauko, kurį vietinis naudotojas tuo metu redaguoja.

## Žinomi apribojimai

- Java heap OOM atpažįstamas iš stderr. Programa gali tai suklastoti, bet paveiktų tik savo pačios rezultatą.
- Atminties OOM aptikimui reikia cgroup v2 (Docker Desktop / šiuolaikinis Linux). Su cgroup v1 OOM kill būtų rodomas kaip `RUNTIME_ERROR` ir užrašomas į žurnalą.
- Atšaukus naršyklės užklausą, serveris vykdymą užbaigia iki ribos; konteineris vis tiek pašalinamas.
- Narystės pašalinimas jau atidaryto WebSocket neuždaro; rodyklės galas prisikabina prie elemento, paleidus jį ant elemento (tempiant visą rodyklę ji atsikabina); nėra sintaksės paryškinimo, stdin.
- Konteinerio paleidimas ir kompiliavimas su 0,5 CPU užtrunka kelias sekundes.

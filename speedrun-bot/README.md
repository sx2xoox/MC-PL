# speedrun-bot — Minecraft Java 1.16.1 RSG 스피드런 봇 (3단계 M1 진행 중, 4회차: 바다 루트)

무작위 시드 1.16.1에서 엔더드래곤까지 IGT 10분 이하를 목표로 하는 봇의 1단계입니다. 게임 상태를 코드로 읽고
조종하는 봇(화면 기반 신경망 아님)이고, 루트는 코드, 판단 수치(`params.json`)는 여러 시드 반복 실행으로 자동
튜닝합니다. 1단계: 기반 + 공정 규칙 + 오버월드에서 네더 진입까지 + 헤드리스 측정/튜닝 하네스. 2단계: 화면 세션
단일 소유(제작·화로·상자), 동굴 프런티어 탐색, 등반·물 확보, 포탈 주조 실전 검증과 수정, 생존, 바다 스폰용 보물
루트(난파선 → 보물 지도 → 매장 보물), 감시견. 3단계 M1(첫 네더 진입): 네더 입장 시 서버 충돌 수정, 멈춤 원인 정리(수직 굴, 물가), 용암 소리 듣기, 주조 실패 수정,
부싯돌은 틀 뒤로. 설계와 측정 결과는 [docs/design.md](docs/design.md) (2단계는 §9, M1은 §10·§11).

이 폴더는 MC-PL 저장소 안의 **독립 프로젝트**입니다(자체 Gradle 빌드, MC-PL 빌드에 포함되지 않음).

## 구성

```
src/main/java/io/github/sx2xoox/speedrunbot/
  core/     순수 로직 (MC 의존 없음, 단위 테스트): 캐기 시간, IGT, 입력 상한, 공정 인지 규칙, 파라미터,
            루트 선택기(DIG_CAST / SURFACE_CAST / TREASURE), 버킷 포탈 주조 계획, 동굴 프런티어(Frontier),
            보물 지도 읽기(TreasureMap)
  brain/    봇 두뇌: 두 인터페이스(BotInputs = 사람이 낼 수 있는 입력, BotSenses = 사람이 보는 것) 뒤에서만 동작.
            Perception(시야 레이캐스트 → WorldMemory), Navigator(A*, 기억 지도), Mover(경로 추종, 등반), Actions,
            화면 세션 Workbench(제작)·Smelter(화로)·Looter(상자), PortalBuilder, Survival(전투·용암·공기·식사),
            TreasureHunt(난파선·지도·보물), RunBrain(목표 순서, 감시견)
  server/   서버 구현: FakePlayer(ServerPlayerEntity 하위 클래스) + FakeConnection, ServerBot(두 인터페이스 구현)
  mixin/    서버 틱 훅 + 틱 워프(대기 없이 연속 틱), Slot 접근자
  BotRuntime.java  서버 시작 1회 = 런 1회, 결과 JSON 한 줄 기록
  DevSetup.java    개발 전용: 시작 키트·순간이동(한 단계만 시험), 시드 분류 probe (측정에는 안 씀)
tools/      setup_server.py, run_batch.py(병렬 헤드리스 측정), summarize.py, tune.py(자동 탐색),
            group_report.py(시드 그룹·루트별 요약), param_space.json(탐색 범위),
            defaults.json(코드 기본값, 테스트가 동기화 확인), seeds_*.txt
docs/design.md
```

## 빌드

Gradle 실행에 JDK 25가 필요합니다(fabric-loom 1.19.4의 요구; 모드는 Java 8 바이트코드로 컴파일, 서버 실행은
JDK 17). 처음 빌드에는 인터넷(Fabric/Mojang maven)이 필요합니다.

```bash
cd speedrun-bot
./gradlew build          # build/libs/speedrun-bot-0.1.0.jar + 단위 테스트
```

`~/.gradle`에 다른 프로젝트용 저장소 리다이렉트(init 스크립트)가 있으면 이 프로젝트만 따로 돌리세요:
`GRADLE_USER_HOME=/some/empty/dir ./gradlew build`.

## 서버 준비 (Mojang EULA)

측정 하네스는 Mojang의 1.16.1 서버 jar를 Fabric server launcher로 띄웁니다. 서버를 실행하려면 **Minecraft EULA
(https://aka.ms/MinecraftEULA)에 본인이 직접 동의**해야 합니다. 스크립트는 `--accept-eula`를 줬을 때만
`eula.txt`에 `eula=true`를 씁니다(사용자 대신 동의하지 않음). 이 저장소 개발 중 샌드박스에서 측정용 서버에
`eula=true`를 쓴 것은 측정 목적의 임시 서버였고, 여러분의 환경에서는 여러분이 직접 동의해야 합니다.

```bash
python3 tools/setup_server.py --accept-eula --java /path/to/java17   # work/template 생성 (서버 jar·라이브러리 다운로드)
```

## 실행

```bash
# 무작위 시드 40개, 서버 4개 병렬, 10분(12000틱) 제한
python3 tools/run_batch.py --seeds 40 --parallel 4 --java /path/to/java17 --out results/run.csv
# 고정 시드 집합 + 파라미터 파일, 판단 로그(trace) 저장
python3 tools/run_batch.py --seed-file tools/seeds_tune.txt --params my.json --out results/x.csv --trace-dir results/traces
python3 tools/summarize.py results/run.csv
# 자동 튜닝 (교차 엔트로피 방식): 세대 × 후보 × 시드 수 만큼 런
python3 tools/tune.py --seed-file tools/seeds_tune.txt --generations 2 --population 5 --out results/tune -- --java /path/to/java17
```

결과 CSV 열: 시드, 성공 여부, 실패 이유(`death_<원인>`, `timeout_<당시 목표>`, `craft_*`, `portal_*` …), 루트,
최종 IGT, 구간별 IGT(`split_*`, 초), MSPT 평균/최대, 실제 걸린 시간.

서버 하나를 손으로 띄울 때 쓰는 시스템 속성(`-Dsrb.*`): `params`, `out`, `trace`, `timeoutTicks`, `fair`
(false = 비교용 완화 인지), `warp`(대기 없는 연속 틱), `warpBudgetMs`, `renderChunks`, `rays`, `exit`. 자세한
내용은 `BotRuntime.java` 주석. 개발 전용(결과에 `dev` 표시, 측정에 쓰지 않음): `kit=item:개수,...`(시작 인벤토리),
`devStart=lavalow|lava|x,y,z`(한 단계만 시험), `probe=true`(하네스가 구조물 탐색기로 스폰~난파선 등 거리를 기록하고
종료: 시드 분류 전용, 봇은 모름), `debugUse`, `debugMove`, `debugGui`.

```bash
# 배치 여러 개를 동시에 돌릴 때는 work 디렉터리와 포트 범위를 나눈다 (메모리: 서버 1개 ≈ 2.3GB, 15GB에서 4개까지)
python3 tools/run_batch.py --work work2 --port-base 25700 --seed-file seeds.txt --parallel 1 --jvm-arg=-Dsrb.probe=true --out results/probe.csv
python3 tools/group_report.py results/eval.csv --probe results/probe.jsonl --ship-dist 96
```

## 2단계 끝 상태 (참고)

평가 시드 48개(IGT 10분 제한): 네더 진입 **0/48**(1단계도 0/48). 단계 도달은 크게 늘었다: 양동이 15→37개
(중앙값 4:43), 부싯돌과 부시 6→27개(5:45), 물 양동이 0→18개, 포탈 터 0→2개. 실패 1위는 용암 찾기(16).
바다 스폰용 보물 루트는 구현·단위 테스트까지이고 실제 월드에서 난파선을 본 런은 아직 없다. 자세한 표와 다음
계획은 docs/design.md §9.

## 현재 상태 (3단계 M1 1회차)

전체 런(평가 시드 48개, IGT 10분): 네더 **0/48**. 2단계보다 물 양동이 18→25, 포탈 터 2→7, 흑요석 10개 완성 0→2,
부싯돌 시간초과 6→1. 후반 단계만 따로 잰 런(스폰에서 물 양동이·부시·곡괭이를 들고 시작하는 개발용 측정)에서는
48개 중 6~8개가 네더에 들어간다(중앙값 3:43, 최고 2:29). 남은 병목: 용암 찾기, 물가에서 멈춤, 주조 시간.
자세한 표와 다음 계획은 docs/design.md §10.

후반 단계 측정:
```bash
python3 tools/run_batch.py --seed-file tools/seeds_eval.txt --timeout-ticks 7200 --out results/late.csv \
  --jvm-arg=-Dsrb.kit=water_bucket:1,flint_and_steel:1,stone_pickaxe:2,cobblestone:32,oak_planks:8,bread:8
```

## 현재 상태 (3단계 M1 2회차)

전체 런(평가 시드 48개, IGT 10분): 처음으로 네더 진입 **2/48**(7:43, 8:23; 같은 시드 재측정은 0/48 — 변동이 큼). 1회차 대비 돌 곡괭이 2:08 → 1:16,
첫 철 3:10 → 2:07, 양동이 4:38 → 3:05(33 → 36개), 물 양동이 5:19 → 3:46(25 → 31개), 포탈 터 7 → 13개. 후반 단계
런(개발 시드 24개, 키트로 시작, 6분): 네더 3/24(1회차 코드) → 6/24. 남은 병목: 용암 찾기 시간초과(13), 주조 시간과
실패, 철 찾기. 목표(50%, 중앙값 4:00)와는 아직 멀다. 자세한 것은 docs/design.md §11.

개발 시드는 `tools/seeds_dev.txt`(평가 시드와 겹치지 않음), 결과는 `results/*_m1r2*.csv`.

## 현재 상태 (3단계 M1 3회차)

전체 런(평가 48개를 두 번 돌려 합산, IGT 10분): 네더 **4/96**(2회차 합산 2/96 — 패스 사이 변동 범위 안). 후반 단계 런(키트,
개발 시드 23개): 7/23, 중앙값 3:57. 이번 회차: 멈춤 원인 수정(좁은 굴 기둥 쌓기, 자기 굴 탈출, 아래쪽 용암 소리, 자기 굴을
동굴로 착각, 화로·작업대 보호, 방을 위에서 파다 용암 낙하 등), **F3 파이 차트 감각**(시야 절두체 안 상자 수, 위치 없음)과
좁은 창 회전으로 매장 보물 찾기(바다 스폰), **RSG 리셋 정책**(본 것만으로 판단, 시드별 측정은 그대로, `tools/rsg_report.py`). 파이 차트 루트는 켜 보니 평가 시드에서 앞 단계를 늦춰(1/48) 기본값 끔.
M2~M4 계획(보루 물물교환, 블레이즈, 엔더 눈 각도, 침대+정박기 드래곤)은 docs/design.md §12.7. 자세한 것은 §12.

```bash
python3 tools/rsg_report.py results/eval_m1r3_e.jsonl      # 시도당 네더 비율, 네더 1회당 시도 수·IGT(리셋 포함)
python3 tools/run_batch.py --seed-file tools/seeds_treasure.txt ...   # 보물 근처 스폰(하네스 probe로 고름)
```

## 현재 상태 (3단계 M1 4회차: 바다 루트로 전략 전환)

일반 굴착 루트가 구조적으로 10분에 닿지 못해(네더 8~9분), 상위 RSG 런의 바다 루트를 짓고 RSG 방식으로 평가하기 시작했다:
바다 판단(`core/OceanSense`: 열린 해수면, 강·호수 제외), 파이 차트 두 높이 띠 회전 + 세로 모형 + 세 번째 회전, **보트**(바닐라
물리를 서버에서, 봇은 W/A/D·웅크리기만), 거품 기둥으로 **마그마 협곡** 찾기, 기둥으로 하강, 협곡 벽에 **문 주머니**(물이 못 들어오는
마른 칸), 그 뒤 일반 주조 목표. RSG 리셋 정책 기본값은 바다 루트용(`reset.oceanOnly`), 풀 측정은 `-Dsrb.rsgStop=true`.
뒤쪽 절반(보트 → 협곡 → 주머니)은 개발 고정 장면에서 끝까지 동작하지만 실제 시드에서 보물 단계가 끝나지 않아 바다 루트 네더 진입은
아직 0이다. 무작위 120 시드 풀: 바다 스폰 21%, 네더 0. 자세한 것과 다음 계획은 docs/design.md §13.

```bash
# RSG 풀 (리셋 판단에서 런 종료; 바다가 아닌 스폰은 IGT 4초)
python3 tools/run_batch.py --seeds 120 --rng 4410 --jvm-arg=-Dsrb.rsgStop=true --out results/rsg.csv ...
python3 tools/rsg_report.py results/rsg.jsonl
# 개발 고정 장면: 협곡 위 수면 / 협곡 바닥에서 바다 루트의 한 단계부터
--jvm-arg=-Dsrb.devStart=ravine --jvm-arg=-Dsrb.devOcean=LAUNCH --jvm-arg=-Dsrb.kit=oak_boat:1,oak_door:1,iron_pickaxe:1,bucket:1
--jvm-arg=-Dsrb.devStart=ravinefloor --jvm-arg=-Dsrb.devOcean=WALL --jvm-arg=-Dsrb.kit=oak_door:1,iron_pickaxe:1,water_bucket:1
```

## 공정 규칙 요약

- 봇의 세계 지식 = 자기 눈 위치에서 시야(FOV 70°, 16:9) 안으로 쏜 광선이 지나간/멈춘 블록 + 몸에 닿는 블록뿐
  (렌더 거리 상한, 로드 안 된 청크 불가). 청크 직접 스캔·구조물 조회·시드 읽기·월드 생성 미리 실행 없음.
- 입력은 사람이 낼 수 있는 것만: 시점 회전 45°/틱 상한, 사용 클릭 4틱 간격, 화면 클릭 2틱(100ms) 간격,
  핫바 1회/틱, 블록 도달 4.5칸. 채굴은 바닐라 클라이언트의 진행 누적 규칙(+5틱 쿨다운)을 그대로 따름.
- IGT = 봇이 월드에 들어온 틱부터의 서버 틱 수 / 20 (SpeedRunIGT의 일시정지 없는 IGT와 같음).
- 2단계 추가: 물을 16칸 넘게 지나간 시야 광선은 끝남(물 안개). 상자는 본 것만 연다. 보물 지도는 손에 들었을 때
  화면의 표식(자기 표식과 빨간 X)만 읽고, 자기 좌표(F3)로 청크 9,9 칸을 판다. 블록이 우클릭을 먹으면(레드스톤
  광석) 아이템은 쓰이지 않는 바닐라 규칙 그대로.
- 3단계: 용암 소리를 사람처럼 듣는다(바닐라 클라이언트와 같은 확률로 나는 소리, 16칸 안, 블록 위치가 아니라 방향
  ±15°·거리 ±30% 정도의 짐작만). `-Dsrb.hearing=false`로 끈다.
- 3단계 M1 3회차: F3 파이 차트의 블록 엔티티 조각 = 지금 시야 절두체 안·렌더 거리 안 상자 수(바닐라 클라이언트처럼
  가림 검사 없음, 위치는 주지 않음). FOV 옵션(30~110)과 창 비율을 바꿀 수 있고, 바꾸는 데 20틱.
- 4회차: 보트는 바닐라 클라이언트의 보트 물리 코드를 서버에서 돌리고 봇은 키만 누른다. 거품 기둥은 시야 광선이 지나간 칸만
  안다(물 안개 16칸 그대로). 협곡 바닥 y 10의 마그마/흑요석과 그 밑 용암은 공개 생성 지식.
- 자세한 근거와 테스트는 docs/design.md §2.

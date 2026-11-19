# speedrun-bot — Minecraft Java 1.16.1 RSG 스피드런 봇 (1단계)

무작위 시드 1.16.1에서 엔더드래곤까지 IGT 10분 이하를 목표로 하는 봇의 1단계입니다. 게임 상태를 코드로 읽고
조종하는 봇(화면 기반 신경망 아님)이고, 루트는 코드, 판단 수치(`params.json`)는 여러 시드 반복 실행으로 자동
튜닝합니다. 이번 단계 범위: 기반 + 공정 규칙 + 오버월드에서 네더 진입까지 + 헤드리스 측정/튜닝 하네스.
설계와 측정 결과는 [docs/design.md](docs/design.md).

이 폴더는 MC-PL 저장소 안의 **독립 프로젝트**입니다(자체 Gradle 빌드, MC-PL 빌드에 포함되지 않음).

## 구성

```
src/main/java/io/github/sx2xoox/speedrunbot/
  core/     순수 로직 (MC 의존 없음, 단위 테스트): 캐기 시간, IGT, 입력 상한, 공정 인지 규칙, 파라미터,
            루트 선택기, 버킷 포탈 주조 계획
  brain/    봇 두뇌: 두 인터페이스(BotInputs = 사람이 낼 수 있는 입력, BotSenses = 사람이 보는 것) 뒤에서만 동작.
            Perception(시야 레이캐스트 → WorldMemory), Navigator(A*, 기억 지도), Mover, Actions, Workbench(제작),
            PortalBuilder, RunBrain(목표 순서)
  server/   서버 구현: FakePlayer(ServerPlayerEntity 하위 클래스) + FakeConnection, ServerBot(두 인터페이스 구현)
  mixin/    서버 틱 훅 + 틱 워프(대기 없이 연속 틱), Slot 접근자
  BotRuntime.java  서버 시작 1회 = 런 1회, 결과 JSON 한 줄 기록
tools/      setup_server.py, run_batch.py(병렬 헤드리스 측정), summarize.py, tune.py(자동 탐색),
            param_space.json(탐색 범위), defaults.json(코드 기본값, 테스트가 동기화 확인), seeds_*.txt
docs/design.md
```

## 빌드

JDK 17 이상(Gradle 실행용; 모드는 Java 8 바이트코드로 컴파일)과 인터넷(Fabric/Mojang maven)이 필요합니다.

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
(false = 비교용 완화 인지), `warp`(대기 없는 연속 틱), `renderChunks`, `rays`, `exit`. 자세한 내용은
`BotRuntime.java` 주석.

## 공정 규칙 요약

- 봇의 세계 지식 = 자기 눈 위치에서 시야(FOV 70°, 16:9) 안으로 쏜 광선이 지나간/멈춘 블록 + 몸에 닿는 블록뿐
  (렌더 거리 상한, 로드 안 된 청크 불가). 청크 직접 스캔·구조물 조회·시드 읽기·월드 생성 미리 실행 없음.
- 입력은 사람이 낼 수 있는 것만: 시점 회전 45°/틱 상한, 사용 클릭 4틱 간격, 화면 클릭 2틱(100ms) 간격,
  핫바 1회/틱, 블록 도달 4.5칸. 채굴은 바닐라 클라이언트의 진행 누적 규칙(+5틱 쿨다운)을 그대로 따름.
- IGT = 봇이 월드에 들어온 틱부터의 서버 틱 수 / 20 (SpeedRunIGT의 일시정지 없는 IGT와 같음).
- 자세한 근거와 테스트는 docs/design.md §2.

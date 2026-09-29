# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 이 저장소

Claude Code 심화 과정(4회차)용 **실습 저장소**다. 코드 · 데이터 · 로그는 전부 더미 에듀테크 도메인(문항 은행 · 과제 배포 · 성적 집계)이다. 레거시 모듈을 분석해 현행 스택(`modern/`)으로 이관하고, 그 동작이 같은지 `characterization/` 스냅샷으로 검증하는 것이 중심 흐름이다.

- Claude Code 는 항상 저장소 루트에서 실행한다. 수업 중 만드는 `CLAUDE.md`, `.claude/`, `hooks/`, `docs/` 는 루트에 생긴다.
- 기준 환경: WSL2(Ubuntu 24.04) bash, JDK 21, Node.js 22.5+, Python 3.12, Docker Compose. 점검은 `bash scripts/check-env.sh`.
- 도메인 용어: 문항 `item` · 단원 `unit`(예: `M5-1`) · 난이도 `level`(1~5) · 태그 `tag` / 학급 `class` · 과제 `assignment` · 배포 `distribution` · 제출 `submission` / 학생 식별자 `STU-<숫자>`.

## 모듈 · 포트

| 모듈 | 위치 | 스택 | 주소 | Compose 프로필 |
|---|---|---|---|---|
| 문항 은행 (레거시) | `legacy/item-bank-php/` | PHP 7.4 + MariaDB | :8081 | `php` |
| 과제 배포 (레거시) | `legacy/assignment-thymeleaf/` | Spring MVC + Thymeleaf + JDBC | :8082 | `thymeleaf` |
| 성적 집계 (레거시) | `legacy/grade-mssql/` | MS-SQL 저장 프로시저(`sql/*.sql`) + 얇은 Java 호출부 | :8083 | `mssql` |
| 현행 API | `modern/api/` | Spring Boot 3.3 · Java 21 · JPA | :8080 (로컬 실행) | `modern` = DB 만 |
| 현행 화면 | `modern/web/` | React 18 · TS · Vite | :5173 (로컬 실행) | — |

- `php` · `thymeleaf` · `modern` 은 **MariaDB 서비스 하나**(DB `itembank`, :3306)를 공유한다. 스키마 · 시드는 `db/mariadb/init/`, MS-SQL 은 `db/mssql/init/`.
- DB 는 tmpfs 라 `down` → `up` 하면 시드 상태(고정 ID · 고정 시각)로 돌아온다.
- `down` · `build` · `logs` 도 반드시 `--profile` 을 붙인다 (예: `docker compose --profile php down`).
- 조회용 계정: MariaDB `readonly` / `readonly-pass`, MS-SQL `readonly` / `Readonly-pass1` (SELECT 전용). 쓰기 계정(`app`)은 compose · `application.yml` 안에서만 쓴다.

## 명령

```bash
# 현행 API — 테스트는 H2(MariaDB 모드, application-test.yml)라 DB 컨테이너 없이 통과해야 한다
cd modern/api && ./gradlew test
cd modern/api && ./gradlew test --tests 'com.example.item.ItemServiceTest'   # 단일 테스트
cd modern/api && ./gradlew bootRun          # 먼저 docker compose --profile modern up -d

# 현행 화면
cd modern/web && npm ci && npm run dev
cd modern/web && npm run lint && npm run typecheck && npm test
cd modern/web && npx vitest run src/components/ItemTable.test.tsx          # 단일 테스트

# 동작 보존 테스트 (대상 서비스가 떠 있어야 함; normalize.unit.test.js 만 서비스 없이 돈다)
cd characterization && npm ci
cd characterization && npm run baseline -- <item-bank|assignment|grade>   # 스냅샷 새로 찍기
cd characterization && npm test                                           # 대상: 레거시
cd characterization && TARGET_BASE_URL=http://localhost:8080 npm test     # 대상: 새 API

# MCP 서버 골격 (SDK v2 — v1 예제의 import 경로 · inputSchema 는 맞지 않는다)
cd mcp-skeleton && npm ci && npx tsc
```

의존성 설치는 `npm ci` 로 한다. `npm install` 은 npm 버전에 따라 `package-lock.json` 을 바꾼다.

## 아키텍처에서 알아 둘 것

**modern/api** (`com.example`): 도메인 패키지 `item/`, `assignment/`, 공통 `common/`, `config/`. 각 도메인은 Controller → Service → Repository → 엔티티 순으로 호출하고, 응답은 record DTO(`ItemResponse` 등)로 내보낸다. 예외 → HTTP 변환은 `common/GlobalExceptionHandler` 한 곳(`NotFoundException` → 404). 시각은 `ClockConfig` 의 `Clock` 빈을 주입받아 테스트에서 고정한다. 테스트는 `@ActiveProfiles("test")` + 컨트롤러 `@WebMvcTest` / 서비스 Mockito / 리포지토리 `@DataJpaTest`, 메서드명은 camelCase 에 `@DisplayName` 한국어 설명. 공개 문항만 노출한다(`status = 'A'`). `application.yml` 의 Hikari 풀(최대 5, 대기 3초)은 운영 값과 같게 맞춘 것이므로 바꾸지 않는다.

**modern/web**: 모든 요청은 `src/api/client.ts` 의 `getJson` 을 거치고, 실패는 `ApiError` 로 던진다. `src/api/items.ts` 가 엔드포인트 함수, `src/api/types.ts` 가 백엔드 JSON 필드명 그대로의 응답 타입. 조회 훅(`src/hooks/use*.ts`)은 공통 `useApiQuery(key, load)` 위에 만들며, `QueryState` 의 `status`(idle/loading/success/error)로 분기하고 AbortSignal 로 이전 요청을 취소한다. 테스트는 `src/test/mockFetch.ts` · `fixtures.ts` 로 fetch 를 가로챈다. `VITE_API_BASE=""` 로 두면 Vite 프록시(`/api` → 8080)를 쓴다.

**characterization**: 레거시의 **현재 응답이 기대값**이다. `lib/normalize.mjs` 가 HTML(`<table id="items">`, `#count`, `#message`)이든 JSON(`{items, count, message}`)이든 `{status, rows, count, message}` 로 정규화하므로 레거시와 새 API 가 같은 스냅샷으로 비교된다. 대상 주소는 `lib/target.mjs` 한 곳에만 있고, 이관으로 경로가 바뀌면 `PATH_ALIASES`(예: `/search.php` → `/api/items/search`)에 추가한다 — 레거시 경로가 404 면 대응 경로로 재요청한다. 규칙:
- 테스트는 `tests/<모듈명>.test.js`, 스냅샷은 `characterization/__snapshots__/` 에 모이며 커밋한다.
- 테스트에 `localhost:808x` 를 직접 쓰지 않고 `fetchNormalized(module, path, params)` 를 쓴다.
- 조회 동작만 찍는다. 흔들리는 값은 스냅샷을 손으로 고치지 말고 정규화(`VOLATILE_KEYS`, `HEADER_MAP`)를 고친다.
- 이관 후 비교가 실패하면 고치는 곳은 이관 코드다. 테스트 · 스냅샷을 고쳐서 통과시키지 않는다.

## 작업 규칙

- `legacy/` 는 분석 · 이관 대상이다. 허락 없이 수정하지 않는다. 레거시 규칙을 옮길 때는 근거를 `파일:줄번호` 로 적는다.
- DB 스키마 · 시드(`db/`), 의존성, `application.yml` 의 접속 · 풀 설정 변경은 먼저 묻는다.
- 실습 자료 폴더는 의도적으로 문제가 심어진 입력이다: `vendor-prs/`(외주 PR 패치 — 리뷰 브랜치에서만 `git apply`), `incident-logs/`(장애 시나리오 a~d), `pipeline-samples/`, `ci-ports/`, `specs/`(4-2 스펙 + `starters/java`·`starters/python` 골격). 이 파일들의 "문제"를 먼저 고치지 않는다.
- `templates/` 의 `CLAUDE.*.md` · 체크리스트는 참가자가 옮겨 쓰는 템플릿이다. 실제 코드 구조와 다를 수 있으니(예: React 템플릿의 `pages/` · msw) 사실 판단은 코드 기준으로 한다.
- Hook 은 `.claude/settings.json` 에서 `bash "$CLAUDE_PROJECT_DIR"/scripts/hook-node.sh hooks/<스크립트>.mjs` 형태로 등록한다. node 를 못 찾으면 명령을 막는다(fail-closed).
- 루트 `.env` 는 만들지 않는다. 권한 실습 더미는 `.env.perm-test` 다.

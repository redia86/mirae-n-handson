# 변경분 검증 2차 — 문항 검색 이관 (migration-review-2)

- 검증 대상: `git diff upstream/main...HEAD` (커밋 9건, 28개 파일, +3414 / -2). 1차 이후 `b2ce1fc` 가 추가됐다.
- 요청 범위: 레거시 엔드포인트 1개를 동작을 바꾸지 않고 modern 으로 이관.
- 기준 문서: `templates/approval-checklist.md` v0, `CLAUDE.md` 코딩 컨벤션 · 금지 사항
- 1차 결과: `docs/verify/migration-review-1.md` (반려)

판정: 반려

| 기준 | 결과 | 근거 |
|---|---|---|
| 테스트 통과 | 충족 | `cd modern/api && ./gradlew test` (`--rerun-tasks`) → 42 통과 / 0 실패 / 0 건너뜀. `cd characterization && npm test`(레거시 :8081) → 31 통과 / 0 실패. `TARGET_BASE_URL=http://localhost:8080 npm test`(새 API) → 30 통과 / 0 실패 / 1 건너뜀(`example-units.test.js:21` 의 `it.skipIf(legacyOnly)`, 이번 변경과 무관). 기존 테스트 삭제 · `@Disabled` 없음 |
| 치명 이슈 0건 | 충족 | 치명 이슈 없음 |
| 요청 범위 이탈 없음 | 미충족 | 이슈 #1(1차 #1 그대로). 코드 변경은 `GET /api/items/search` 하나로 설명되지만 `CLAUDE.md`, `.claude/skills/*`, `docs/assignment/*`, `docs/item-bank/*`, `docs/verify/migration-review-1.md`, characterization 베이스라인이 같은 범위에 들어 있다 |
| 컨벤션 준수 | 충족 | 1차 #2 해결 확인(아래). 새로 발견한 위반 없음 |

## 1차 이슈 처리 현황

| 1차 # | 상태 | 확인 |
|---|---|---|
| #1 범위 이탈 | 미해결 | 범위가 오히려 `docs/verify/migration-review-1.md` 만큼 늘었다 |
| #2 숫자 리터럴 `2` | 해결 | `ItemSearchParams.java:25` 에 `PERCENT_HEX_DIGITS = 2` 상수, `:95` `:96` `:98` `:100` 에서 사용. 남은 리터럴 `2` 없음, 120자 초과 줄 0건, 단위 테스트 통과 — 이번 재검증에서 직접 확인(아래 "재검증 기록") |
| #3 NPE 가능성(제안) | 미해결 | 승인 · 반려에 영향 없음 |

## 이슈 목록

| # | 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|---|
| 1 | 경고 | `CLAUDE.md`, `.claude/skills/{convention-check,document-module,verify}/SKILL.md`, `docs/assignment/*`, `docs/item-bank/*`, `docs/verify/migration-review-1.md`, `characterization/tests/item-bank.test.js`, `characterization/tests/__snapshots__/item-bank.test.js.snap` (전체 파일 · 줄번호 해당 없음) | 요청은 이관 1건인데 `upstream/main...HEAD` 에 이관 커밋(`0f2fd11`, `b2ce1fc`) 외에 베이스라인 · 문서 · Skill · 리뷰 문서 커밋이 섞여 있다. CLAUDE.md "요청받지 않은 파일을 고치지 않는다", 체크리스트 3-2 | 이관 PR 은 `modern/api` 코드 · 테스트만 담도록 분리하고 나머지는 별도 PR 로 올린다. 또는 요청에 포함돼 있었다면 요청 문장을 갱신한다. `0f2fd11` + `b2ce1fc` 만 보면 이 기준은 충족한다 |
| 2 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchService.java:48` | `details.get(id)` 가 `null` 이면 `ItemSearchRow.from` 에서 NPE → 500. 같은 readOnly 트랜잭션이라 가능성은 낮다 | 누락 id 는 건너뛰거나 명시적 예외로 바꾼다. 동작 변경이 되므로 이관과 별도 변경으로 다룬다 |

## 확인 필요

- 새 API 대상 characterization 은 `localhost:8080` 에 떠 있는 서버로 실행했다. 프로세스는 12:48 에 기동돼 있으나 `b2ce1fc` 이후 재기동됐는지, 현재 HEAD 로 빌드된 것인지 확인하지 못했다(`build/libs` 의 jar 는 9/29 빌드). `b2ce1fc` 는 동작 불변 리팩토링이라 결과 차이는 없을 것으로 보이나, 확정하려면 HEAD 로 다시 기동한 뒤 재실행이 필요하다.
- `ItemSearchSort` · `ItemSearchSpecifications` 는 전용 테스트가 없다. 간접 커버리지는 줄 단위 근거를 대지 못했다.
- 레거시 `search.php` 줄번호 주석과 실제 파일의 일치 여부는 대조하지 않았다.
- 코드는 수정하지 않았다.

## 재검증 기록

- 줄 길이: `upstream/main...HEAD` 에서 바뀐 `modern/api` 의 `.java` 전체를 `awk` 로 검사 → 120자 초과 줄 0건.
- 숫자 리터럴: `ItemSearchParams.java` 코드에서 0 · 1 이 아닌 리터럴 없음(검색에 걸린 `:15` `:24` `:27` 은 주석). `PERCENT_HEX_DIGITS`(`:25`) 는 `:95` `:96` `:98` `:100` 에서 사용된다.
- 레거시 서버: `docker compose --profile php ps` 에서 `item-bank`(:8081) · `mariadb` 가 Up(mariadb healthy). `GET /` · `GET /search.php` 모두 200. 앞서 `/api/units` 404 로 판단한 것은 PHP 앱에 없는 경로라 헬스체크로 부적절했고, 위 확인으로 대체한다.
- 새 API(:8080) 는 `GET /api/units` 200 으로 확인했다.

### 새 API 대상 characterization 실행 범위

- `TARGET_BASE_URL=http://localhost:8080 npx vitest run --reporter=verbose` 결과, `item-bank.test.js` 의 검색 케이스 17건(정상 3 · 경계값 8 · 빈 값 2 · 이상한 값 4)이 모두 통과했고 건너뛴 것이 없다. 이 파일에는 `skipIf` 가 없다(`characterization/tests/item-bank.test.js:6` 주석).
- 건너뛴 1건은 `example-units.test.js:21` 의 단원 목록(`units.php`) 케이스로 검색과 무관하다. `normalize.unit.test.js` 12건은 서버 없이 도는 단위 테스트다.
- 요청 경로: 테스트는 `/search.php` 로 요청한다. `:8080` 은 이 경로에 404 를 돌려주므로 `lib/target.mjs` 의 `PATH_ALIASES` 가 `/api/items/search` 로 다시 요청한다. 즉 새 API 의 검색 엔드포인트를 실제로 호출한 결과를 스냅샷과 비교한 것이다.
- 스냅샷 파일은 이번 실행에서 쓰이거나 obsolete 로 표시되지 않았고 `characterization/` 의 git 상태도 깨끗하다(테스트 · 스냅샷 수정 없음).

- 같은 대상(`main` = `b2ce1fc`, 28개 파일)으로 다시 실행: `./gradlew test` 42/0, characterization 레거시 31/0, 새 API 30 통과 / 0 실패 / 1 건너뜀. 판정 변화 없음.
- 이슈 #1 은 `feat/item-search-migration` 브랜치로 분리해 재검증했다 → `docs/verify/migration-review-3.md`(승인).

확인 필요
- 없음. 이전에 남겼던 characterization 검색 케이스 실행 범위는 위에서 확인했다.

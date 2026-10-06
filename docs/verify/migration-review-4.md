# 변경분 검증 4차 — 문항 검색 이관 (migration-review-4)

- 검증 대상: `git diff upstream/main...HEAD -- modern characterization` (16개 파일, +2200 / -2)
- 요청 범위: 레거시 엔드포인트 1개를 동작을 바꾸지 않고 modern 으로 이관.
- 기준 문서: `templates/approval-checklist.md`, `CLAUDE.md` 코딩 컨벤션 · 금지 사항
- 이전 결과: `docs/verify/migration-review-1.md` · `-2.md`(반려), `-3.md`(승인 — 대상은 분리 브랜치 `chore/day2-baseline-docs...feat/item-search-migration`)
- 수행: 체크리스트 대조는 reviewer Sub-agent, 테스트 실행은 tester Sub-agent 가 했다. 최종 판정은 메인 세션이 했다. 메인 세션은 줄 길이 · `application.yml` · 레거시 줄번호를 직접 다시 확인했다.

판정: 반려

| 기준 | 결과 | 근거 |
|---|---|---|
| 테스트 통과 | 미충족 | 판정 대상인 HEAD 트리로는 테스트를 실행하지 못했다. tester 의 허용 명령에 `git worktree` 가 없었다. 실행 결과는 모두 작업 트리 기준이고, 작업 트리에는 미커밋 `ItemSearchService.java` 수정과 tester 가 추가한 테스트 3개가 들어 있다. 작업 트리 결과: `cd modern/api && ./gradlew test --rerun-tasks` 는 추가 전 42 통과 / 0 실패, 추가 후 54 통과 / 0 실패 / 0 건너뜀. `cd characterization && npm test`(레거시)는 31 통과 / 0 실패. `TARGET_BASE_URL=http://localhost:8080 npm test -- tests/item-bank.test.js` 는 17 통과 / 0 실패. 단 `:8080` 이 어떤 코드로 기동됐는지 확인하지 못했다. 실패한 테스트 없음 |
| 치명 이슈 0건 | 충족 | 치명 이슈 없음. 비밀값 · 삭제 · 인증 변경 없음. 검색 조건은 Criteria API 바인딩으로 만든다 |
| 요청 범위 이탈 없음 | 미충족 | 이슈 #1. `modern/api` 14개 파일은 `GET /api/items/search` 이관 1건으로 설명된다. `characterization/` 2개 파일(베이스라인 커밋 `73c689e`)은 설명되지 않는다 |
| 컨벤션 준수 | 충족 | 반려 대상 위반 없음. 120자 초과 줄 0건(awk 로 확인). main 코드에서 0 · 1 이 아닌 숫자 리터럴은 모두 상수다. 와일드카드 import · try-catch · 엔티티 반환 · 컨트롤러 Repository 주입 없음. 테스트는 모두 `@ActiveProfiles("test")` 와 한국어 `@DisplayName` 을 쓴다. 제안 #5 는 판정에 영향 없음 |

## 이슈 목록

| # | 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|---|
| 1 | 경고 | `characterization/tests/item-bank.test.js`, `characterization/tests/__snapshots__/item-bank.test.js.snap` (신규 파일 전체, 커밋 `73c689e`) | 요청은 엔드포인트 1건 이관이다. 그런데 이관 전에 따로 만든 동작 보존 베이스라인이 같은 범위에 들어 있다(체크리스트 3-2). 3차는 이 둘을 브랜치로 나눠 승인받았지만, 현재 `day1` HEAD 범위에는 다시 섞여 있다. 스냅샷을 수정한 흔적은 없다. `characterization/` 을 바꾼 커밋은 `73c689e` 하나이고, 이관 커밋 `0f2fd11` 보다 앞선다 | 베이스라인은 선행 PR 로, 이관은 `modern/api` 14개 파일로 올린다. 요청에 베이스라인 작성이 포함됐다면 요청 문장을 갱신한다 |
| 2 | 경고 | `modern/api/src/main/java/com/example/item/ItemController.java:31`, `modern/api/src/main/resources/application.yml:28-29` | 컨트롤러는 원본 쿼리 문자열을 풀어 PHP 배열 규칙(BR-20, `legacy/item-bank-php/search.php:55-79`)을 따른다. 그런데 `application.yml` 에 `server.tomcat.relaxed-query-chars` 가 없다(메인 세션 확인). Tomcat 기본값이면 인코딩하지 않은 `[` `]` 를 거부할 수 있다. 그러면 `?level[]=3` 이 레거시는 200, 새 API 는 400 이 된다. MockMvc · `ItemSearchParamsTest` · characterization(`%5B` 인코딩)은 이 경로를 거치지 않는다. 실제 서버로는 확인하지 못했다 | `:8080` 에 `curl 'http://localhost:8080/api/items/search?level[]=3'` 로 확인한다. 차이가 맞으면 설정 변경이므로 먼저 묻고, 그 전까지는 의심 동작 목록에 올린다 |
| 3 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchService.java:48` (HEAD) | 2 · 3차 제안이 아직 해결되지 않았다. `details.get(id)` 가 null 이면 `ItemSearchRow.from` 에서 NPE 가 나 500 이 된다. 같은 readOnly 트랜잭션 안이라 실제로 일어날 가능성은 낮다 | 이관과 별도 변경으로 다룬다. 작업 트리의 미커밋 수정은 아래 "범위 밖 변경" 참고 |
| 4 | 제안 | `ItemSearchCondition.java:56`, `ItemSearchSpecifications.java:51`, `ItemSearchSort.java:8` | 레거시 근거 줄번호가 한 줄씩 어긋난다. `search.php:213` 은 주석 줄이다. 난이도 분기는 `:214-230`, 정렬 switch 는 `:266-327` 이다(메인 세션 확인). 기능에는 영향 없다 | 주석의 줄번호를 `:214-230`, `:266-327` 로 고친다 |
| 5 | 제안 | `ItemSearchConditionTest.java:43,47`, `ItemControllerTest.java:90`, `ItemRepositoryTest.java:93` | 테스트에 0 · 1 이 아닌 리터럴(`101`, `2`, `16`, `3`)이 있다. 반면 `ItemSearchServiceTest.java:26-27` 은 상수를 쓴다. CLAUDE.md 는 이 규칙이 테스트에도 적용되는지 정하지 않았다 | 팀 기준을 정해 한쪽으로 통일한다 |

## 동작 보존 대조 (reviewer)

`legacy/item-bank-php/search.php` 와 대조했고, 아래 항목은 모두 레거시와 같다.

- trim · 자르기(:85-87, :235-241)
- 단원 일치(:118)
- 난이도: 빈값이면 `level < 5`, `[1-5]` 면 그 값, 그 밖이면 `(int)` 변환(:214-230)
- 태그 EXISTS(:244-245)
- 정렬 5종(:266-327)
- 페이지(:336-349)
- 페이지 크기 20 · 뷰(:521-525)
- 0건 문구(:657-658)

### 의심 동작

이관 중이라 고치지 않고 그대로 보존했다.

- `search.php:213` 주석은 "1~5 모두 포함"이라고 쓰지만, `:215` 는 `level < 5` 라 난이도 5 가 빠진다. 이관 코드도 같은 동작이다(`ItemSearchSpecifications.java:15`).

## 범위 밖 변경 (판정 대상 아님)

- **미커밋 수정** `modern/api/src/main/java/com/example/item/ItemSearchService.java:48-53`
  - 상세 조회에서 빠졌거나 ACTIVE 가 아닌 행을 걸러 낸다.
  - 오류 경로의 동작이 바뀐다. 지금은 500 인데 수정 후에는 그 행을 빼고 200 을 낸다. `count` 는 그대로라 rows 수와 어긋날 수 있다.
  - 별도 변경으로 다뤄야 한다.
- **tester 가 추가한 테스트**: 미추적 파일 3개
  - `modern/api/src/test/java/com/example/item/ItemSearchSortTest.java` (4건)
  - `modern/api/src/test/java/com/example/item/ItemSearchSpecificationsTest.java` (7건, `@DataJpaTest`)
  - `modern/api/src/test/java/com/example/item/ItemSearchServiceDetailsGapTest.java` (1건)
    - 이 파일은 미커밋 필터를 검증한다. HEAD 에서는 실패할 것으로 보이나 실행하지 않았다.
    - 필터를 커밋하지 않는다면 이 파일은 빼야 한다.
- **그 밖**: `.claude/skills/verify/SKILL.md` 수정, `.claude/agents/` · `.claude/settings.json` 미추적. 존재만 기록한다.

## 확인 필요

- **HEAD 기준 테스트**: 사람이 아래 명령을 실행해야 한다.
  ```
  git worktree add <scratchpad>/verify-head HEAD
  cd <scratchpad>/verify-head/modern/api && ./gradlew test --rerun-tasks
  git worktree remove <scratchpad>/verify-head
  ```
- **새 API characterization**
  - `:8080` 서버가 HEAD · 작업 트리 · 옛 빌드 중 무엇으로 떠 있는지 확인하지 못했다. HEAD 로 다시 기동한 뒤 재실행해야 한다.
  - 이번에는 `tests/item-bank.test.js` 만 실행했다. 이관하지 않은 모듈 케이스가 8080 에서 실패하지 않게 하려는 것이었다. 3차의 전체 실행(30 통과 / 1 건너뜀)과는 범위가 다르다.
- **PHP 와 다를 수 있는 파라미터 처리**
  - PHP 는 파라미터 이름 앞의 공백을 지운다(`%20q=…` → `q`). `ItemSearchParams.java:46-48` 은 지우지 않는다.
  - 잘못된 UTF-8 바이트(`q=%FF`)를 `ItemSearchParams.java:105` 는 U+FFFD 로 바꾸고, PHP 는 원래 바이트를 그대로 둔다.
  - 둘 다 PHP 런타임 동작이라 레거시 줄번호 근거가 없다. 실행해서 비교해야 한다.
- **연속 공백 제목**: `characterization/lib/normalize.mjs` 는 HTML 제목의 연속 공백만 합친다. 시드에 연속 공백 제목이 있으면 코드와 무관하게 차이가 날 수 있다.
- **Sub-agent 보고 간 불일치**: 없다.
  - reviewer 는 테스트를 "확인 불가"로 적었다. tester 보고를 받기 전이었기 때문이다.
  - NPE 지적은 두 보고가 같다.
- **코드 수정**: 메인 세션 · reviewer 는 코드를 수정하지 않았다. tester 는 위 테스트 파일 3개만 추가했다.

# 변경분 검증 — 문항 검색 이관 (migration-review-1)

- 검증 대상: `git diff upstream/main...HEAD` (커밋 8건, 27개 파일, +3382 / -2)
- 요청 범위: 레거시 엔드포인트 1개를 동작을 바꾸지 않고 modern 으로 이관.
- 기준 문서: `templates/approval-checklist.md` v0, `CLAUDE.md` 코딩 컨벤션 · 금지 사항

판정: 반려

| 기준 | 결과 | 근거 |
|---|---|---|
| 테스트 통과 | 충족 | `cd modern/api && ./gradlew test` → 42 통과 / 0 실패 / 0 건너뜀. `cd characterization && npm test`(레거시 :8081) → 31 통과 / 0 실패. `TARGET_BASE_URL=http://localhost:8080 npm test`(새 API) → 30 통과 / 0 실패 / 1 건너뜀(`example-units.test.js:21` 의 `it.skipIf(legacyOnly)` — 단원 목록 예시 테스트로 이번 변경이 넣은 것이 아님). 삭제 · `@Disabled` 로 바뀐 기존 테스트 없음 |
| 치명 이슈 0건 | 충족 | 치명 이슈 없음. SQL 은 JPA Specification 바인딩, 비밀값 리터럴 없음, 삭제 · 덮어쓰기 코드 없음 |
| 요청 범위 이탈 없음 | 미충족 | 요청 문장은 "엔드포인트 1개 이관"이다. 코드 변경(`modern/api` 의 `item/` 아래 main 10개 · test 5개)은 `GET /api/items/search` 하나로 설명된다. 그러나 같은 범위에 `CLAUDE.md`, `.claude/skills/*`(3), `docs/assignment/*`(3), `docs/item-bank/*`(4), `characterization/tests/item-bank.test.js` · `__snapshots__/…snap` 이 들어 있어 요청 문장으로 설명되지 않는다 → 이슈 #1 |
| 컨벤션 준수 | 미충족 | 이슈 #2 (숫자 리터럴 `2`) |

## 이슈 목록

| # | 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|---|
| 1 | 경고 | `CLAUDE.md`, `.claude/skills/{convention-check,document-module,verify}/SKILL.md`, `docs/assignment/*`, `docs/item-bank/*`, `characterization/tests/item-bank.test.js`, `characterization/tests/__snapshots__/item-bank.test.js.snap` (전체 파일 · 줄번호 해당 없음) | 요청은 이관 1건인데 `upstream/main...HEAD` 에 이관 커밋(`0f2fd11`) 외에 `73c689e`(characterization 베이스라인), `9802b74`(문서 · Skill), `8b692f8`(verify Skill) 이 함께 들어 있다. CLAUDE.md "요청받지 않은 파일을 고치지 않는다"와 체크리스트 3-2 에 어긋난다. 파일 자체의 내용 문제는 아니다 | 이관 PR 은 `modern/api` 의 코드 · 테스트 파일만 담도록 분리한다. 문서 · Skill · characterization 베이스라인은 별도 PR 로 올리거나, 요청에 포함돼 있었다면 요청 문장을 갱신한다. 이관 커밋 `0f2fd11` 단독 범위(`git diff 0f2fd11^..0f2fd11`)는 코드와 테스트로 한정돼 있어 이 기준을 충족한다 |
| 2 | 경고 | `modern/api/src/main/java/com/example/item/ItemSearchParams.java:93`, `:95`, `:97` | CLAUDE.md "0 · 1 이 아닌 숫자 리터럴은 `static final` 상수로 뺀다". `i + 2 < bytes.length`, `bytes[i + 2]`, `i += 2` 에 리터럴 `2` 가 그대로 쓰였다. 같은 파일 `HEX_RADIX` 는 상수로 뺐으므로 일관성도 어긋난다 | `private static final int PERCENT_ESCAPE_TAIL = 2;` 같은 상수(`%` 뒤 16진수 자리 수)를 두고 세 곳을 바꾼다. 동작은 변하지 않는다 |
| 3 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchService.java:79` | `details.get(id)` 가 `null` 이면(페이지 조회와 상세 조회 사이에 문항이 사라진 경우) `ItemSearchRow.from` 에서 NPE → 500. 같은 트랜잭션(readOnly)이라 가능성은 낮다 | 누락 id 는 건너뛰거나 명시적 예외로 바꾼다. 이관 중 동작 변경이 되지 않게 별도 변경으로 다룬다 |

## 확인 필요

- 새 API 대상 characterization 은 `localhost:8080` 에 떠 있던 서버로 실행했다. 이 서버가 현재 HEAD 로 빌드된 것인지 확인하지 못했다(프로세스 기동 시각 · 빌드 해시 미확인). 통과 결과를 확정하려면 `./gradlew bootRun` 으로 HEAD 를 다시 띄운 뒤 같은 명령을 재실행하는 것이 좋다.
- `ItemSearchSort` · `ItemSearchSpecifications` 는 전용 테스트 클래스가 없다. `ItemRepositoryTest`(7건) · `ItemSearchServiceTest`(3건) 를 통해 간접 확인되는지는 줄 단위 근거를 대지 못해 이슈로 올리지 않는다. 정렬 · 태그 서브쿼리 분기별 커버리지를 확인하면 좋다.
- 의심 동작(레거시 버그로 보이지만 이관 중 고치지 않음)은 이번 검토에서 새로 발견하지 못했다. 레거시 규칙 근거(`search.php:줄번호`, BR 번호)는 코드 주석에 달려 있으나 줄번호가 `legacy/item-bank-php/search.php` 와 일치하는지는 대조하지 않았다.
- 코드는 수정하지 않았다.

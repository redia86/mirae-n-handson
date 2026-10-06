# 변경분 검증 3차 — 문항 검색 이관 PR (migration-review-3)

- 검증 대상: `git diff chore/day2-baseline-docs...feat/item-search-migration` (커밋 2건, 14개 파일, +837 / -2). 이관 PR 만 본다.
- 요청 범위: 레거시 엔드포인트 1개를 동작을 바꾸지 않고 modern 으로 이관.
- 기준 문서: `templates/approval-checklist.md` v0, `CLAUDE.md` 코딩 컨벤션 · 금지 사항
- 이전 결과: `docs/verify/migration-review-1.md`(반려), `docs/verify/migration-review-2.md`(반려 — 이슈 #1 범위 이탈)
- 이번 변경: 2차 이슈 #1 처리. 한 PR 에 섞여 있던 1회차 산출물을 선행 브랜치로 분리했다(아래 "분리 내역").

판정: 승인

| 기준 | 결과 | 근거 |
|---|---|---|
| 테스트 통과 | 충족 | `cd modern/api && ./gradlew test --rerun-tasks` → 42 통과 / 0 실패 / 0 건너뜀. `cd characterization && npm test`(레거시 :8081) → 31 통과 / 0 실패. `TARGET_BASE_URL=http://localhost:8080 npm test`(새 API) → 30 통과 / 0 실패 / 1 건너뜀(`example-units.test.js:21` `skipIf`, 단원 목록 케이스로 이번 변경과 무관). 검색 케이스 17건은 새 API 에서 전부 실행 · 통과(2차 리포트 "재검증 기록") |
| 치명 이슈 0건 | 충족 | 치명 이슈 없음 |
| 요청 범위 이탈 없음 | 충족 | 변경 14개 파일이 모두 `modern/api/src/{main,test}/java/com/example/item/` 아래다. 요청 "엔드포인트 1개 이관"(`GET /api/items/search`)으로 설명된다. 의존성 · DB 스키마 · 설정 파일 변경 없음 |
| 컨벤션 준수 | 충족 | 위반 없음. 120자 초과 줄 0건, 0 · 1 이 아닌 숫자 리터럴은 상수로 분리됨(`ItemSearchParams.java:25`) |

## 분리 내역

2차의 반려 사유(이슈 #1)는 `upstream/main...HEAD` 에 1회차 산출물이 함께 들어 있던 것이었다. 로컬 브랜치 두 개로 나눴다(원격 push 없음, `main` 은 그대로).

| 브랜치 | 기준 | 커밋 |
|---|---|---|
| `chore/day2-baseline-docs` | `c4a6eb6`(PR #1 병합) | 동작 보존 베이스라인 · 1회차 문서 · Skill · verify Skill · `migration-review-1.md` |
| `feat/item-search-migration` | 위 브랜치 위 | `78aacf5` 문항 검색 이관, `438c216` 숫자 리터럴 상수화 |

- 원래 `b2ce1fc` 에 `docs/verify/migration-review-1.md` 가 같이 들어 있어, 이를 선행 브랜치로 옮기고 이관 커밋에서는 뺐다.
- `feat/item-search-migration` 의 최종 트리는 `main` 과 동일하다(`git diff main HEAD` 차이 없음). 내용 손실 없음.
- 선행 브랜치의 변경(`CLAUDE.md`, `.claude/skills/*`, `docs/*`, `characterization/*`)은 이번 판정 대상이 아니다. 별도 PR 로 검토해야 하며 이 리포트는 그것을 승인하지 않는다.

## 이전 이슈 처리 현황

| 이전 # | 상태 | 확인 |
|---|---|---|
| 2차 #1 범위 이탈 | 해결 | 이관 PR 의 변경 파일이 `modern/api` 14개뿐 |
| 1차 #2 숫자 리터럴 | 해결 | `ItemSearchParams.java:25` `PERCENT_HEX_DIGITS`, `:95` `:96` `:98` `:100` 에서 사용 |
| 2차 #2 NPE 가능성 | 미해결(제안) | 아래 이슈 #1 로 이어 감 |

## 이슈 목록

| # | 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|---|
| 1 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchService.java:48` | `details.get(id)` 가 `null` 이면 `ItemSearchRow.from` 에서 NPE → 500. 같은 readOnly 트랜잭션이라 가능성은 낮다 | 누락 id 는 건너뛰거나 명시적 예외로 바꾼다. 동작 변경이므로 이관과 별도 변경으로 다룬다. 승인 · 반려에 영향 없음 |

## 확인 필요

- 컨벤션 항목은 2차에서 전수 대조한 코드와 트리가 같다(`main` 과 동일)는 점을 근거로 이월했고, 이번에는 줄 길이 · 숫자 리터럴만 다시 실행했다.
- `:8080` 서버는 분리 전 `main` 트리로 떠 있던 것이다. 두 트리가 같아 결과에 영향은 없지만, PR 머지 후 새로 기동해 한 번 더 확인하면 가장 확실하다.
- 코드는 수정하지 않았다.

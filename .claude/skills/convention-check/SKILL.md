---
name: convention-check
description: 변경된 코드가 팀 코딩 컨벤션과 금지 사항을 지켰는지 점검하고 위반 목록을 표로 보고합니다. 컨벤션 점검, 커밋 전 점검, 리뷰 전 확인 요청에 사용합니다.
argument-hint: "[점검할 경로 — 생략하면 git diff 대상]"
allowed-tools:
  - Read
  - Grep
  - Glob
  - Bash(git diff *)
  - Bash(git status *)
---

# 컨벤션 점검

### 점검 대상
- 인자가 있으면 $ARGUMENTS 경로 아래의 파일
- 없으면 git status 와 git diff 에 잡힌 변경 파일 (아직 add 하지 않은 새 파일 포함)

### 점검 항목
1. 컨트롤러가 Repository 나 SQL 을 직접 호출하지 않는가
2. 예외를 잡고 아무 처리 없이 넘기는 곳이 없는가
3. System.out.println 이 없는가 (로그는 SLF4J)
4. 새 의존성이나 스키마 변경이 섞이지 않았는가
5. 변경한 서비스에 대응하는 테스트가 있는가

### 출력 형식
1. 판정 요약: 점검 파일 수, 위반 건수
2. 위반 목록 표: 파일:줄번호 / 어긴 규칙 / 수정 방향

### 하지 말 것
- 코드를 직접 고치지 않습니다. 보고만 합니다.
- 근거 라인을 댈 수 없는 지적은 하지 않고 "확인 필요"로 남깁니다.
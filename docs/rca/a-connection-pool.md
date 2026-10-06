# RCA: a-connection-pool

## 요약

- **무엇이**: 2026-09-16 **14:40:52 ~ 14:51:47 KST**(약 11분) API 응답 실패 — 500 49건 · 502 82건 · 504 55건. 이상 징후는 14:37:42 부터, 14:52 에 자연 회복.
- **직접 원인**: 학급 리포트 요청(`GET /api/classes/{id}/report`)이 한 건당 DB 연결을 15.5~21.5초 쥔 채 동시에 몰려, 풀 5개를 모두 차지했다. 다른 API 는 3초 안에 연결을 얻지 못해 실패했다.
- **배경 원인**: `ReportService.buildClassReport` 의 트랜잭션 범위가 DB 조회 뒤의 서명 루프(SHA-256 20만 회)까지 감싸 연결을 놓지 않는다. 리포트 동시 실행 제한도 없다.
- **확신 수준: 중간** — 로그상 보유 시간의 대부분(133/134건)이 느린 쿼리 밖이었다. 그 시간에 서명 루프가 돌았다는 직접 증거(스레드 덤프 · 측정)는 없다(9절).

대상 로그: `incident-logs/a-connection-pool/` (4개 파일). 모든 시각은 KST(+09:00) 기준으로 적는다.

IP 는 역할 별칭으로 적는다: **API-1**(응답 서버), **API-2**(대기 · 연결 거부 서버), 리포트를 반복 조회한 단말 4대는 사고 구간 등장 순서대로 **교사단말A~D**.

## 1. 수집 범위

### 1.1 파일별 기록 기간 · 형식 · 타임존

| 파일 | 줄 수 | 기록 기간 (KST) | 시각 형식 (예) | 타임존 |
|---|---:|---|---|---|
| `app.log` | 5,371 (타임스탬프 줄 1,927 + 스택트레이스 연속 줄 3,444) | 2026-09-16 06:43:56 ~ 15:29:51 | ISO 8601 밀리초 + 오프셋 `2026-09-16T06:43:56.753+09:00` | KST (모든 줄 `+09:00`) |
| `nginx-access.log` | 21,086 | 2026-09-16 06:43:56 ~ 15:29:59 | nginx combined `[16/Sep/2026:06:43:56 +0900]` | KST (모든 줄 `+0900`) |
| `nginx-error.log` | 168 | 2026-09-16 09:12:03 ~ 14:51:47 | `2026/09/16 09:12:03` (오프셋 표기 없음) | KST — 오프셋이 없으나 access 로그와 대조해 확인(아래) |
| `mariadb-slow.log` | 21 (항목 3건) | 2026-09-16 09:14:07 ~ 14:37:52 (KST 환산) | `# Time: 260916  0:14:09` (YYMMDD, 시 한 자리 가능) + `SET timestamp=<epoch>` | **UTC** |

**KST 변환 규칙**

- `app.log`, `nginx-access.log`: 이미 KST. 변환 불필요.
- `nginx-error.log`: 표기 없음 → **KST 로 본다.** 근거: `nginx-error.log` 마지막 두 줄의 `upstream timed out`(14:51:03, 14:51:47, client 교사단말A / 교사단말C, `/api/classes/3/report`)이 `nginx-access.log:19351`, `nginx-access.log:19381` 의 504 응답(같은 시각 · 같은 client · 같은 URI, `+0900`)과 초 단위로 일치한다. UTC 였다면 23:51 KST 로 access 로그 범위(~15:29) 밖이 된다.
- `mariadb-slow.log`: **UTC → KST 는 +9시간.**
  - `# Time:` 은 `YYMMDD H:MM:SS`(UTC) 이고 로그가 쓰인 시각(쿼리 종료 무렵)이다.
  - `SET timestamp=` 는 epoch 초로 쿼리 **시작** 시각이다. `TZ=Asia/Seoul date -d @<epoch>` 로 변환한다.
  - 두 값 차이 ≈ `Query_time` 이므로 정렬 · 대조에는 `SET timestamp`(시작)를 쓰고, 끝 시각이 필요하면 `# Time` + 9h 를 쓴다.

| slow 항목 | `# Time` (UTC) | 시작 (KST, epoch 환산) | 끝 (KST) | Query_time |
|---|---|---|---|---:|
| `mariadb-slow.log:1` | 00:14:09 | 09:14:07 | 09:14:09 | 2.32s |
| `mariadb-slow.log:8` | 02:02:42 | 11:02:40 | 11:02:42 | 2.04s |
| `mariadb-slow.log:15` | 05:37:52 | 14:37:42 | 14:37:52 | 9.81s |

### 1.2 분 단위 이상 신호 집계

집계 기준 (타임스탬프가 있는 줄만, 스택트레이스 연속 줄 제외):

- `app.log`: `ERROR` 줄, `WARN` 줄을 세 종류로 나눔 — Hikari `leak detection`(leak), `state conflict`(409, 업무상 정상 응답), 그 밖의 WARN. 타임아웃은 `Connection is not available, request timed out after 3000ms`.
- `nginx-access.log`: 상태 코드 5xx(500 · 502 · 504). 분당 요청 수도 함께 봄.
- `nginx-error.log`: `[error]` · `[warn]` 줄, 그중 `upstream timed out`.
- `mariadb-slow.log`: 항목 3건뿐이라 위 표로 대신함.

**평소 수준 (06:43 ~ 14:36)**

| 신호 | 평소 |
|---|---|
| 요청 수 (access) | 분당 중앙값 41, 시간대 평균 23~46 (오전에 점증) |
| 5xx (access) | 0건 |
| 4xx (access, 하루 전체) | 분당 약 2.5건 (404 1,250 · 400 25 · 409 20) |
| app `ERROR` | 0건 |
| app `WARN` | 409 `state conflict` 만, 수십 분에 1건 |
| app leak WARN · Hikari 타임아웃 | 0건 |
| nginx-error `[error]`/`[warn]` | 09:12, 13:05 에 `buffered to a temporary file` warn 각 1건 (그 밖은 `[info]` 3건) |
| slow query | 09:14, 11:02 에 2초대 1건씩 |

**이상 구간 분 단위 (KST, 0 인 칸은 생략, 409 WARN 제외)**

| 분 | app leak WARN | app ERROR | Hikari 타임아웃 | app 기타 WARN | 500 | 502 | 504 | nginx-error err/warn | upstream timed out | 요청 수 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 14:37 | 1 | | | | | | | | | 50 |
| 14:38 | 2 | | | | | | | | | 41 |
| 14:39 | 3 | | | | | | | | | 42 |
| 14:40 | 14 | 2 | 1 | 1 | 1 | | | | | 42 |
| 14:41 | 13 | 12 | 6 | 6 | 6 | | 3 | 3 | 3 | 42 |
| 14:42 | 10 | 4 | 2 | 2 | 2 | 13 | 5 | 20 | 5 | 41 |
| 14:43 | 14 | 20 | 10 | 10 | 10 | 2 | 5 | 8 | 5 | 58 |
| 14:44 | 11 | 4 | 2 | 2 | 2 | 5 | 6 | 15 | 6 | 34 |
| 14:45 | 9 | 16 | 8 | 8 | 8 | 14 | 5 | 22 | 5 | 39 |
| 14:46 | 12 | 8 | 4 | 4 | 4 | 16 | 6 | 25 | 6 | 51 |
| 14:47 | 9 | 10 | 5 | 5 | 5 | 6 | 3 | 10 | 3 | 38 |
| 14:48 | 10 | 8 | 4 | 4 | 4 | 6 | 5 | 15 | 5 | 42 |
| 14:49 | 12 | 14 | 7 | 7 | 7 | 8 | 8 | 20 | 8 | 42 |
| 14:50 | 11 | | | | | 11 | 7 | 22 | 7 | 34 |
| 14:51 | 3 | | | | | 1 | 2 | 3 | 2 | 46 |
| 14:52~ | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 37~56 |

합계: 500 49 · 502 82 · 504 55 (모두 이 구간), app ERROR 98, Hikari 타임아웃 49, leak WARN 134, nginx-error `[error]` 137.

**신호별 첫 · 마지막 시각 (KST)**

| 신호 | 처음 | 마지막 |
|---|---|---|
| slow query 시작 (9.81s) | 14:37:42 | (끝 14:37:52) |
| app leak WARN | 14:37:52 | 14:51:37 |
| app `ProxyLeakTask` "Previously reported leaked connection … returned" (INFO) | 14:38:01 | 14:51:49 |
| app ERROR · Hikari 타임아웃 · 첫 5xx(500) | 14:40:52 | 14:49:51 |
| nginx-error `[error]` (`upstream timed out`) | 14:41:06 | 14:51:47 |
| 502 · `connect() failed` / `no live upstreams` | 14:42:01 | 14:51:02 |
| 마지막 5xx (504) | — | 14:51:47 |

참고: 요청 수는 이상 구간에도 분당 34~58건으로 평소 범위 안이다(유입량 급증 없음). app 로그는 07:21:48 → 07:24:51 (183초) 공백 외에 3분 넘는 공백이 없고, 기동 · 종료 메시지가 없다.

**제안 이상 구간 (KST)**

- **시작: 14:37:42** — 이상 신호 중 가장 이른 것(14:37:42 시작 slow query, 14:37:52 첫 leak WARN). 사용자 영향(5xx) 시작은 **14:40:52**.
- **끝: 14:51:49** — 마지막 5xx 14:51:47, 마지막 leak 반환 INFO 14:51:49. 14:52 이후 모든 신호가 평소 수준(0건)으로 돌아옴.

### 1.3 분석 창(앞뒤 여유) 제안

| 구분 | 범위 (KST) | 이유 |
|---|---|---|
| 앞 여유 | **30분** — 14:07:00 부터 | 첫 신호(14:37:42)가 이미 연결을 오래 쥐는 형태라 그 전부터 쌓였을 수 있다. 이전 slow query(11:02)는 3시간 이상 떨어져 있어 30분 창에는 넣지 않되, 비교용으로 별도 확인 대상에 둔다. |
| 핵심 구간 | 14:37:00 ~ 14:52:00 | 1.2 제안 구간을 분 단위로 맞춤. |
| 뒤 여유 | **10분** — 15:02:00 까지 | 14:52 부터 신호가 0 으로 회복. 재발 · 잔여 오류가 없는지 확인하는 데 10분이면 충분하다(실제로 15:29 로그 끝까지 재발 없음). |
| **최종 분석 창** | **2026-09-16 14:07:00 ~ 15:02:00 KST** | `mariadb-slow.log` 는 UTC 05:07:00 ~ 06:02:00 로 환산해 자른다. |

평소 수준 비교용 표본(대조군)으로는 같은 날 13:30 ~ 14:00 KST 를 쓴다(요청 수가 비슷하고 이상 신호 없음).

## 2. 타임라인

대상: 2026-09-16 **14:07:00 ~ 15:02:00 KST** (1.3 분석 창). 줄 범위는 `app.log:1135~5287`, `nginx-access.log` 14:07~15:01, `nginx-error.log:6~168`, `mariadb-slow.log:15~21` 이다.

표기 규칙

- 시각은 모두 KST다. `mariadb-slow.log` 는 UTC 에 9시간을 더했다(1.1).
- `nginx-access.log` · `nginx-error.log` 시각은 초 단위, `app.log` 는 밀리초 단위다. 다른 파일과 같은 초이거나 몇 초 안에 있는 사건은 **(선후 불확실)** 로 표시했다.
- 반복 메시지는 첫 발생 줄만 적고 "이후 N건/분"으로 묶었다. 분별 수는 1.2 표를 따른다.
- 원문 발췌의 학생 식별자는 `STU-…` 로 가렸다.
- 로그에 있는 사실만 적었고 해석은 하지 않았다.

### 2.1 가장 많은 오류(14:40:52~)보다 앞선 평소와 다른 줄

이 구간에서 가장 많이 나온 오류는 다음과 같다.

- `nginx-access.log` 502: 82건
- `nginx-error.log` `no live upstreams`: 68건
- `app.log` Hikari 타임아웃 ERROR: 49건

이 중 가장 이른 것은 14:40:52 의 Hikari 타임아웃이다. 그 전 30분 동안 나온 줄을 평소(1.2)와 비교했다.

| 구분 | 내용 | 평소와 비교 | 근거 |
|---|---|---|---|
| 느린 쿼리 | 14:37:42 시작, 9.81초, `Rows_examined: 1284310` | 같은 날 다른 slow 항목은 2초대이고, 검사 행 수는 41,822 · 5다 | `mariadb-slow.log:15-21` (비교: `mariadb-slow.log:4`, `mariadb-slow.log:11`) |
| 경고 | 14:37:52 부터 Hikari leak WARN | 이 시각 전에는 0건 | `app.log:1243` |
| 트래픽 변화 | 14:38 부터 `/api/classes/*/report` 요청이 분당 3건 → 14:40 10건 → 14:41~14:49 분당 12~19건 | 14:00대 앞부분(14:10~14:18)은 9분에 5건이었고, 14:18:13 ~ 14:38:01 에는 0건 | `nginx-access.log:17539-17914` (14:10~14:18), `nginx-access.log:17914` → `nginx-access.log:18801` (공백), `nginx-access.log:18801-19311` (14:38~14:49) |
| 설정 재적재 | 해당 로그 없음 | `reload` · `refresh` · `restart` · `config` · `SIGHUP` 를 grep 해도 3개 파일 모두 0건이다. app 기동 · 종료 메시지도 없다 | 해당 줄 없음 (`app.log` · `nginx-error.log` · `mariadb-slow.log` grep 0건) |
| 전체 트래픽 | 분당 요청 수 34~58 | 평소 범위(중앙값 41) 안 | `nginx-access.log:18751-19392` (14:37~14:51) |

### 2.2 통합 타임라인

| 시각 (KST) | 출처 | 사건 요약 | 원문 발췌 |
|---|---|---|---|
| 14:07~14:37 | `nginx-access.log:17405-18800` | 분당 요청 수 평소 수준. 5xx 0건 | — |
| 14:10:05 | `nginx-access.log:17539` / `app.log:1148` | 학급 리포트 조회(14:10~14:18 사이 5건, 모두 200) | `GET /api/classes/3/report HTTP/1.1" 200` / `built class report for class 3: 3 distributions, 10 submissions` |
| 14:11:38 | `app.log:1154` | 409 재배포 거절 WARN. 이후 14:14 · 14:33 에 1건씩(평소 빈도) | `state conflict: 마감된 과제는 재배포할 수 없습니다` |
| 14:18:13 | `nginx-access.log:17914` / `app.log:1175` | 14:38:01 전 마지막 리포트 조회. 이후 20분간 리포트 요청 0건 | `GET /api/classes/1/report HTTP/1.1" 200` |
| 14:19:38 | `nginx-access.log:17977` / `app.log:1182` | 배포 7 재배포(성공) | `POST /api/distributions/7/redistribute HTTP/1.1" 200` / `redistributed distribution 7 (assignment 5, class 3)` |
| 14:37:42 | `mariadb-slow.log:20-21` | 느린 쿼리 시작. `submission` 을 `distribution_id=5` 로 조회, 9.81초, 검사 행 1,284,310 | `select s1_0.id,…,s1_0.submitted_at from submission s1_0 where s1_0.distribution_id=5` |
| 14:37:52 | `mariadb-slow.log:15,18` | 위 느린 쿼리 종료(`# Time` 05:37:52 UTC) **(선후 불확실 — 아래 app.log:1243 과 같은 초)** | `# Time: 260916  5:37:52` / `# Query_time: 9.812417 … Rows_examined: 1284310` |
| 14:37:52.199 | `app.log:1243-1255` | **첫 Hikari leak WARN**. 연결 `@6e8f14d3`, 스레드 `exec-6`, 스택 `ReportController.classReport` **(선후 불확실 — 위 slow 종료와 같은 초)** | `Connection leak detection triggered for org.mariadb.jdbc.Connection@6e8f14d3 on thread http-nio-8080-exec-6` |
| 14:38:01 | `nginx-access.log:18801` / `app.log:1258-1259` | 리포트 요청 재개(교사단말A, 학급 3). 같은 초에 첫 leak 연결 반환 INFO **(선후 불확실 — 세 줄이 같은 초)** | `GET /api/classes/3/report HTTP/1.1" 200` / `Previously reported leaked connection org.mariadb.jdbc.Connection@6e8f14d3 … returned` |
| 14:38:01~ | `nginx-access.log:18801,18815,18839,18862,18882` | 교사단말A 가 학급 3 리포트를 약 30초 간격으로 반복 조회 | `교사단말A … "GET /api/classes/3/report HTTP/1.1" 200` |
| 14:38:20 | `app.log:1261` | leak WARN 반복. 이후 14:38 2건/분, 14:39 3건/분, 14:40~14:50 9~14건/분, 14:51 3건, 마지막 14:51:37 (총 134건, 134건 모두 스택이 `ReportController.classReport`) | `Connection leak detection triggered for … on thread http-nio-8080-exec-3` |
| 14:38:29 | `nginx-access.log:18817` / `app.log:1278` | 배포 5 재배포(성공) **(선후 불확실 — 두 파일이 같은 초)** | `POST /api/distributions/5/redistribute HTTP/1.1" 200` / `redistributed distribution 5 (assignment 3, class 3) reason=null` |
| 14:39:54 | `nginx-access.log:18881` | 두 번째 클라이언트(교사단말B)가 리포트 조회 시작 | `교사단말B … "GET /api/classes/1/report HTTP/1.1" 200` |
| 14:40:27 | `nginx-access.log:18904` | 세 번째 클라이언트(교사단말C)가 리포트 조회 시작 | `교사단말C … "GET /api/classes/3/report HTTP/1.1" 200` |
| 14:40:43 | `nginx-access.log:18912` | 네 번째 클라이언트(교사단말D)가 리포트 조회 시작. 리포트 요청 14:40 10건/분 → 14:41~14:49 12~19건/분 (14:38~14:52 클라이언트별 교사단말B 67 · 교사단말A 40 · 교사단말C 35 · 교사단말D 29건) | `교사단말D … "GET /api/classes/1/report HTTP/1.1" 200` |
| 14:40:52.319 | `app.log:1522,1547` | **첫 Hikari 연결 획득 타임아웃 ERROR**. 이후 14:41 6 · 14:43 10 · 14:45 8 · 14:49 7건/분 등, 마지막 14:49:51 (총 49건). `waiting` 은 1~7 이고 7 은 14:45:06(`app.log:3249`) | `itembank-pool - Connection is not available, request timed out after 3000ms (total=5, active=5, idle=0, waiting=…)` |
| 14:40:52.320 | `app.log:1523` / `nginx-access.log:18918` | **첫 5xx**. `/api/units/M6-2/items` 500. 이후 500 은 14:41 6 · 14:43 10 · 14:45 8건/분 등(총 49건) **(선후 불확실 — app ERROR 와 access 500 이 같은 초)** | `unhandled exception on /api/units/M6-2/items` / `GET /api/units/M6-2/items HTTP/1.1" 500` |
| 14:41:06 | `nginx-error.log:6` / `nginx-access.log:18933` | **첫 upstream 타임아웃 · 첫 504** (학급 3 리포트). 이후 504 는 14:41 3 · 14:42~14:50 3~8건/분(총 55건) **(선후 불확실 — 두 파일이 같은 초)** | `upstream timed out (110: Connection timed out) while reading response header from upstream` |
| 14:42:01 | `nginx-error.log:10` | upstream 서버 일시 제외(warn). 이후 14:50:52 까지 26건 **(선후 불확실 — 아래 두 줄과 같은 초)** | `upstream server temporarily disabled while reading response header from upstream` |
| 14:42:01 | `nginx-error.log:11` | upstream 연결 거부. 이후 14:50:52 까지 14건 **(선후 불확실)** | `connect() failed (111: Connection refused) while connecting to upstream` |
| 14:42:01 | `nginx-access.log:18970` | **첫 502** (학급 2 리포트). 이후 14:42 13 · 14:45 14 · 14:46 16 · 14:50 11건/분 등(총 82건) **(선후 불확실)** | `GET /api/classes/2/report HTTP/1.1" 502` |
| 14:42:04 | `nginx-error.log:12` | 사용 가능한 upstream 없음. 이후 14:51:02 까지 68건 | `no live upstreams while connecting to upstream` |
| 14:44:19 | `nginx-access.log:19078` / `app.log:2929` | 배포 8 재배포(성공) **(선후 불확실 — 두 파일이 같은 초)** | `POST /api/distributions/8/redistribute HTTP/1.1" 200` / `redistributed distribution 8 (assignment 6, class 2)` |
| 14:49:51 | `app.log:4980` / `nginx-access.log:19305` | 마지막 Hikari 타임아웃 ERROR · 마지막 500 | `request timed out after 3000ms` |
| 14:50:52 | `nginx-error.log:158-159` | 마지막 `temporarily disabled` · `Connection refused` | `upstream server temporarily disabled` / `connect() failed (111: Connection refused)` |
| 14:51:02 | `nginx-error.log:166` / `nginx-access.log:19347` | 마지막 `no live upstreams` · 마지막 502 **(선후 불확실 — 두 파일이 같은 초)** | `no live upstreams while connecting to upstream` |
| 14:51:37 | `app.log:5251` | 마지막 leak WARN | `Connection leak detection triggered for …` |
| 14:51:47 | `nginx-error.log:168` / `nginx-access.log:19381` | **마지막 upstream 타임아웃 · 마지막 504 · 마지막 5xx** (교사단말C, 학급 3 리포트) **(선후 불확실 — 두 파일이 같은 초)** | `upstream timed out (110: Connection timed out) … "GET /api/classes/3/report` |
| 14:51:49 | `app.log:5267` | 마지막 leak 연결 반환 INFO | `Previously reported leaked connection … returned` |
| 14:52~15:02 | 전체 | app ERROR · leak WARN · 5xx · nginx-error 모두 0건. 분당 요청 37~56건. 리포트 조회 14:59:15 · 14:59:52 · 15:01:20 3건, 모두 200 (`nginx-access.log:19701,19724,19781`). 409 WARN 14:51:52 · 14:52:09 (`app.log:5268-5269`) | — |

## 3. 가설과 검증

### 3.0 증상과 원인 구분

아래는 **증상**이다. 이 절에서는 원인으로 부르지 않는다.

- `app.log` Hikari 타임아웃 ERROR(`Connection is not available, request timed out after 3000ms`, 49건) — "풀에 빈 연결이 없었다"는 사실이고, 왜 비었는지는 말하지 않는다.
- `nginx-access.log` 500 · 502 · 504, `nginx-error.log` `upstream timed out` · `no live upstreams` — 사용자에게 보인 결과다.
- Hikari leak WARN(134건) — 연결을 10초(`leak-detection-threshold: 10000`, `modern/api/src/main/resources/application.yml`) 넘게 쥐고 있었다는 관찰이다. 스택은 연결을 **얻은 위치**이고, 그 시점에 스레드가 **무엇을 하고 있었는지**는 아니다.

타임라인(2절) 밖의 추가 관찰 1건(가설 H4 근거로 씀):

- `nginx-error.log` 의 `upstream:` 값은 `API-1:8080` 83건, `API-2:8080` 14건, `item_bank_api`(그룹 이름) 68건이다. `API-2` 는 14건 모두 `connect() failed (111: Connection refused)` 이고 첫 줄이 `nginx-error.log:11`(14:42:01)이다. `mariadb-slow.log` 의 클라이언트는 3건 모두 `API-1` 이다. `nginx-access.log` 는 combined 형식이라 요청별 upstream 주소가 없다.

### 3.1 가설 표

가설을 세울 때는 판정하지 않았다. "로그 검증 결과" · "이유" 열은 3.1.1 의 로그 전용 확인(H1-② · H1-③ · H2-① · H3-①②③)을 실행한 뒤 채웠다. jshell · docker · mysql 은 실행하지 않았다. 각 가설은 서로 배타적이지 않다(여러 개가 함께 맞을 수 있다).

| # | 계층 | 가설 | 지지 근거 (타임라인 줄) | 반증 조건 (이것이 확인되면 가설이 틀림) | 확인 방법 | 로그 검증 결과 | 이유 (3.1.1 실행 기록) |
|---|---|---|---|---|---|---|---|
| H1 | 애플리케이션 코드 | 학급 리포트 처리(`ReportService.buildClassReport`)가 DB 조회를 마친 뒤에도 `@Transactional` 안에서 CPU 작업(서명 루프 `SIGNATURE_ROUNDS` 200,000회)을 하며 연결을 쥐고 있어, 리포트 요청 몇 건만 겹쳐도 풀(5개)이 바닥난다. | `app.log:1243-1254` 첫 leak 스택이 `ReportController.classReport` → `ReportService.buildClassReport`. `app.log:1243-5251` leak 134건 모두 같은 스택. `app.log:1243`(leak 14:37:52.199 → 획득 ≈14:37:42) · `mariadb-slow.log:15,20`(slow query 14:37:42 시작 · 14:37:52 종료) · `app.log:1259`(반환 14:38:01.902) — DB 작업 뒤 약 10초를 더 쥠. `app.log:1522` 타임아웃 메시지가 `active=5, idle=0`. | ① 서명 루프가 운영 수준 페이로드(배포 3 · 제출 10건 내외)에서 수백 ms 이하로 끝난다. ② leak 쌍(WARN → returned INFO)의 보유 시간이 같은 시각 slow query 의 `Query_time` 과 거의 같다(쿼리 밖 보유 시간이 없음). ③ 리포트 요청이 없는 분에도 leak WARN 이 나온다. | ① `modern/api/src/main/java/com/example/assignment/ReportService.java:41`(`@Transactional`) · `:71`(`sign` 호출) · `:85-93`(루프)을 연다. `jshell` 로 `MessageDigest` SHA-256 200,000회 반복 시간을 잰다(동시 4건이면 4개 스레드로). ② `grep -n -e 'leak detection triggered' -e 'was returned to the pool' incident-logs/a-connection-pool/app.log` 로 `Connection@<해시>` 별 WARN · INFO 시각을 짝지어 보유 시간(= INFO − WARN + 10초)을 구하고, `mariadb-slow.log` 항목 시각과 겹치는지 본다. ③ 1.2 표의 leak 분별 수와 `grep -c '/report'` 분별 수를 나란히 놓는다. | **유지** | ② 반증 조건 불충족: leak 134쌍 보유 시간 15.5~21.5초(중앙 18.9초) 중 133쌍은 slow query(14:37:42~52)와 겹치지 않는다. slow 기준이 2.04초 이하(아래 H2)이므로 리포트 1건의 쿼리 5회를 모두 기준 직전(2.04초)으로 잡아도 최소 5.3초는 쿼리 밖 보유다. `built class report` → 반환 INFO 간격은 134쌍 모두 2ms. ③ 반증 조건 불충족: leak 이 있는 분 15개 중 리포트 요청 0건인 분은 14:37 하나이고, 이는 14:37:42 시작 요청이 14:38:01 에 끝나 access 로그(종료 시각 기록)에 14:38 로 찍힌 것. leak 스택 134/134 가 `ReportController.classReport`. ① 은 jshell 미실행 → 서명 루프 자체의 소요 시간은 **판단 불가**(필요 자료: 스레드 덤프 또는 측정). |
| H2 | DB · 쿼리 | `submission` 을 `distribution_id` 로 읽는 쿼리가 인덱스를 제대로 쓰지 못하거나(실행 계획 · 통계) 대상 행이 많아져 느려졌고, 그만큼 연결 보유 시간이 길어져 풀이 고갈됐다. | `mariadb-slow.log:18` 9.81초 · `Rows_examined 1,284,310`(같은 날 다른 항목은 2초대 · 41,822 / 5행, `mariadb-slow.log:4`, `:11`). `mariadb-slow.log:18,21` 대상 쿼리 `where s1_0.distribution_id=5 order by s1_0.student_id`, `Rows_sent 4`. `app.log:1243` · `mariadb-slow.log:15` 첫 leak 이 slow query 종료와 같은 초. | ① `long_query_time` 이 10초 미만인데 14:38~14:51(leak 134건 구간)에 slow 항목이 하나도 없다 → 연결을 오래 쥔 동안 쿼리는 느리지 않았다. ② `EXPLAIN` 이 `idx_submission_distribution` 을 쓰고 예상 rows 가 작다. ③ `submission` 전체 행 수가 1,284,310 보다 훨씬 작아 그 수치를 재현할 수 없다(→ 일시적 원인 · 다른 데이터). | ① `grep -n '^# Time' incident-logs/a-connection-pool/mariadb-slow.log` (항목이 3건뿐인지 · 시각), 로컬 DB(`docker compose --profile modern up -d`) 에서 `mysql -hlocalhost -ureadonly -p[SECRET] -e "SHOW VARIABLES LIKE 'long_query_time'"` — 운영 값은 운영팀에 확인 요청. ② 같은 계정으로 `EXPLAIN SELECT id,distribution_id,score,student_id,submitted_at FROM itembank.submission WHERE distribution_id=5 ORDER BY student_id;` ③ `SELECT COUNT(*) FROM itembank.submission;` 와 `db/mariadb/init/01-schema.sql:105-114`(인덱스 정의). | **기각** | ① 반증 조건 충족: slow log 에 2.041903초 항목(`mariadb-slow.log:11`)이 기록됐으므로 `long_query_time` ≤ 2.04초(로그에서 추정, 설정 원본 미확인)이고, 14:38~14:51 slow 항목은 0건이다(전체 3건: 09:14 · 11:02 · 14:37). 연결 보유 134쌍 중 133쌍이 느린 쿼리 없이 15초 넘게 유지됐다. ②③ 은 mysql 미실행 → 9.81초 쿼리 1건의 원인(1,284,310행 검사)은 **판단 불가**(필요 자료: 운영 DB EXPLAIN · 행 수). |
| H3 | 트래픽 | 전체 요청 수는 평소와 같지만, 교사 리포트 화면을 연 클라이언트 4대가 약 30초 간격으로 리포트를 반복 조회하면서 **리포트 엔드포인트의 동시 실행 수**가 풀 크기(5)를 넘었다(요청 구성 변화). | `nginx-access.log:17914` → `:18801` 리포트 요청 14:18~14:38 0건, `nginx-access.log:18801-19311` 14:38 3건 → 14:40 10건 → 14:41~14:49 12~19건/분. `nginx-access.log:18801,18815,18839,18862,18882` 교사단말A 가 약 30초 간격 반복. `nginx-access.log:18881`, `:18904`, `:18912` 14:39:54 · 14:40:27 · 14:40:43 에 클라이언트 2 · 3 · 4번째 합류, 첫 Hikari 타임아웃(`app.log:1522`)은 4번째 합류 9초 뒤. `nginx-access.log:18751-19392` 전체 요청은 평소 범위. | ① 같은 날 다른 시간대에 리포트 요청이 분당 10건 이상 · 클라이언트 3대 이상이었는데 오류 · leak 이 없었다. ② 리포트 요청이 늘기 **전**(14:38:01 이전)에 이미 Hikari 타임아웃이나 연결 대기가 있었다. ③ 14:52 이후 리포트 요청이 같은 수준으로 이어졌는데 오류가 없다. | ① `grep '/api/classes/[0-9]*/report' incident-logs/a-connection-pool/nginx-access.log \| awk '{print substr($4,14,5), $1}' \| sort \| uniq \| awk '{n[$1]++} END{for(m in n) print m, n[m]}' \| sort` 로 하루 전체의 분별 고유 클라이언트 수를, `awk '{print substr($4,14,5)}'` 로 분별 건수를 뽑아 14:38~14:51 과 비교한다. ② `grep -n -e 'request timed out' -e 'waiting=' incident-logs/a-connection-pool/app.log \| head -1`. ③ `sed -n '19392,$p' nginx-access.log \| grep -c '/report'`(14:52 이후). | **유지** | ① 반증 조건 불충족: 사고 구간(14:37~14:51) 밖 리포트 요청은 하루 58건, 분당 최대 2건, 분당 고유 클라이언트 최대 2(12:20 · 14:59 · 15:20). 사고 구간은 분당 10~19건(14:40~14:50), 고유 클라이언트 4(14:40~14:49). 같은 부하가 다른 시간대에 없었다. ② 불충족: 첫 연결 대기 · 타임아웃은 14:40:52(`app.log:1522`)로 리포트 증가(14:38) 뒤. ③ 불충족: 14:52 이후 리포트는 15:29 까지 6건(분당 최대 2) — 같은 수준으로 이어지지 않았다. 4개 클라이언트의 마지막 리포트 요청 14:49:53~14:51:47 와 회복(14:52)이 겹친다. |
| H4 | 인프라 | upstream 2대 중 `API-2` 가 그날 연결을 거부하는 상태(프로세스 미기동 · 장애)라 모든 요청이 `API-1` 한 대(풀 5개)로 몰렸고, 처리 용량이 평소의 절반이었다. | `nginx-error.log:11` `connect() failed (111: Connection refused)` — 이 줄의 upstream 은 `API-2`(3.0 추가 관찰). `nginx-error.log:10` · `:12` `API-1` 이 `temporarily disabled` 되자 곧바로 `no live upstreams`(살아 있는 upstream 이 없음). `mariadb-slow.log:2,9,16` slow query 클라이언트는 `API-1` 만. | ① nginx upstream 설정에서 `API-2` 가 `backup` 이거나 원래 서비스 대상이 아니다(평소에도 1대 운영 → 용량 변화 아님). ② `API-2` 의 app 로그 · 프로세스 이력상 14:42 전에는 정상 응답 중이었고 refused 는 그 뒤에만 났다(→ 원인이 아닌 결과). ③ 평소 시간대 access 로그(upstream 주소를 남기는 설정이 있다면)에 `API-2` 응답이 있었다. | ① nginx 설정의 `upstream item_bank_api { … }` 블록(이 저장소에 없음 — 운영팀에 요청)에서 `server` 줄과 `backup` · `max_fails` · `fail_timeout` 을 본다. ② `API-2` 의 `app.log` · 컨테이너 재시작 이력 요청(이 폴더에 없음). ③ `grep -c 'API-2' incident-logs/a-connection-pool/*.log` 로 다른 흔적 유무, nginx `log_format` 에 `$upstream_addr` 가 있는지 확인. | **판단 불가** | 이 시나리오 로그(`a-connection-pool/`)로는 `API-2` 가 평소 서비스 대상이었는지 알 수 없다. 필요했던 로그 · 자료: nginx `upstream item_bank_api` 설정, `$upstream_addr` 를 남기는 access 로그, `API-2` 호스트의 app 로그 · 프로세스 기동 이력. |
| H5 | 배포 · 설정 변경 | 사고 전 배포로 풀 설정(`maximum-pool-size 5` · `connection-timeout 3000`)이 줄었거나 리포트 서명 로직(`SIGNATURE_ROUNDS`)이 새로 들어와, 이전에는 견디던 같은 부하를 못 견디게 됐다. | `app.log:1522` 오류 메시지의 `total=5` · `3000ms` 가 현재 `application.yml:14-15` 값과 같음. 당일 로그에는 재적재 · 재기동 흔적이 없다(`app.log` · `nginx-error.log` · `mariadb-slow.log` 에서 `reload` · `refresh` · `restart` · `config` · `SIGHUP` grep 0건, 해당 줄 없음) — 변경이 있었다면 06:43(로그 시작) **이전** 배포여야 한다는 제약. | ① 직전 배포 이후 며칠 동안 같은 설정 · 같은 코드로 리포트 조회(오후 시간대 동시 조회 포함)가 있었는데 오류가 없었다. ② 배포 이력상 해당 주에 `api` 배포가 없다. ③ 풀 설정 · `ReportService.sign` 이 오래전부터 같은 값이다. | ① `git log -p -- modern/api/src/main/resources/application.yml modern/api/src/main/java/com/example/assignment/ReportService.java` (이 저장소에서는 초기 커밋 1건뿐이라 운영 배포 이력과 대조가 필요). ② 운영 배포 이력(이 폴더에는 `deploy-history.md` 가 없음 — `incident-logs/b-deploy-5xx/` 와 혼동하지 않는다)을 운영팀에 요청. ③ 이전 날짜 `app.log` 에서 `grep -c -e 'leak detection' -e 'request timed out'`. | **판단 불가** | 이 시나리오 로그는 09-16 하루치뿐이고 배포 이력이 없다. 필요했던 로그 · 자료: 이 사고 묶음의 배포 이력, 09-08~09-15 `app.log`(같은 코드에서 leak · 타임아웃 유무), 운영 `application.yml` 변경 이력. `incident-logs/b-deploy-5xx/deploy-history.md` 는 다른 장애 묶음(b-deploy-5xx)의 자료라 참고만 하고 판정에 쓰지 않는다. |

### 3.1.1 로그 검증 실행 기록

모든 명령은 `incident-logs/a-connection-pool/` 에서 실행했다. 출력은 그대로 옮겼다(IP 는 별칭으로 마스킹)(결과에 학생 식별자 · 토큰 없음).

**H1-② leak 쌍별 보유 시간 vs slow query**

```bash
grep -c 'leak detection triggered' app.log      # 134
grep -c 'was returned to the pool' app.log      # 134
```

짝짓기 스크립트(연결 해시 + 스레드로 WARN 과 returned INFO 를 묶고, 획득 시각 = WARN − 10초):

```python
import re, statistics
from datetime import datetime, timedelta
ts = lambda s: datetime.fromisoformat(s)
warn = re.compile(r'^(\S+)\s+WARN .*leak detection triggered for \S+@(\w+) on thread (\S+),')
ret = re.compile(r'^(\S+)\s+INFO .*leaked connection \S+@(\w+) on thread (\S+) was returned')
open_, pairs = {}, []
for no, line in enumerate(open('app.log', encoding='utf-8'), 1):
    if m := warn.match(line):
        open_[(m[2], m[3])] = (ts(m[1]), no)
    elif m := ret.match(line):
        w, wno = open_.pop((m[2], m[3]))
        pairs.append((w - timedelta(seconds=10), ts(m[1]), wno, no, m[3]))
holds = [(r - a).total_seconds() for a, r, *_ in pairs]
# … 분포 출력, slow 구간(14:37:42~14:37:52)과 겹치는 쌍 출력
```

```text
pairs 134 unmatched 0
hold sec min 15.5 median 18.9 max 21.5
  10~15s 0
  15~20s 85
  20~30s 49
  30~60s 0
  60~999s 0
overlap with slow 14:37:42 14:37:52 9.81 -> [('14:37:42', '14:38:01', 19.7, 'app.log:1243-1259')]
pairs not overlapping any slow query 133
```

같은 스레드의 `built class report` 로그 → 반환 INFO 간격:

```text
returned with preceding built-report on same thread 134 without 0
built->returned ms min 2 max 2
```

사고 구간 · 평소 구간에서 만들어진 리포트 크기(`app.log` 의 `built class report` 집계):

```text
# 14:37~14:51
     54 built class report for class 1: 3 distributions, 10 submissions
     22 built class report for class 2: 3 distributions, 10 submissions
     58 built class report for class 3: 3 distributions, 10 submissions
# 14:10~14:18 (평소, leak 0건)
      3 built class report for class 1: 3 distributions, 10 submissions
      1 built class report for class 2: 3 distributions, 10 submissions
      1 built class report for class 3: 3 distributions, 10 submissions
```

- 사고 구간 리포트 134건 = leak 134건(모두 10초 초과 보유). 사고 구간 밖 리포트 58건은 leak 0건(10초 미만). 리포트 크기는 두 구간이 같다(배포 3 · 제출 10).
- 리포트 1건의 쿼리 수는 코드상 5회다(`ReportService.java:43` 학급 1 · `:46` 배포 1 · `:53-54` 배포별 제출 3). 각 쿼리가 slow 기준(≤ 2.04초) 미만이므로 쿼리 합은 10.2초 미만이고, 최소 보유 15.5초에서 빼도 5.3초 이상이 쿼리 밖이다.

**H1-③ 분별 leak WARN vs 분별 리포트 요청**

```bash
grep 'leak detection triggered' app.log | cut -c12-16 | sort | uniq -c > leak_min.txt
grep -E '"GET /api/classes/[0-9]+/report' nginx-access.log | awk '{print substr($4,14,5)}' | sort | uniq -c > report_min.txt
join -1 2 -2 2 -a 1 -e 0 -o 0,1.1,2.1 <(awk '{print $1,$2}' leak_min.txt) <(awk '{print $1,$2}' report_min.txt)
grep -A12 'leak detection triggered' app.log | grep -c 'ReportController.classReport'   # 134
```

```text
14:37 leak=1 report=0  <-- 리포트 0
14:38 leak=2 report=3
14:39 leak=3 report=3
14:40 leak=14 report=10
14:41 leak=13 report=16
14:42 leak=10 report=15
14:43 leak=14 report=19
14:44 leak=11 report=14
14:45 leak=9 report=15
14:46 leak=12 report=19
14:47 leak=9 report=12
14:48 leak=10 report=14
14:49 leak=12 report=16
14:50 leak=11 report=10
14:51 leak=3 report=5
```

14:37 의 leak 1건은 14:37:42 에 시작해 14:38:01 에 끝난 요청이다(nginx access 로그는 응답 종료 시각을 기록해 14:38 에 잡힘, `nginx-access.log:18801`).

**H2-① slow log 항목과 기준값**

```bash
grep -n -e '^# Time' -e '^# Query_time' mariadb-slow.log
grep -c -i 'long_query_time' mariadb-slow.log   # 0 (헤더에 설정값 없음)
```

```text
1:# Time: 260916  0:14:09
4:# Query_time: 2.318446  Lock_time: 0.000071  Rows_sent: 7  Rows_examined: 41822
8:# Time: 260916  2:02:42
11:# Query_time: 2.041903  Lock_time: 0.912004  Rows_sent: 5  Rows_examined: 5
15:# Time: 260916  5:37:52
18:# Query_time: 9.812417  Lock_time: 0.000071  Rows_sent: 4  Rows_examined: 1284310
```

- 2.041903초 항목이 기록됐으므로 `long_query_time` ≤ 2.04초로 본다(추정 — 설정 원본 · `log_slow_rate_limit` 같은 표본 추출 설정은 로그로 확인할 수 없음).
- 14:38~14:51(KST, UTC 05:38~05:51) 항목은 0건.

**H3-① 하루 전체 리포트 요청 분포**

```bash
# 분별 건수 상위 10분
grep -E '"GET /api/classes/[0-9]+/report' nginx-access.log | awk '{print substr($4,14,5)}' | sort | uniq -c | sort -rn | head -10
# 분별 고유 클라이언트 수가 2 이상인 분
grep -E '"GET /api/classes/[0-9]+/report' nginx-access.log | awk '{print substr($4,14,5), $1}' | sort -u | awk '{n[$1]++} END{for(m in n) if(n[m]>=2) print m, n[m]}' | sort
# 사고 구간(14:37~14:51) 밖: 분당 최대 3개 · 총건수
grep -E '"GET /api/classes/[0-9]+/report' nginx-access.log | awk '{t=substr($4,14,5)} t<"14:37"||t>="14:52"{print t}' | sort | uniq -c | sort -rn | head -3
grep -E '"GET /api/classes/[0-9]+/report' nginx-access.log | awk '{t=substr($4,14,5)} t<"14:37"||t>="14:52"' | wc -l
```

```text
     19 14:46
     19 14:43
     16 14:49
     16 14:41
     15 14:45
     15 14:42
     14 14:48
     14 14:44
     12 14:47
     10 14:50
---
12:20 2
14:39 2
14:40 4
14:41 4
14:42 4
14:43 4
14:44 4
14:45 4
14:46 4
14:47 4
14:48 4
14:49 4
14:50 3
14:51 2
14:59 2
15:20 2
---
      2 15:20
      2 14:59
      2 12:20
58
```

**H3-② 첫 연결 대기 · 타임아웃**

```bash
grep -n -m1 -e 'request timed out' -e 'waiting=' app.log | cut -c1-40
```

```text
1522:2026-09-16T14:40:52.319+09:00 ERROR
```

**H3-③ 회복 후 리포트 요청**

```bash
grep -E '"GET /api/classes/[0-9]+/report' nginx-access.log | awk '{t=substr($4,14,8)} t>="14:52"{print t, $1, $9}'
grep -E '"GET /api/classes/[0-9]+/report' nginx-access.log | awk '{t=substr($4,14,8)} t>="14:37" && t<"14:52"{last[$1]=t} END{for(c in last) print c, last[c]}' | sort
```

```text
14:59:15 교사단말C 200
14:59:52 교사단말B 200
15:01:20 교사단말C 200
15:04:55 교사단말C 200
15:20:31 교사단말C 200
15:20:39 교사단말A 200
---
교사단말B 14:50:52
교사단말A 14:51:24
교사단말C 14:51:47
교사단말D 14:49:53
```

### 3.2 가설 사이의 관계 (검증 순서 참고용, 판정 아님)

- H1 · H2 는 "한 요청이 연결을 왜 오래 쥐었나", H3 · H4 는 "동시에 몇 요청이 쥐었나 / 받을 수 있는 그릇이 몇 개였나", H5 는 "그 조건이 언제부터 그랬나"를 다룬다. 풀 고갈은 (보유 시간 × 동시 요청 수 > 풀 크기)일 때 생기므로 둘 이상이 함께 맞을 수 있다.
- 비용이 가장 적은 확인부터: H1-②(app.log 만으로 보유 시간 계산) → H2-①(slow log 항목 수) → H3-①(access 로그 집계) → H1-①(jshell 측정) → H2-②③(로컬 DB, readonly 계정) → H4 · H5(운영팀 자료 필요). 이 중 로그만으로 되는 H1-② · H1-③ · H2-① · H3-①②③ 은 실행했다(3.1.1). H1-① · H2-②③ 은 실행하지 않았다.
- 로컬 DB 는 시드 데이터라 운영 행 수 · 통계를 재현하지 않는다. H2 확인 결과를 운영 판정에 그대로 쓰지 않는다. 운영 DB(`prod-db` 등)에는 접속하지 않는다.

### 3.3 판정

대조 범위: `modern/api` 설정 파일(`application.yml`), 로그에 찍힌 클래스(`ReportController`, `ReportService`, `GlobalExceptionHandler`)와 그 리포지토리 · 엔티티, DB 스키마(`db/mariadb/init/01-schema.sql`). 벤치마크 · 테스트 · jshell · docker · mysql 은 실행하지 않았다. 설정값은 모두 파일에서 읽은 값이다. 코드 · 설정의 **주석**은 판정 근거로 쓰지 않았다(실행되는 코드와 설정값만 씀).

`incident-logs/b-deploy-5xx/deploy-history.md` 는 **다른 장애 묶음(b-deploy-5xx)의 자료, 참고만** 한다. 판정 · 기각 · 확신 수준의 근거로 쓰지 않는다.

#### 가설별 대조 결과

| # | 코드 · 설정 판정 | 근거 (파일:줄번호) | 로그 검증 결과 (3.1.1) |
|---|---|---|---|
| H1 | **지지한다** | `ReportService.java:41` `buildClassReport` 전체가 `@Transactional` → 메서드가 끝날 때까지 연결을 쥔다. `ReportService.java:52-63` DB 조회(배포별 `findByDistributionIdOrderByStudentIdAsc`)가 끝난 **뒤** `ReportService.java:71` 에서 `sign()` 을 호출한다. `ReportService.java:23` `SIGNATURE_ROUNDS = 200_000`, `ReportService.java:88-91` SHA-256 을 그만큼 반복. 호출부는 로그 스택과 같은 `ReportController.java:22`. 풀 설정 `application.yml:14` `maximum-pool-size: 5`, `:15` `connection-timeout: 3000`, `:18` `leak-detection-threshold: 10000` 이 로그(`total=5`, `3000ms`, 10초 뒤 leak WARN)와 일치. 다른 도메인 요청이 연결을 못 얻어 500 이 되는 경로는 `GlobalExceptionHandler.java:53-55`(그 밖의 예외 → 500)로 `app.log:1523` 과 같다. | 유지 |
| H2 | **반박한다** | `db/mariadb/init/01-schema.sql:112` `KEY idx_submission_distribution (distribution_id)` 가 느린 쿼리의 조건 컬럼과 같다. 쿼리는 파생 메서드 `SubmissionRepository.java:8` 의 단순 조건 조회라 코드상 인덱스를 피할 이유가 없다. 1,284,310 행 검사를 설명할 코드 · 스키마는 찾지 못했다. | 기각 |
| H3 | **관련 코드를 찾지 못했다** | 반복 조회를 만드는 클라이언트 코드는 이 저장소에 없다(`modern/web/src` 에 report · `setInterval` 검색 0건, 요청 출처는 저장소 밖 `lms.example.com/teacher/classes/*/report`). 풀 크기 `application.yml:14` 5 는 동시 리포트 4~5건이면 바닥나는 조건이다. | 유지 |
| H4 | **관련 코드를 찾지 못했다** | nginx `upstream item_bank_api` 설정은 저장소에 없다(`item_bank_api` · `API-2` 검색 시 로그 · 문서 외 0건). `docker-compose.yml` 에도 nginx · 앱 2대 구성이 없다. | 판단 불가 |
| H5 | **관련 코드를 찾지 못했다** | 저장소의 `application.yml` · `ReportService.java` 이력은 초기 커밋 1건뿐(`1260542`, 2026-09-22)이라 사고 전 변경 여부를 코드에서 알 수 없다. (참고만: 다른 장애 묶음 `b-deploy-5xx/deploy-history.md:8-9` 에 09-08 리포트 API 추가, 09-14 `ItemService.java` 변경 기록이 있다.) | 판단 불가 |

#### 채택: H1 — 학급 리포트가 DB 조회를 마친 뒤에도 트랜잭션 안에서 서명 루프를 돌며 연결을 쥔다

- 메커니즘: 리포트 1건이 연결 1개를 (DB 조회 + 서명 루프) 동안 쥔다(`ReportService.java:41` · `:71`) → 리포트 동시 요청이 늘자(H3, 촉발 조건) 풀 5개(`application.yml:14`)가 모두 리포트에 묶인다 → 다른 요청이 3초(`application.yml:15`) 안에 연결을 못 얻어 500 → 리포트 요청은 nginx 대기 시간을 넘겨 504, `API-1` 이 `temporarily disabled` 된 뒤 502(`no live upstreams`). `API-2` 의 `Connection refused` 가 왜 났는지는 판단 불가(H4).
- 로그 수치(3.1.1):
  - 사고 구간 리포트 134건 모두 leak(보유 15.5~21.5초, 중앙 18.9초), 사고 구간 밖 리포트 58건은 leak 0건. 리포트 크기는 두 구간이 같다(배포 3 · 제출 10).
  - 134쌍 중 133쌍은 slow query 와 겹치지 않는다. slow 기준(≤ 2.04초, 로그에서 추정) 아래 쿼리 5회를 최대로 잡아도 최소 5.3초가 쿼리 밖 보유다.
  - `built class report` 로그 → 연결 반환 간격이 134쌍 모두 2ms — 보유가 메서드 종료(트랜잭션 끝)와 함께 끝난다. 그 직전에 실행되는 코드는 `ReportService.java:65-74`(평균 계산 · `sign()` · 로그)다.
  - leak 은 리포트 요청이 있는 분에만 나왔고(14:37 은 access 기록 시점 차이), 스택 134/134 가 `ReportController.classReport`.
- **확신 수준: 중간** (이전과 같음. 근거를 주석에서 로그 수치로 바꿨다).
  - 높이는 이유: 코드 구조(`ReportService.java:41` 트랜잭션 범위 안에 `:71` 서명 호출)와 로그 수치가 맞는다 — 보유 시간 대부분이 쿼리 밖이고(133/134), 보유가 메서드 끝에서 2ms 안에 풀린다. 경쟁 가설 H2 는 로그로 기각됐다.
  - 낮추는 이유: ① 쿼리 밖 5.3초 이상 동안 스레드가 **무엇을 했는지**는 로그에 없다(스레드 덤프 없음). 코드상 그 자리에 있는 것은 서명 루프뿐이지만, GC 정지 · CPU 부족 같은 다른 원인을 로그로 배제하지 못했다. ② 같은 크기의 리포트가 평소에는 10초 미만, 사고 구간에는 15.5~21.5초 걸렸다. 보유 시간이 동시 요청 수에 따라 늘었다는 뜻인데, "서명 루프가 CPU 를 두고 경쟁해 느려졌다" 는 추정이고 측정하지 않았다(jshell 미실행). ③ slow 기준 ≤ 2.04초는 로그에서 추정한 값이다. ④ H4 · H5 는 판단 불가라 "왜 이날 처음" 인지는 열려 있다.
  - "높음" 으로 올리려면: 사고 재현 중 스레드 덤프(쿼리 밖 구간에 `ReportService.sign` 프레임이 있는지), `sign()` 단독 · 4스레드 동시 소요 시간 측정, 09-08~09-15 `app.log` 의 leak 유무.

#### 나머지 가설의 처리

| # | 처리 | 사유 | 남는 역할 |
|---|---|---|---|
| H2 | **기각** | 로그: slow 기준 ≤ 2.04초인데 leak 134건 구간(14:38~14:51)에 slow 항목 0건, 133/134쌍이 느린 쿼리 없이 15초 넘게 보유. 코드: 조건 컬럼에 인덱스(`01-schema.sql:112`), 단순 조회(`SubmissionRepository.java:8`). | 14:37:42 쿼리 1건(9.81초)이 첫 연결 보유를 늘린 계기일 수 있다. 1,284,310 행 검사 이유는 판단 불가(운영 DB EXPLAIN 필요). |
| H3 | **기각하지 않음 — 촉발 조건으로 유지** | 로그로 반증되지 않았다. 리포트 분당 10~19건 · 동시 클라이언트 4대는 이날 사고 구간에만 있었고(구간 밖 분당 최대 2건 · 2대), 리포트 요청이 줄면서 회복했다. 다만 이 가설은 "왜 동시 요청이 많았나" 만 설명하고 "왜 한 요청이 15~21초 연결을 쥐었나" 는 설명하지 못해 단독 원인으로 채택하지 않는다. 이전 판정의 "정상 업무 조건" 표현은 로그 근거가 없어 철회한다. | 원인(H1)이 드러난 조건. 재발 조건: 리포트 동시 요청 4건 이상. |
| H4 | **판단 불가** | 이 시나리오 로그로는 `API-2` 가 평소 서비스 대상이었는지 알 수 없다. 필요한 자료: nginx upstream 설정, `$upstream_addr` 포함 access 로그, `API-2` 호스트 app 로그 · 기동 이력. | — |
| H5 | **판단 불가** | 이 시나리오 로그는 09-16 하루치이고 배포 이력이 없다. 필요한 자료: 이 사고 묶음의 배포 이력, 09-08~09-15 `app.log`, 운영 설정 변경 이력. | — |

#### 의심 동작 메모 (수정하지 않음)

- `ReportService.java:69-71` 는 저장소의 컨벤션 "`@Transactional` 메서드 안에서 외부 HTTP 를 호출하지 않는다" 의 취지(트랜잭션 안에서 오래 걸리는 외부 연동 작업)와 맞지 않는다. 코드에 이미 `TODO: 트랜잭션 밖으로` 가 있다. 이번 작업은 분석만이므로 고치지 않았다.

## 4. 원인

인과 순서. 문장 번호는 5절의 근거 표와 맞춘다.

### 4.1 배경 원인 (사고를 가능하게 한 구조)

- **B1** `ReportService.buildClassReport` 는 메서드 전체가 `@Transactional` 이다. 조회가 끝난 뒤 실행하는 서명 루프(SHA-256 200,000회) 동안에도 DB 연결을 쥔다.
- **B2** 리포트 엔드포인트에는 동시 실행 제한이 없다. 리포트가 풀 전체를 차지할 수 있다.
- **B3** 풀은 5개, 연결 대기 한도는 3초다. 연결을 오래 쥐는 요청 몇 건이 겹치면 다른 API 가 곧바로 실패한다.
- **B4** 연결 장기 보유 신호(leak WARN)는 사고 3분 전부터 찍혔지만, 경보로 이어지는 지표가 없었다.

### 4.2 직접 원인 (그날 일어난 일)

- **D1** 14:38 부터 교사 클라이언트 4대가 학급 리포트를 반복 조회해, 리포트 요청이 분당 10~19건이 됐다. 같은 날 다른 시간대에는 분당 최대 2건이었다. *(촉발 조건)*
- **D2** 사고 구간 리포트 134건이 모두 연결을 10초 넘게(15.5~21.5초) 쥐었다. 133건은 느린 쿼리와 겹치지 않고, 연결은 리포트 생성 로그 2ms 뒤에야 반환됐다. 동시 점유 추정: 14:43 · 14:46 분당 리포트 19건 × 중앙 보유 18.9초 ÷ 60 ≈ **6.0개 > 풀 5개** — 리포트만으로 풀을 넘는다(19건은 nginx 기준이라 앱에 닿지 않은 502 가 14:43 1건 · 14:46 6건 포함된 상한값이다. 502 를 빼면 18건 ≈ 5.7개, 13건 ≈ 4.1개).
- **D3** 5개 연결이 모두 리포트에 묶여, 14:40:52 부터 다른 요청이 3초 대기 끝에 실패했다(Hikari 타임아웃 → 500).
- **D4** 리포트 요청 자체도 응답이 늦어 nginx 가 504 를 냈다. `API-1` 을 upstream 에서 일시 제외한 뒤에는 남은 upstream `API-2` 가 연결을 거부해 502 가 됐다.
- **D5** 리포트 요청이 끝나자(마지막 14:51:47) 연결이 반환되며 14:52 부터 모든 이상 신호가 0 으로 돌아왔다.

참고: 14:37:42 의 9.81초 쿼리(H2)는 첫 연결 보유를 늘렸을 수 있으나, 이후 133건에는 해당하지 않아 원인으로 보지 않는다.

## 5. 근거 로그 줄

| 문장 | 근거 (파일:줄번호) | 발췌 |
|---|---|---|
| B1 | `modern/api/.../assignment/ReportService.java:41`, `:71`, `:23`, `:88-91` | `@Transactional` / `String signature = sign(payload.toString());` / `SIGNATURE_ROUNDS = 200_000` |
| B1 | `app.log:1243-1254` | `Connection leak detection triggered … on thread http-nio-8080-exec-6` … `at com.example.assignment.ReportController.classReport(ReportController.java:22)` |
| B2 | `modern/api/.../assignment/ReportController.java:20-22` | `@GetMapping("/{id}/report")` → `return reportService.buildClassReport(id);` (제한 장치 없음) |
| B3 | `modern/api/src/main/resources/application.yml:14-15` | `maximum-pool-size: 5` / `connection-timeout: 3000` |
| B4 | `app.log:1243` (14:37:52) vs `app.log:1522` (14:40:52) | 첫 leak WARN 이 첫 타임아웃보다 3분 앞섬 |
| D1 | `nginx-access.log:18801`, `:18881`, `:18904`, `:18912` | `교사단말A … 14:38:01 … "GET /api/classes/3/report" 200` / `.11 … 14:39:54` / `.17 … 14:40:27` / `.23 … 14:40:43` |
| D1 | 3.1.1 H3-① 집계 — 사고 구간 `nginx-access.log:18801-19381`, 비교 구간 `nginx-access.log:1-18800` · `:19382-21086` | 사고 구간 분당 10~19건 · 클라이언트 4 / 구간 밖 분당 최대 2건 · 2 |
| D2 | 3.1.1 H1-② 집계 — `app.log:1243-5267` (leak WARN 134건 · 반환 INFO 134건), slow 대조 `mariadb-slow.log:15-21` | `hold sec min 15.5 median 18.9 max 21.5` / `pairs not overlapping any slow query 133` / `built->returned ms min 2 max 2` |
| D2 | `app.log:1258-1259` | `14:38:01.900 … built class report for class 3` → `14:38:01.902 … leaked connection … was returned to the pool` |
| D3 | `app.log:1522-1523`, `nginx-access.log:18918` | `itembank-pool - Connection is not available, request timed out after 3000ms (total=5, active=5, idle=0, …)` / `unhandled exception on /api/units/M6-2/items` / `"GET /api/units/M6-2/items" 500` |
| D3 | `app.log:3249` | 14:45:06 `waiting=7` (최대 대기 수) |
| D4 | `nginx-error.log:6`, `nginx-access.log:18933` | `14:41:06 upstream timed out (110: Connection timed out) … /api/classes/3/report` / `504` |
| D4 | `nginx-error.log:10-12`, `nginx-access.log:18970` | `upstream server temporarily disabled` / `connect() failed (111: Connection refused) … upstream: "http://API-2:8080…"` / `no live upstreams` / `"GET /api/classes/2/report" 502` |
| D5 | `nginx-access.log:19381`, `nginx-error.log:168`, `app.log:5251`, `app.log:5267` | 14:51:47 마지막 504 / 14:51:37 마지막 leak WARN / 14:51:49 마지막 연결 반환 |
| 참고 | `mariadb-slow.log:15-21` | `Query_time: 9.812417 … Rows_examined: 1284310` / `where s1_0.distribution_id=5` |

## 6. 수정안 (지금 적용할 것 · 코드 미수정, 제안만)

**6.1 리포트 생성에서 트랜잭션 범위를 DB 조회로 좁힌다** — `modern/api/src/main/java/com/example/assignment/`

| 파일 | 무엇을 | 어떻게 |
|---|---|---|
| `ReportQueryService.java` (신규) | 조회 · 집계만 하는 서비스 | `@Transactional(readOnly = true) public ReportSource load(Integer classId)`. 현재 `ReportService.java:43-67`(학급 · 배포 · 제출 조회, 합계 · 평균, payload 조립)을 옮긴다. 반환은 record `ReportSource(classId, className, distributionCount, submissionCount, averageScore, payload)`. |
| `ReportService.java:41` | `@Transactional` 제거 | `buildClassReport` 는 `reportQueryService.load(classId)` 를 호출한 뒤 **트랜잭션 밖에서** `sign(source.payload())` 를 실행하고 `ClassReport` 를 만든다. 같은 클래스 안 호출은 프록시를 거치지 않아 트랜잭션이 걸리지 않으므로, 조회 부분은 반드시 별도 빈으로 뺀다. |
| `ReportService.java:69-70` | TODO 주석 | 위 변경으로 해소되므로 삭제. |
| 테스트 | 컨벤션(새 public 서비스 메서드마다 테스트) | `ReportQueryServiceTest`(Mockito: 조회 결과 → 합계 · 평균 · payload), `ReportServiceTest` 는 `ReportQueryService` 를 목으로 바꾸고 서명 · 응답 조립을 검증. |

- 기대 효과: 연결 보유 시간이 쿼리 5회 시간으로 줄어든다. 서명 루프 동안에는 풀에 연결이 남는다.
- 응답 형식(`ClassReport`) · 서명 값은 바뀌지 않는다. 기존 `ReportServiceTest` 의 서명 기대값으로 확인한다.

**6.2 이번에 바꾸지 않는 것**

- Hikari 풀 크기 · 대기 시간(`application.yml:14-15`): 운영 값과 같게 맞춘 설정이고 변경 전 승인이 필요하다. 풀을 키워도 보유 시간 문제는 남으므로 수정안에 넣지 않는다.

## 7. 재발 방지 (같은 유형을 구조적으로 막는 장치)

| # | 장치 | 막는 것 | 비고 |
|---|---|---|---|
| 7.1 | 팀 규칙에 "`@Transactional` 메서드 안에서 CPU 집약 작업(반복 해시 · 서명 · 암호화) · 외부 호출을 하지 않는다" 를 추가하고, `convention-check` · `verify` 점검 항목에 넣는다 | 트랜잭션 범위가 DB 작업 밖으로 넓어지는 코드가 리뷰를 통과하는 것 | 현재 규칙은 외부 HTTP 만 명시. 아키텍처 테스트(ArchUnit 등)로 자동화하려면 의존성 추가 승인이 필요하다 |
| 7.2 | 무거운 엔드포인트(리포트 등)에 동시 실행 상한(풀 크기보다 작게, 예: 2)을 두는 격벽(bulkhead) | 한 엔드포인트가 풀 전체를 차지해 다른 API 까지 실패하는 것 | 상한 초과 요청은 대기 대신 즉시 429/503 으로 응답 |
| 7.3 | 배포 전 동시성 점검: 새 엔드포인트를 풀 크기(5) 이상 동시 호출하면서 다른 엔드포인트가 3초 안에 응답하는지 확인 | 단건 응답 시간만 보고 배포하는 것 | 단건 테스트로는 이번 문제를 재현할 수 없다 |
| 7.4 | 교사 리포트 화면의 반복 조회 주기 · 결과 캐시를 화면 담당과 합의 | 사용자 수에 비례해 무거운 요청이 늘어나는 것 | 클라이언트 코드는 이 저장소 밖 |

## 8. 모니터링 항목 (더 빨리 알아채기)

알림 시각은 이번 로그로 다시 계산한 값이다. 사용자 영향(첫 5xx)은 **14:40:52**.

| 지표 | 임계값 (제안) | 이번에 울렸을 시각 | 첫 5xx 대비 | 근거 |
|---|---|---|---|---|
| Hikari 연결 장기 보유(leak WARN 또는 연결 사용 시간 최댓값) | 10초 초과 1건 이상 | **14:37:52** | 3분 앞 | `app.log:1243` |
| DB 느린 쿼리 | 5초 초과 1건 | 14:37:52 | 3분 앞 | `mariadb-slow.log:15` (단발 경보로만, 이번 원인과 직접 연결되진 않음) |
| 엔드포인트별 분당 요청 수(리포트) | 직전 1시간 최대치의 3배 이상 · 분당 5건 이상 | 14:41:00 (14:40 분 10건 집계 후) | 8초 뒤 | 3.1.1 H1-③ |
| Hikari 연결 획득 실패 | 분당 1건 이상 | 14:40:52 | 동시 | `app.log:1522` |
| 5xx 비율 | 1분 동안 1% 초과 | 14:41:00 (14:40 분 1/42 = 2.4%) | 8초 뒤 | 1.2 표 |
| nginx upstream 제외 · `no live upstreams` | 1건 이상 | 14:42:01 | 69초 뒤 | `nginx-error.log:10` |

- 이번에 가장 빨리 울렸을 지표는 **연결 장기 보유**다. 지금은 WARN 로그로만 남는다. 경보로 쓰려면 로그 기반 알림을 붙이거나, Hikari 지표(`active` · `pending` · usage)를 내보내야 한다. 지표를 내보내려면 actuator · micrometer 의존성 추가가 필요하다(현재 `build.gradle` 에 없음, 추가 전 승인 필요).
- `leak-detection-threshold` 를 10초보다 낮추면 더 일찍 잡힌다. 다만 풀 설정 변경이라 승인 대상이고, 이번 로그로는 몇 초가 적정한지 정할 수 없다.

## 9. 확인하지 못한 것

| 항목 | 왜 판단 못 했나 | 다음을 위해 남길 로그 · 자료 |
|---|---|---|
| 쿼리 밖 보유 시간(최소 5.3초)에 스레드가 한 일 | 스레드 덤프 · 구간별 소요 시간 로그가 없다. 서명 루프라는 것은 코드 구조에서 나온 추정이다 | 리포트 처리 구간별 소요 시간 로그(조회 · 서명 각각 ms, 학생 식별자 제외), leak 감지 시 스레드 덤프 |
| 같은 크기 리포트가 평소 10초 미만 → 사고 중 15~21초로 늘어난 이유 | CPU 사용률 · GC 로그가 없다 | 앱 호스트 CPU · GC 로그 |
| `long_query_time` 실제 값 | slow log 에 설정이 없어 2.04초 이하로만 추정 | MariaDB 설정값(`long_query_time`, `log_slow_rate_limit`) 기록 |
| 14:37:42 쿼리가 1,284,310행을 검사한 이유 | 운영 DB 실행 계획 · 행 수를 보지 않았다 | 해당 쿼리의 운영 `EXPLAIN`, `submission` 행 수 |
| `API-2` 가 연결을 거부한 이유와 평소 역할(H4) | nginx upstream 설정 · `API-2` 로그가 없다 | nginx 설정 원본, access 로그에 `$upstream_addr` · `$request_time` · `$upstream_response_time` 추가 |
| 리포트 코드 도입 이후 이날 처음 터졌는지(H5) | 09-16 하루치 로그뿐, 이 사고의 배포 이력 없음 (`b-deploy-5xx/deploy-history.md` 는 다른 장애 묶음 자료라 참고만) | 이 서비스의 배포 이력, 이전 날짜 `app.log` 보존 |
| 실시간 풀 상태(active · pending) 추이 | 타임아웃 메시지에만 찍혀 14:40:52 이전 상태를 모른다 | Hikari 지표 수집(8절) |
| 교사 클라이언트가 반복 조회한 이유(자동 새로고침 여부) | 클라이언트 코드가 저장소 밖 | 화면 담당에게 조회 주기 확인 |

마스킹 적용: IP 별칭 54건(전체 주소 40 · 축약 표기 4 · `.21`/`.22` 꼬리 10) · IP 부분 가림 0건 · 이메일 0건 · 학생 식별자 0건(발췌에 실제 값 없음) · 토큰 0건 · 비밀번호 1건(`[SECRET]`)

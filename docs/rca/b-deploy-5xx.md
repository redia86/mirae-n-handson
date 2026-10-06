# RCA: b-deploy-5xx

> **작성 범위(중간본)**: 요청에 따라 1 수집 범위 · 2 타임라인 · 3 가설 표까지만 작성했다. 3절의 로그 반증 · 코드 대조와 4 원인 ~ 9 확인하지 못한 것은 아직 작성하지 않았다. 따라서 이 문서에는 원인 판정이 없다.

## 요약

- **무엇이**: 2026-09-17 v1.4.2 배포(13:40:05 시작) 전후로 `item-bank-api` 응답 실패 — 502 8건(13:41:51 ~ 13:41:59) · 500 46건(배포 전 7건 09:52 ~ 11:18, 배포 후 39건 13:46:38 ~ 14:34:45).
- **원인**: 미판정(3절 검증 · 4절 미작성).

대상 로그: `incident-logs/b-deploy-5xx/` (3개 파일). 모든 시각은 KST(+09:00) 기준으로 적는다.

IP 는 역할 별칭으로 적는다: **API-1**(배포 대상 호스트), **API-2**(대기 호스트, 평소 정지). 교사 단말(`/teacher/…` Referer)은 사고 구간(배포 시작 13:40:05 이후) 등장 순서대로 **교사단말A**, **교사단말B**, **교사단말C**. 학생 단말 · 헬스체크(`kube-probe`) 주소는 구분할 필요가 없어 앞 두 옥텟만 남긴다.

## 1. 수집 범위

### 1.1 파일별 기록 기간 · 형식 · 타임존

| 파일 | 줄 수 | 기록 기간 (KST) | 시각 형식 (예) | 타임존 |
|---|---:|---|---|---|
| `app.log` | 2,938 (타임스탬프 줄 1,275 + 스택트레이스 연속 줄 1,663) | 2026-09-17 09:00:04 ~ 14:39:57 | ISO 8601 밀리초 + 오프셋 `2026-09-17T09:00:04.878+09:00` | KST (모든 줄 `+09:00`) |
| `nginx-access.log` | 20,365 | 2026-09-17 09:00:00 ~ 14:39:58 | nginx combined `[17/Sep/2026:09:00:00 +0900]` | KST (모든 줄 `+0900`) |
| `deploy-history.md` | 39 | 2026-09-01 ~ 2026-09-17 배포 4건 | `2026-09-17 13:40:05` | KST (`deploy-history.md:3` 에 "시각은 모두 KST" 명시) |

- 세 파일 모두 KST 라 변환하지 않았다.
- `nginx-access.log` 는 초 단위, `app.log` 는 밀리초 단위다. 두 파일 사이 몇 초 차이는 선후를 확정할 수 없다(2절 **(선후 불확실)** 표시).
- `nginx-access.log` 에 upstream 주소 필드가 없어 요청이 API-1 · API-2 중 어디로 갔는지는 로그로 확인 불가. `app.log` 에도 호스트 식별 정보가 없다(`upstream` · `api-2` · API-1 · API-2 주소로 `grep -ciE` → 두 파일 모두 0).
- `deploy-history.md:20` 의 배포 명령에 배포 API 토큰이 평문으로 들어 있다(`Authorization: Bearer [TOKEN]`). 분석 근거로는 쓰지 않았다.
- 그 밖의 로그(nginx error 로그, DB 로그, 배포 파이프라인 로그, 클라이언트 로그)는 제공되지 않았다.

### 1.2 상태 코드 · 오류 건수 (전체 기간)

실행 명령과 결과:

```bash
awk '{print $9}' nginx-access.log | sort | uniq -c
#  19278 200 · 29 400 · 969 404 · 35 409 · 46 500 · 8 502
grep -c ' ERROR ' app.log            # 46  (전부 "unhandled exception on /api/distributions/…")
grep -c 'state conflict' app.log     # 35  (WARN 전부. nginx 409 35건과 같은 수)
grep -c 'Caused by' app.log          # 46  (전부 FetchNotFoundException, Assignment identifier `9`)
grep 'identifier value' app.log | grep -vc '`9`'   # 0
```

- 요청 수: 340분 동안 20,365건, 분당 중앙값 59건. 이상 구간에 눈에 띄는 증감 없음(분 단위 집계 기준).
- nginx 500 건수(46) = app `ERROR` 건수(46). 409(35) = app `state conflict` WARN(35).
- 502 8건은 app.log 에 대응 줄이 없다(앱이 내려가 있던 구간, 2절).

### 1.3 5xx 분 단위 분포

```bash
awk '$9>=500{print substr($4,14,5)}' nginx-access.log | uniq -c
```

| 분 | 500 | 502 | 대상 |
|---|---:|---:|---|
| 09:52 | 3 | | `POST /api/distributions/41/redistribute` |
| 10:31 | 1 | | `GET /api/distributions/41` |
| 11:17 ~ 11:18 | 3 | | `POST /api/distributions/41/redistribute` |
| 13:41 | | 8 | 여러 경로(학생 · 교사 · 헬스체크) |
| 13:46 ~ 14:34 | 39 | | `POST /api/distributions/42/redistribute` 19 · `…/43/redistribute` 20 |

재배포 요청(`POST …/redistribute`)을 배포 재기동(13:41:48) 전후 · 배포 id · 상태 코드로 나눈 결과:

```bash
grep 'POST /api/distributions/[0-9]*/redistribute' nginx-access.log \
  | awk '{t=substr($4,14,8); p=(t<"13:41:48")?"pre":"post"; split($7,a,"/"); print p, a[4], $9}' | sort | uniq -c
```

| 배포 id | 배포 전 | 배포 후 |
|---|---|---|
| 1 · 2 · 3 | 409 31 | 409 4 |
| 5 · 6 · 7 · 8 · 44 | 200 48 | 200 6 |
| 41 | 500 6 | (요청 없음) |
| 42 | 200 5 | **500 19** |
| 43 | 200 13 | **500 20** |

- 배포 41 · 42 · 43 재배포 요청은 모두 교사단말B 한 대에서 왔다(배포 전 200 18 · 500 6, 배포 후 500 39).
- app.log 의 `redistributed distribution … (assignment …, class …)` 줄로 보면 42 · 43 은 `class 1`, 5 · 6 · 7 은 `class 3`, 8 · 44 는 `class 2` 다. 41 의 학급은 로그로 확인 불가(41 요청은 전부 실패해 이 줄이 없다).

### 1.4 분석 구간

- **장애 구간**: 13:41:48(구 버전 종료 시작) ~ 14:34:45(마지막 500). 앞 여유로 배포 승인 13:38 부터, 뒤 여유로 로그 끝 14:39:58 까지 본다.
- **배포 전 구간(09:00 ~ 13:41)도 포함한다**: 배포 전에 이미 같은 예외(`Assignment` identifier `9`)로 500 이 7건 있어, 배포 전후 비교가 필요하다.

## 2. 타임라인

모든 시각 KST. 근거는 원본 `파일:줄번호`. 같은 사건이 두 파일에 있으면 둘 다 적는다.

| 시각 (KST) | 사건 | 근거 |
|---|---|---|
| 09:15:53 | 교사단말B 배포 43 재배포 성공(200). `redistributed distribution 43 (assignment 6, class 1)` | `nginx-access.log:931`, `app.log:59` |
| 09:52:17 | **첫 5xx.** 교사단말B `POST /api/distributions/41/redistribute` → 500. 예외 `JpaObjectRetrievalFailureException: Entity com.example.assignment.Assignment with identifier value 9 does not exist`, 위치 `DistributionService.loadOrThrow(DistributionService.java:65)` ← `redistribute(…:51)` | `nginx-access.log:3150`, `app.log:194`, `app.log:205`, `app.log:224` |
| 09:52:30, 09:52:41 | 같은 요청 재시도 2회, 같은 예외로 500 | `nginx-access.log:3162`, `nginx-access.log:3174`, `app.log:232`, `app.log:270` |
| 10:31:05 | 교사단말B `GET /api/distributions/41` → 500. 같은 예외, 위치 `loadOrThrow` ← `getDistribution(DistributionService.java:32)` | `nginx-access.log:5563`, `app.log:451`, `app.log:462`, `app.log:463` |
| 10:36:16 | 교사단말B 배포 42 재배포 성공(200) — 같은 단말의 42 · 43 재배포는 배포 전 18건 모두 200 | `nginx-access.log:5846`, `app.log:510` |
| 11:17:40 ~ 11:18:02 | 배포 41 재배포 3회 더 500(같은 예외) | `nginx-access.log:8442`, `nginx-access.log:8466`, `nginx-access.log:8468`, `app.log:655`, `app.log:695`, `app.log:733` |
| 13:38 | v1.4.2 배포 승인(***@example.com). 변경: 재배포 응답에 학급 배포 이력(history) 포함, `DistributionController.redistribute` 에서 `DistributionService.listByClass(classId)` 호출 추가 | `deploy-history.md:7`, `deploy-history.md:15`, `deploy-history.md:16`, `deploy-history.md:21` |
| 13:40:05 | v1.4.2 배포 시작(배포자 ***@example.com, 대상 API-1). 방식: 단일 호스트 재기동, 무중단 아님, 예상 중단 10초 안팎 | `deploy-history.md:3`, `deploy-history.md:7`, `deploy-history.md:18` |
| 13:41:20 | 교사단말B 배포 43 재배포 성공(200) — 구 버전에서의 마지막 42 · 43 성공. reason=학생A 요청 — 제출 화면 오류 | `nginx-access.log:16829`, `app.log:1238` |
| 13:41:48.214 | 구 버전 `GracefulShutdown`: Commencing graceful shutdown | `app.log:1240` |
| 13:41:50 | nginx 에 200 응답 3건(헬스체크 · 학생 단말) **(선후 불확실)** — app 의 `Graceful shutdown complete`(13:41:50.144)와 같은 초 | `nginx-access.log:16862` ~ `nginx-access.log:16864`, `app.log:1241` |
| 13:41:50.194 ~ 13:41:50.224 | Hikari 풀 `itembank-pool` 종료 | `app.log:1243`, `app.log:1244` |
| 13:41:51 ~ 13:41:59 | **502 8건.** 학생 단말(10.20.x.x) `GET /api/units/M5-2/items?student=학생B`, 헬스체크(10.20.x.x) `GET /`, `GET /api/distributions/8?student=학생C`, 교사단말C `GET /api/distributions/5` 등, 마지막은 13:41:59 `GET /api/units/M6-1/items?student=학생A` | `nginx-access.log:16865` ~ `nginx-access.log:16872` |
| 13:41:55.902 | v1.4.2 기동 시작(`Starting ItemBankApplication v1.4.2`) | `app.log:1245` |
| 13:41:58.535 | Hikari 풀 기동 완료 | `app.log:1260` |
| 13:42:00.290 ~ 13:42:00.304 | Tomcat 8080 기동, `Started ItemBankApplication in 4.921 seconds` **(선후 불확실)** — 마지막 502(13:41:59)와 1초 차이 | `app.log:1263`, `app.log:1264`, `nginx-access.log:16872` |
| 13:42:01 | 배포 후 첫 200 응답 | `nginx-access.log:16873` |
| 13:42:10 | 교사단말A 배포 7(class 3) 재배포 성공(200) — 신 버전에서 첫 재배포 성공 | `nginx-access.log:16884`, `app.log:1266` |
| 13:42:31 | 배포 완료(이력 기준) | `deploy-history.md:7` |
| 13:46:38 | **배포 후 첫 500.** 교사단말B 배포 42 재배포. app 은 먼저 `redistributed distribution 42 (assignment 4, class 1) reason=보강 수업 후 재배포` 를 남기고(13:46:38.107), 6ms 뒤 같은 예외(identifier `9`)로 실패(13:46:38.113). 위치가 배포 전과 다르다: `DistributionService.listByClass(DistributionService.java:38)` ← `DistributionController.redistribute(DistributionController.java:40)` | `nginx-access.log:17155`, `app.log:1274`, `app.log:1275`, `app.log:1286`, `app.log:1294`, `app.log:1304` |
| 13:46:58, 13:47:08 | 배포 42 재배포 재시도 2회(reason=학생D 결석으로 재배포, reason=null), 매번 `redistributed …` 뒤 500 | `nginx-access.log:17170`, `nginx-access.log:17181`, `app.log:1312`, `app.log:1313`, `app.log:1350`, `app.log:1351` |
| 13:50:00 | 같은 교사단말B 가 배포 8(class 2) 재배포 → 200 | `nginx-access.log:17353`, `app.log:1393` |
| 13:50:12 ~ 13:50:43 | 교사단말B 배포 43(class 1) 재배포 4회, 모두 `redistributed …` 뒤 500 | `nginx-access.log:17365`, `nginx-access.log:17366`, `nginx-access.log:17376`, `nginx-access.log:17400`, `app.log:1395`, `app.log:1396` |
| 13:51:08 | 교사단말A 배포 7(class 3) 재배포 → 200 | `nginx-access.log:17424`, `app.log:1549` |
| 13:52:44 ~ 14:34:45 | 교사단말B 가 배포 42 · 43 재배포를 2 ~ 6분 간격으로 3회 안팎씩 반복, 전부 500(배포 후 합계 42: 19건, 43: 20건). 같은 구간 class 2 · 3 재배포(5 · 7 · 8 · 44)는 200, 배포 1 · 2 · 3 은 409(마감) | `nginx-access.log:17516` ~ `nginx-access.log:20084`, `app.log:1555` ~ `app.log:2887` |
| 14:34:45 | 마지막 500(배포 43 재배포) | `nginx-access.log:20084`, `app.log:2887` |
| 14:39:58 | 로그 끝. 그 사이 5xx 없음. 롤백 기록은 로그로 확인 불가 | `nginx-access.log:20365`, `app.log:2938` |

## 3. 가설과 검증

### 3.1 가설 표

서로 다른 계층에서 세운 가설이다. 판정은 아직 하지 않았다(로그 반증 · 코드 대조 미실시).

| ID | 계층 | 가설 | 참이면 보일 것 | 반증 조건 | 확인 방법 |
|---|---|---|---|---|---|
| H1 | 코드(v1.4.2 변경) | 재배포 응답에 추가된 `listByClass(classId)` 가 학급 전체 배포를 읽다가, 그 학급 안의 깨진 배포(배포 41 → 없는 과제 9)를 만나 예외를 던진다. 재배포 자체는 끝난 뒤라 응답만 500 이 된다. | 배포 후 500 의 스택에 `listByClass` · `DistributionController.redistribute(…:40)` 이 있다. 실패가 특정 학급(class 1)의 재배포에만 몰린다. 실패 직전에 `redistributed …` 줄이 있다. | 배포 후 500 스택에 `listByClass` 가 없다 / class 2 · 3 재배포도 배포 후 500 이 난다 / 배포 전에도 42 · 43 재배포가 500 이다 | app.log 스택 프레임 집계, 배포 id × 전후 × 상태 집계, `DistributionController` · `DistributionService.listByClass` 코드 대조 |
| H2 | DB(데이터 정합성) | DB 에 존재하지 않는 과제(`Assignment` id 9)를 가리키는 배포 행(41)이 배포 이전부터 있다. v1.4.2 와 무관하게 41 을 직접 읽는 요청은 원래 실패했고, 이 데이터가 이번 장애의 전제 조건이다. | 배포 전에도 같은 예외(identifier `9`)가 41 요청에서 난다. 예외 identifier 가 늘 `9` 하나다. | 배포 전에는 identifier `9` 예외가 없다 / identifier 값이 요청마다 다르다 / 배포 41 에 대한 요청이 배포 전 성공한 적이 있다 | app.log 의 identifier 값 · 시각 집계, 41 요청 상태 집계, 스키마(외래키 유무) · 엔티티 매핑 대조. 41 의 학급 · 과제 9 삭제 경위는 DB 조회가 필요 |
| H3 | 인프라(배포 방식) | 단일 호스트 재기동(무중단 아님)으로 앱이 내려간 동안 nginx 가 502 를 냈다. 대기 호스트 API-2 로 넘어가지 않았다. | 502 가 구 버전 종료(13:41:48) ~ 신 버전 기동(13:42:00) 사이에만 있다. 502 대상 경로가 특정 API 에 몰리지 않는다. | 재기동 구간 밖에 502 가 있다 / 502 가 한 경로에만 몰린다 / 재기동 구간에 app 이 요청을 처리한 기록이 있다 | nginx 502 시각 · 경로 집계와 app.log 종료 · 기동 줄 대조. upstream 지정 · API-2 상태는 nginx 설정 필요(로그로 확인 불가) |
| H4 | 트래픽(클라이언트 재시도) | 교사단말B 의 반복 재시도가 몰려 부하나 동시성 문제로 실패가 난다. | 실패 시점에 요청 수가 평소보다 많다. 같은 단말의 다른 요청도 실패한다. 첫 시도는 성공하고 재시도부터 실패한다. | 분당 요청 수가 평소 수준이다 / 같은 시간대 같은 단말의 다른 학급 재배포가 성공한다 / 매 묶음의 첫 시도부터 500 이다 | 분당 요청 수 집계, 교사단말B 의 요청별 상태 · 순서 집계 |
| H5 | 인프라(DB 연결 · 신 버전 기동 상태) | 재기동 후 DB 연결 풀이나 스키마 · 캐시 상태가 비정상이라 DB 를 읽는 요청이 실패한다. | 배포 후 DB 를 읽는 여러 API 가 실패한다. Hikari 타임아웃 · 연결 오류 로그가 있다. | 배포 후 다른 API(문항 · 단원 · 다른 학급 재배포)가 200 이다 / Hikari · 연결 오류 로그가 없다 / 예외가 연결 오류가 아니라 엔티티 미존재다 | app.log 의 Hikari · 연결 관련 줄 grep, 배포 후 경로별 상태 집계 |

- H1 과 H2 는 서로 배타적이지 않다. H2 의 데이터가 있고 H1 의 변경이 그 데이터를 더 넓은 요청(같은 학급의 모든 재배포)에서 읽게 만들었을 가능성을 함께 검증해야 한다.
- 가장 많이 나온 오류(`FetchNotFoundException`, identifier `9`, 46건)는 증상이다. 이것만으로 원인을 정하지 않는다.

### 3.2 이후 단계 (미작성)

- 3.2 로그 반증 · 3.3 코드 · 설정 대조 · 3.4 판정, 4 원인 ~ 9 확인하지 못한 것은 요청 범위 밖이라 작성하지 않았다.

마스킹 적용: IP 별칭 27건(전체 주소 27 · 축약 표기 0 · 꼬리 0) · IP 부분 가림 2건 · 이메일 2건 · 학생 식별자 5건 · 토큰 1건 · 비밀번호 0건

# 과제 배포(레거시 Thymeleaf) — 배포 등록 화면 비즈니스 규칙 후보

대상: `legacy/assignment-thymeleaf/` 배포 등록 화면(`GET /distributions/new`, `POST /distributions`)과 그 화면이 부르는 코드
진입점 · 의존 관계는 [ARCHITECTURE.md](ARCHITECTURE.md), 테이블 · 데이터 흐름은 [ERD.md](ERD.md) 참고. 경로 약어도 두 문서와 같다.

- **코드를 실행해 보지 않았다.** "확실"은 조건문 · SQL 을 읽어 확인했다는 뜻이다.
- "확실" 가운데 "코드에 없음"을 근거로 한 규칙(BR-10 · BR-11 · BR-21 · BR-22)은 `grep` 과 해당 메서드 전체를 읽어 확인했다.
- 범위 밖: 목록 화면 · 재배포(`service/DistributionService.java:72~`) · 과제 목록 화면. 같은 규칙이 범위 밖에 다시 구현된 경우에는 위치만 적었다.

## 1. 먼저 볼 것 (이관할 때 결정이 필요한 항목)

| # | 내용 | 관련 규칙 |
|---|---|---|
| 1 | **마감 검사가 컨트롤러에만 있다.** `DistributionService.distribute` 는 마감을 보지 않는다. 이관할 때 서비스 계층으로 옮길지 정해야 한다 | BR-07, BR-21 |
| 2 | **`'C'`(마감) 상태라도 `due_at` 이 지나지 않았으면 배포된다.** 상태 코드와 마감 시각 가운데 무엇을 기준으로 삼을지 정해야 한다 | BR-10 |
| 3 | **`'X'`(연장)는 스키마 주석에 없는 상태값이다.** 연장 기한 없이 무기한 배포할 수 있다 | BR-09, BR-12 |
| 4 | **목록에 없는 상태값(`'D'` 를 뺀 O · X · C 외 값)도 POST 로 직접 보내면 배포된다.** 화면은 허용 목록, 서버는 금지 목록을 쓴다 | BR-11 |
| 5 | **중복 방지와 id 채번에 DB 제약도 잠금도 없다.** UNIQUE 제약이나 AUTO_INCREMENT 를 도입하려면 스키마를 바꿔야 한다(팀 규칙상 먼저 묻는다) | BR-17, BR-18, BR-19 |
| 6 | **현재 시각이 `2026-09-15 00:00:00` 으로 고정되어 있다.** 시스템 프로퍼티 `app.clock=system` 이 없으면 고정값을 쓴다. characterization 비교 기준 시각도 이 값에 묶인다 | BR-24 |
| 7 | **인증 · 권한 확인이 없다.** 교사와 학급의 소유 관계도 확인하지 않아 누구나 아무 학급에나 배포할 수 있다 | BR-04, BR-22 |

## 2. 영역별 규칙

### 2-1. 화면 표시 (GET /distributions/new)

| ID | 규칙 | 근거 | 근거 코드 | 확신도 | 비고 |
|---|---|---|---|---|---|
| BR-01 | 과제 선택 목록에는 `status` 가 `'O'` · `'X'` · `'C'` 인 과제만 `due_at` 오름차순, 같으면 `id` 오름차순으로 나온다 | `dao/AssignmentDao.java:36-37` | `` + " WHERE a.status IN ('O', 'X', 'C') "<br>+ " ORDER BY a.due_at ASC, a.id ASC"; `` | 확실 | `'D'` 를 포함해 세 값이 아닌 상태는 모두 빠진다. 목록 화면(`:29`)은 `<> 'D'` 를 쓴다(범위 밖) |
| BR-02 | 과제의 `dueAt` 이 현재 시각보다 이르고 상태가 `'X'` 가 아니면 선택지가 비활성(`disabled`)으로 나온다 | `templates/distribution_form.html:16` | `th:disabled="${a.dueAt != null and a.dueAt.isBefore(now) and a.status != 'X'}"` | 확실 | 브라우저 쪽 제한일 뿐이다. 서버는 BR-07 에서 다시 확인한다 |
| BR-03 | 선택지 라벨은 `id. 제목 (단원코드, 마감 MM-dd HH:mm)` 형식이다. 마감이 지났으면 뒤에 `'X'` 는 `[연장]`, 그 밖의 상태는 `[마감]` 을 붙인다 | `templates/distribution_form.html:17-18` | `th:text="${a.id} + '. ' + ${a.title} + ' (' + ${a.unitCode} + ', 마감 ' + ${#temporals.format(a.dueAt, 'MM-dd HH:mm')} + ')'`<br>`+ (${a.dueAt != null and a.dueAt.isBefore(now)} ? (${a.status == 'X'} ? ' [연장]' : ' [마감]') : '')"` | 확실 | 연도를 표시하지 않는다 |
| BR-04 | 학급 선택 목록은 조건 없이 `class` 전체를 `id` 오름차순으로 보여 준다 | `dao/ClassDao.java:21` | `` return jdbc.query("SELECT id, name, teacher_id FROM `class` ORDER BY id ASC", MAPPER); `` | 확실 | 교사별로 거르지 않는다 |
| BR-05 | 학급 선택지 라벨은 `학급명 (teacher_id)` 형식이다 | `templates/distribution_form.html:25` | `th:text="${c.name} + ' (' + ${c.teacherId} + ')'"` | 확실 | |
| BR-06 | 과제 · 학급 select 는 `required` 이고 첫 선택지 값이 빈 문자열이다. 선택하지 않으면 브라우저가 제출을 막는다 | `templates/distribution_form.html:11-12`, `:23-24` | `<select id="assignmentId" name="assignmentId" required>`<br>`<option value="">-- 과제 선택 --</option>` | 확실 | 서버 쪽 처리는 BR-25(추정) |

### 2-2. 등록 검증 (POST /distributions) — 실행 순서대로

| ID | 규칙 | 근거 | 근거 코드 | 확신도 | 비고 |
|---|---|---|---|---|---|
| BR-26 | `assignmentId` 로 과제를 찾지 못하면 "존재하지 않는 과제입니다." flash 와 함께 `/distributions/new` 로 돌아간다 | `web/DistributionController.java:53-56` | `AssignmentRow a = assignmentDao.findById(assignmentId);`<br>`if (a == null) {`<br>`ra.addFlashAttribute("error", "존재하지 않는 과제입니다.");` | 확실 | 0 · 음수 id 도 `WHERE a.id = ?` 결과가 0건이면 이 분기로 온다(`dao/AssignmentDao.java:42-46`). 트랜잭션 밖에서 실행된다 |
| BR-07 | `dueAt` 이 현재 시각보다 이르고 상태가 `'X'` 가 아니면 "마감(yyyy-MM-dd HH:mm)이 지난 과제는 배포할 수 없습니다. [제목]" 으로 거부한다 | `web/DistributionController.java:60-63` | `if (a.getDueAt() != null && a.getDueAt().isBefore(now)) {`<br>`if (!"X".equals(a.getStatus())) {`<br>`ra.addFlashAttribute("error", "마감(" + AppClock.fmt(a.getDueAt()) + ")이 지난 과제는 배포할 수 없습니다. [" + a.getTitle() + "]");` | 확실 | 시각 형식은 `AppClock.FMT_SHORT`(`AppClock.java:12`)를 쓴다 |
| BR-08 | `dueAt` 이 현재 시각과 **같으면** 마감 전으로 보고 통과시킨다 | `web/DistributionController.java:60` | `if (a.getDueAt() != null && a.getDueAt().isBefore(now)) {` | 확실 | `isBefore` 는 엄격한 비교다. 화면(BR-02)도 같은 식이라 같은 시각에는 선택할 수 있다 |
| BR-09 | 상태가 `'X'` 이면 마감이 지나도 BR-07 을 통과한다 | `web/DistributionController.java:61` | `if (!"X".equals(a.getStatus())) {` | 확실 | 스키마 주석(`01-schema.sql:87`)에는 `O` · `C` 만 있다. 연장 기한 컬럼이 없다 |
| BR-10 | 상태가 `'C'`(마감)여도 `dueAt` 이 지나지 않았으면 배포된다. 컨트롤러와 서비스 어디에도 `'C'` 분기가 없다 | `web/DistributionController.java:60-61`, `service/DistributionService.java:56` | `if (a.getDueAt() != null && a.getDueAt().isBefore(now)) {`<br>`if ("D".equals(a.getStatus())) {` | 확실 | 이 흐름의 상태 비교는 위 두 곳과 템플릿(:16, :18)뿐이다(grep 확인). 화면 목록에도 `'C'` 가 포함된다(BR-01) |
| BR-27 | 서비스에서 과제를 다시 찾아 없으면 `IllegalArgumentException`("존재하지 않는 과제입니다. (id=…)")을 던진다 | `service/DistributionService.java:48-51` | `AssignmentRow a = assignmentDao.findById(assignmentId);`<br>`if (a == null) {`<br>`throw new IllegalArgumentException("존재하지 않는 과제입니다. (id=" + assignmentId + ")");` | 확실 | BR-26 을 통과한 뒤라 평소에는 도달하지 않는다. 두 조회 사이에 과제가 삭제되면 도달한다(추정). 메시지는 컨트롤러가 flash 로 그대로 보여 준다(`web/DistributionController.java:69-70`) |
| BR-14 | 학급을 찾지 못하면 "존재하지 않는 학급입니다. (id=…)" 로 거부한다 | `service/DistributionService.java:52-55` | `ClassRow c = classDao.findById(classId);`<br>`if (c == null) {`<br>`throw new IllegalArgumentException("존재하지 않는 학급입니다. (id=" + classId + ")");` | 확실 | 학급 검사는 과제 마감 검사(BR-07) **뒤**, 삭제 검사(BR-12) **앞**에 온다 |
| BR-12 | 상태가 `'D'` 인 과제는 "삭제된 과제는 배포할 수 없습니다." 로 거부한다 | `service/DistributionService.java:56-58` | `if ("D".equals(a.getStatus())) {`<br>`throw new IllegalStateException("삭제된 과제는 배포할 수 없습니다.");` | 확실 | 컨트롤러의 마감 검사가 먼저 돌므로, 마감이 지난 `'D'` 과제는 이 메시지가 아니라 BR-07 의 마감 메시지를 받는다 |
| BR-11 | `'O'` · `'X'` · `'C'` · `'D'` 가 아닌 상태값(예: 스키마에 없는 값)의 과제는 선택 목록에 없지만 POST 로 id 를 직접 보내면 배포된다 | `dao/AssignmentDao.java:36`, `service/DistributionService.java:56` | `+ " WHERE a.status IN ('O', 'X', 'C') "`<br>`if ("D".equals(a.getStatus())) {` | 확실 | 화면은 허용 목록, 서버는 `'D'` 만 막는 금지 목록이다. 이런 값이 실제 데이터에 있는지는 미확인(시드에는 없다, `02-seed.sql:120-125`) |
| BR-13 | 서버의 상태 비교는 대소문자를 구분하는 정확 일치(`String.equals`)다 | `web/DistributionController.java:61`, `service/DistributionService.java:56` | `if (!"X".equals(a.getStatus())) {`<br>`if ("D".equals(a.getStatus())) {` | 확실 | SQL 쪽(BR-01)은 컬럼 콜레이션 `utf8mb4_unicode_ci`(`01-schema.sql:90`)로 비교한다. 소문자 값이 있을 때 SQL 과 Java 결과가 달라지는지는 **미확인**(6절) |
| BR-15 | 같은 `(assignment_id, class_id)` 조합의 `distribution` 행이 1건이라도 있으면 "이미 이 학급에 배포된 과제입니다. 다시 내려면 배포 목록에서 재배포를 사용하세요." 로 거부한다 | `service/DistributionService.java:59-61`, `dao/DistributionDao.java:48-50` | `if (distributionDao.countByAssignmentAndClass(assignmentId, classId) > 0) {`<br>`"SELECT COUNT(*) FROM distribution WHERE assignment_id = ? AND class_id = ?",` | 확실 | `redistributed` 값과 과제 상태에 관계없이 센다. 시드에는 이미 같은 조합이 2건 있다(`02-seed.sql:133-134`) |

### 2-3. 저장

| ID | 규칙 | 근거 | 근거 코드 | 확신도 | 비고 |
|---|---|---|---|---|---|
| BR-16 | 새 배포 id 는 `distribution` 전체의 `MAX(id)` + 1 이다. 테이블이 비어 있으면 1 이다 | `dao/DistributionDao.java:55-56` | `Long max = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM distribution", Long.class);`<br>`return (max == null ? 0L : max.longValue()) + 1L;` | 확실 | 스키마 `id` 는 AUTO_INCREMENT 가 아니다(`01-schema.sql:93`) |
| BR-17 | 중복 확인(COUNT)과 채번(MAX)은 모두 잠금 절(`FOR UPDATE` · `LOCK IN SHARE MODE`)이 없는 일반 SELECT 다. `(assignment_id, class_id)` 에는 UNIQUE 제약이 없다 | `dao/DistributionDao.java:46`, `:49`, `:55`, `01-schema.sql:99-100` | `// 같은 학급 + 같은 과제 는 한 건만 존재해야 한다 (UNIQUE 제약은 없음, 여기서 막는다)`<br>`"SELECT COUNT(*) FROM distribution WHERE assignment_id = ? AND class_id = ?",`<br>`"SELECT COALESCE(MAX(id), 0) FROM distribution"` | 확실 | 잠금이 **없다**는 것만 확실하다. 동시 요청 결과는 BR-18 · BR-19 |
| BR-18 | 두 요청이 동시에 같은 `MAX(id)` 를 읽으면 두 번째 INSERT 가 PK 충돌로 실패하고 "DB 오류로 배포에 실패했습니다." 를 받는다 | `dao/DistributionDao.java:55`, `web/DistributionController.java:72-74` | `catch (DataAccessException e) {`<br>`ra.addFlashAttribute("error", "DB 오류로 배포에 실패했습니다.");` | 추정 | BR-17 과 `PRIMARY KEY (id)`(`01-schema.sql:98`)에서 추론했다. 격리 수준 설정이 없어 DB 기본값에 따른다. 실행하지 않았다 |
| BR-19 | 같은 과제 · 학급으로 동시에 들어온 두 요청이 모두 COUNT=0 을 읽고 서로 다른 id 를 받으면 중복 배포 2건이 저장될 수 있다 | `service/DistributionService.java:59-63` | `if (distributionDao.countByAssignmentAndClass(assignmentId, classId) > 0) {`<br>`long id = distributionDao.nextId();`<br>`int n = distributionDao.insert(id, assignmentId, classId, AppClock.now());` | 추정 | BR-17 에서 추론했다. 실제 경합이 가능한지는 격리 수준과 타이밍에 달려 있어 확인하지 않았다 |
| BR-20 | `distributed_at` 은 `AppClock.now()`, `redistributed` 는 `0` 으로 INSERT 한다 | `service/DistributionService.java:63`, `dao/DistributionDao.java:61` | `int n = distributionDao.insert(id, assignmentId, classId, AppClock.now());`<br>`"INSERT INTO distribution (id, assignment_id, class_id, distributed_at, redistributed) VALUES (?, ?, ?, ?, 0)",` | 확실 | |
| BR-23 | INSERT 영향 행 수가 1 이 아니면 "배포 저장에 실패했습니다." 로 거부한다 | `service/DistributionService.java:64-66` | `if (n != 1) {`<br>`throw new IllegalStateException("배포 저장에 실패했습니다.");` | 확실 | |
| BR-28 | 성공하면 "배포가 등록되었습니다. (배포 #id)" flash 와 함께 목록(`/distributions`)으로 간다 | `web/DistributionController.java:68`, `:76` | `ra.addFlashAttribute("message", "배포가 등록되었습니다. (배포 #" + id + ")");`<br>`return "redirect:/distributions";` | 확실 | |

### 2-4. 시각 · 오류 · 권한

| ID | 규칙 | 근거 | 근거 코드 | 확신도 | 비고 |
|---|---|---|---|---|---|
| BR-24 | 시스템 프로퍼티 `app.clock` 이 정확히 `"system"` 일 때만 실제 시각(나노초 0)을 쓰고, 그 밖에는 `2026-09-15 00:00:00` 고정값을 쓴다 | `AppClock.java:9`, `:15-19` | `private static final String FIXED = "2026-09-15 00:00:00";`<br>`String sys = System.getProperty("app.clock");`<br>`if (sys != null && sys.equals("system")) {`<br>`return LocalDateTime.now().withNano(0);`<br>`return LocalDateTime.parse(FIXED, FMT);` | 확실 | `Dockerfile:11` ENTRYPOINT 와 `docker-compose.yml:55-63` 환경변수에 `app.clock` 설정이 없다. 따라서 compose 로 띄우면 고정 시각이다. 운영 기동 설정은 미확인 |
| BR-29 | 화면의 비활성 판정(GET)과 서버 거부(POST)는 `AppClock.now()` 를 각각 따로 호출한 값을 쓴다 | `web/DistributionController.java:44`, `:58` | `model.addAttribute("now", AppClock.now());`<br>`LocalDateTime now = AppClock.now();` | 추정 | 고정 시각이면 결과가 같다. `system` 모드에서는 화면을 연 뒤 마감이 지나면, 선택할 수 있던 과제가 제출할 때 거부된다(추론) |
| BR-25 | `assignmentId` · `classId` 가 비었거나 숫자가 아니면 앱 메시지 없이 Spring 기본 오류 응답(400 계열)이 나간다 | `web/DistributionController.java:50-51`, `web/DbErrorAdvice.java:11` | `public String create(@RequestParam("assignmentId") long assignmentId,`<br>`@RequestParam("classId") long classId,`<br>`@ExceptionHandler(DataAccessException.class)` | 추정 | 파라미터가 원시형 `long` 이고, 변환 예외를 처리하는 핸들러가 `DataAccessException` 전용 하나뿐이라는 점에서 추론했다. 정확한 상태 코드와 화면은 실행하지 않아 미확인 |
| BR-30 | 컨트롤러 try 밖의 DB 예외(GET 목록 조회, POST 첫 과제 조회)는 `db_error` 화면에 DB 예외 원문을 그대로 보여 주고 `System.out` 에 출력한다 | `web/DbErrorAdvice.java:13`, `:15`, `templates/db_error.html:8` | `System.out.println("[DB-ERROR] " + e.getClass().getSimpleName() + " : " + e.getMostSpecificCause().getMessage());`<br>`model.addAttribute("detail", e.getMostSpecificCause().getMessage());`<br>`<p th:text="${detail}"></p>` | 확실 | 서비스 안의 DB 예외는 고정 문구로 바뀐다(BR-18 근거 코드). 같은 DB 오류라도 발생 위치에 따라 보이는 결과가 다르다 |
| BR-22 | 배포 요청자의 인증 · 권한과, 학급 담당 교사(`teacher_id`)와 요청자의 관계를 확인하지 않는다 | `build.gradle:24-30`, `web/DistributionController.java:49-52` | `implementation 'org.springframework.boot:spring-boot-starter-web'`<br>`@PostMapping("/distributions")`<br>`public String create(@RequestParam("assignmentId") long assignmentId,` | 확실 | 의존성에 security 가 없고, 모듈 전체에 `Security` · `Interceptor` · `Filter` 가 없다(grep 확인). 요청 파라미터도 두 개뿐이다 |
| BR-21 | 마감 규칙(BR-07)은 서비스에 없다. `distribute` 를 다른 곳에서 부르면 마감 검사 없이 배포된다 | `service/DistributionService.java:47-67` | `public long distribute(long assignmentId, long classId) {` | 확실 | 현재 `distribute` 를 부르는 곳은 `web/DistributionController.java:67` 하나뿐이다(grep 확인) |

## 3. 같은 규칙의 다른 구현

| 규칙 | 구현 위치 | 차이 |
|---|---|---|
| 마감 판정(`dueAt < now` 이고 상태 ≠ `'X'`) | `templates/distribution_form.html:16`(비활성), `:18`(라벨), `web/DistributionController.java:60-61`(거부), `templates/assignments.html:21`(`past` 표시, 범위 밖) | 식은 같다. 서비스에는 없다(BR-21) |
| 과제 존재 확인 | `web/DistributionController.java:54-55`, `service/DistributionService.java:49-50` | 메시지가 다르다. 컨트롤러는 id 를 넣지 않고 서비스는 `(id=…)` 를 넣는다 |
| 배포 대상 과제 상태 | `dao/AssignmentDao.java:36`(`IN ('O','X','C')` 허용 목록), `service/DistributionService.java:56`(`'D'` 금지 목록), `dao/AssignmentDao.java:29`(`<> 'D'`, 과제 목록용, 범위 밖) | 허용 목록과 금지 목록이 섞여 있다(BR-11) |
| DB 오류 처리 | `web/DistributionController.java:72-74`(고정 문구 flash), `web/DbErrorAdvice.java:11-17`(오류 화면 + 원문) | BR-30 |
| 상태별 분기 | `service/DistributionService.java:182-198` (`case "O"` · `"X"` · `"C"` · `"D"`, 재배포, 범위 밖) | 등록 화면에는 상태별 분기가 없다. 재배포와 비교가 필요하다 |

## 4. 매직 넘버 · 고정값

| 값 | 위치 | 의미 |
|---|---|---|
| `"2026-09-15 00:00:00"` | `AppClock.java:9` | 고정 현재 시각(BR-24) |
| `"system"` | `AppClock.java:16` | 실제 시각으로 바꾸는 프로퍼티 값 |
| `'O'` · `'X'` · `'C'` · `'D'` | `dao/AssignmentDao.java:36`, `web/DistributionController.java:61`, `service/DistributionService.java:56` | 과제 상태 코드. 스키마 주석에는 `O` · `C` 만 있다(`01-schema.sql:87`) |
| `0` (INSERT 리터럴) | `dao/DistributionDao.java:61` | `redistributed` 초기값 |
| `0` · `+ 1L` | `dao/DistributionDao.java:55-56` | 채번 시작값과 증가분 |
| `> 0` | `service/DistributionService.java:59` | 기존 배포 1건 이상이면 중복 |
| `!= 1` | `service/DistributionService.java:64` | INSERT 성공 판정 |
| `'MM-dd HH:mm'` | `templates/distribution_form.html:17` | 선택지 마감 표시(연도 없음) |
| `yyyy-MM-dd HH:mm` | `AppClock.java:12` | 오류 메시지의 마감 표시 |
| `maximum-pool-size=4`, `connection-timeout=5000` | `application.properties:6-7` | Hikari 풀 4, 대기 5초. 현행 `modern/api` 는 5 · 3초다(CLAUDE.md). 이관할 때 값이 달라진다 |

## 5. 주석만 있고 코드로 확인되지 않은 것

| 주석 | 위치 | 코드와의 관계 |
|---|---|---|
| "2024-03 QA 기간 중 임시로 고정. 운영 반영 전 LocalDateTime.now() 로 되돌릴 것 (아직 안 되돌림)" | `AppClock.java:8` | 주석은 2024-03 이라고 하지만 고정값은 `2026-09-15`(`:9`)다. 운영에서 되돌렸는지는 코드로 확인할 수 없다 |
| "같은 학급 + 같은 과제 는 한 건만 존재해야 한다" | `dao/DistributionDao.java:46` | 등록 경로는 COUNT 로 막는다(BR-15). 시드에는 같은 조합이 2건 있다(`02-seed.sql:133-134`, 재배포 이력). "한 건만"은 테이블 전체의 불변식이 아니다 |
| "O=진행 C=마감" | `01-schema.sql:87` | 코드는 `X` · `D` 도 쓴다. `X` 의 의미("연장")는 주석(`web/DistributionController.java:59`, `templates/distribution_form.html:13`)과 라벨 문구(`:18`)에만 있다 |

## 6. 미확인

- `utf8mb4_unicode_ci` 컬럼에 소문자 상태값(`'x'`, `'d'`)이 있을 때 SQL `IN` 과 Java `equals` 의 결과가 달라지는지(BR-13). 이런 데이터가 있는지도 확인하지 않았다.
- 비숫자 · 빈 값 · `long` 범위 초과 파라미터를 보냈을 때의 실제 상태 코드와 화면(BR-25).
- `INT` 범위(2147483647)를 넘지만 `long` 안에 드는 id 를 `WHERE a.id = ?` 로 비교했을 때의 결과. 0건으로 BR-26 에 갈 것 같지만 DB 비교 동작을 확인하지 않았다.
- 트랜잭션 격리 수준. 설정이 없어(`application.properties` 전체) DB 기본값에 따른다. 이 때문에 BR-18 · BR-19 의 실제 발생 조건도 확인하지 못했다.
- 운영 기동 시 `-Dapp.clock=system` 을 주는지(BR-24). 저장소 안에는 그런 설정이 없다.
- 두 조회 사이(`web/DistributionController.java:53` → `service/DistributionService.java:48`)에 과제 상태가 바뀌는 경우의 동작(BR-27).

## 7. 사람이 표본 점검할 규칙 3개

| # | 유형 | ID | 확인할 파일:줄번호 | 확인 방법 |
|---|---|---|---|---|
| 1 | 가장 그럴듯한 규칙 | BR-15 | `service/DistributionService.java:59-61`, `dao/DistributionDao.java:47-51` | `(assignmentId=2, classId=1)` 처럼 시드에 있는 조합(`02-seed.sql:133-134`)으로 POST 하면 이 분기에서 거부되는지 본다. 이어서 **시드에 없는 조합**이면서 마감 전인 과제(예: 과제 4 · 5 · 6 과 배포 이력이 없는 학급)로 BR-07 · BR-12 를 모두 통과해 COUNT=0 으로 INSERT 에 이르는지 따라간다. 그리고 "같은 과제 · 학급은 한 건"이라는 전제가 재배포 경로(`:72~`)에서 깨지는지 함께 확인한다 |
| 2 | 경계값 · 변환 | BR-08 | `web/DistributionController.java:58-61`, `AppClock.java:14-19`, `templates/distribution_form.html:16` | 고정 시각 `2026-09-15 00:00:00` 기준으로 `due_at` 이 ① 같은 시각 ② 1초 전 ③ 1초 뒤인 과제를 두고 `isBefore` 분기를 각각 따라간다. `due_at` 이 `DATETIME` 이라 초 미만이 없다는 점, `system` 모드의 `withNano(0)` 도 함께 본다 |
| 3 | 먼저 볼 것 | BR-10 | `web/DistributionController.java:60-61`, `service/DistributionService.java:56-61`, `dao/AssignmentDao.java:36` | `status='C'`, `due_at` 이 고정 시각 이후인 과제를 가정하고 GET 목록(`IN (...)` 포함)과 선택지 활성 여부(`:16`), POST 의 모든 분기(`:54` → `:60` → `:52` → `:56` → `:59`)를 따라가 INSERT 까지 가는지 확인한다. 이것이 의도된 동작인지는 업무 담당자에게 확인해야 한다 |

## 검증 이력

(아직 없음)

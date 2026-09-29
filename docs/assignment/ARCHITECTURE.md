# 과제 배포(레거시 Thymeleaf) — 배포 등록 화면 아키텍처

대상: `legacy/assignment-thymeleaf/` 중 **배포 등록 화면**(`/distributions/new`)과 그 화면이 부르는 코드
경로 약어: `web/` · `service/` · `dao/` · `model/` = `src/main/java/com/example/assign/…`, `templates/` = `src/main/resources/templates/`

- 코드를 실행해 보지 않았다. 모든 내용은 소스를 읽어 확인한 것이다.
- 읽은 파일: `templates/distribution_form.html`, `templates/layout.html`, `templates/db_error.html`,
  `web/DistributionController.java`, `web/DbErrorAdvice.java`, `service/DistributionService.java:1-68`,
  `dao/DistributionDao.java`, `dao/AssignmentDao.java`, `dao/ClassDao.java`, `model/AssignmentRow.java`,
  `model/ClassRow.java`, `AppClock.java`, `application.properties`, `Dockerfile`, `docker-compose.yml:50-66`.

## 1. 진입점

| URL / 호출명 | 파일:줄번호 | 역할 |
|---|---|---|
| `GET /distributions/new` | `web/DistributionController.java:40-47` | 선택 가능한 과제 목록 · 전체 학급 목록 · 현재 시각(`now`)을 모델에 담아 `distribution_form` 을 렌더링한다 |
| `POST /distributions` (폼 `assignmentId`, `classId`) | `web/DistributionController.java:49-77` | 과제 존재 · 마감 여부를 먼저 확인한 뒤 `DistributionService.distribute` 를 호출한다. 성공하면 `/distributions` 로, 실패하면 `/distributions/new` 로 리다이렉트하고 flash 로 메시지를 전달한다 |
| `DataAccessException` 처리 | `web/DbErrorAdvice.java:11-17` | 컨트롤러가 잡지 않은 DB 예외를 받아 `db_error` 화면을 보여준다(`@ControllerAdvice`, 전역) |

화면 구성 요소

| 템플릿 | 파일:줄번호 | 역할 |
|---|---|---|
| `distribution_form.html` | `templates/distribution_form.html:8-31` | `POST /distributions` 로 보내는 폼. 과제 select(:11-19), 학급 select(:23-26), 배포 버튼(:29) |
| `layout.html` 조각 | `templates/layout.html:3`, `:23`, `:28-34`, `:35` | head · nav · flash(`message`/`error`/`warnings`) · footer |
| `db_error.html` | `templates/db_error.html:6-8` | DB 오류 안내 문구와 DB 예외 원문(`detail`)을 출력한다 |

## 범위 밖 (이름만 적음)
- `GET /distributions`(`web/DistributionController.java:32`): 등록에 성공한 뒤 리다이렉트되는 목록 화면
- `POST /distributions/{id}/redistribute`(`web/DistributionController.java:79`), `DistributionService.redistribute`(`service/DistributionService.java:72~`)
- `web/AssignmentController.java`(`/`, `/assignments`), `templates/assignments.html`, `templates/distributions.html`
- `DistributionService.listAll` · `get`, `DistributionDao.findAllForList` · `findById` · `markRedistributed` · `findSubmissions`, `AssignmentDao.findAllForList`
- `model/DistributionRow` · `RedistributeResult` · `SubmissionRow`, MS-SQL, 빌드 설정

## 2. 의존 관계 (호출문이 실제로 있는 방향만)

### 주입 관계 (생성자)
- `DistributionController` ← `DistributionService`, `AssignmentDao`, `ClassDao` (`web/DistributionController.java:26-30`)
  - 컨트롤러가 DAO 를 **직접** 주입받아 호출한다(서비스를 거치지 않는 경로가 있다).
- `DistributionService` ← `DistributionDao`, `AssignmentDao`, `ClassDao` (`service/DistributionService.java:32-36`)
- `DistributionDao` · `AssignmentDao` · `ClassDao` ← `JdbcTemplate` (`dao/DistributionDao.java:19`, `dao/AssignmentDao.java:17`, `dao/ClassDao.java:16`)
- `AppClock` 은 주입되지 않는 static 유틸이다(`AppClock.java:14`).

### GET /distributions/new
```
DistributionController.form            web/DistributionController.java:41
 ├─→ AssignmentDao.findAllForSelect     :42 → dao/AssignmentDao.java:34-39  (SELECT assignment LEFT JOIN unit)
 ├─→ ClassDao.findAll                   :43 → dao/ClassDao.java:20-22       (SELECT `class`)
 ├─→ AppClock.now                       :44 → AppClock.java:14-20
 └─→ view "distribution_form"           :46 → templates/distribution_form.html
                                              └─→ layout :: head/nav/flash/footer (:3, :5, :7, :32)
```

### POST /distributions
```
DistributionController.create                       web/DistributionController.java:50
 ├─→ AssignmentDao.findById                          :53 → dao/AssignmentDao.java:41-48   (try 밖)
 ├─→ AppClock.now / AppClock.fmt                     :58, :62 → AppClock.java:14, :22
 └─→ DistributionService.distribute (@Transactional) :67 → service/DistributionService.java:46-68
      ├─→ AssignmentDao.findById                     :48 → dao/AssignmentDao.java:41-48   (같은 조회를 한 번 더)
      ├─→ ClassDao.findById                          :52 → dao/ClassDao.java:24-30
      ├─→ DistributionDao.countByAssignmentAndClass  :59 → dao/DistributionDao.java:47-52
      ├─→ DistributionDao.nextId                     :62 → dao/DistributionDao.java:54-57
      ├─→ AppClock.now                               :63 → AppClock.java:14-20
      └─→ DistributionDao.insert                     :63 → dao/DistributionDao.java:59-63
```

### 예외 흐름
| 발생 위치 | 예외 | 받는 곳 | 결과 |
|---|---|---|---|
| `service/DistributionService.java:50`, `:54` | `IllegalArgumentException` | `web/DistributionController.java:69-71` | `error` flash 후 `/distributions/new` |
| `service/DistributionService.java:57`, `:60`, `:65` | `IllegalStateException` | `web/DistributionController.java:69-71` | 위와 같음 |
| `distribute` 안의 DAO 호출 | `DataAccessException` | `web/DistributionController.java:72-74` | 고정 문구 "DB 오류로 배포에 실패했습니다." |
| `web/DistributionController.java:42`, `:43`, `:53` (try 밖 DAO 호출) | `DataAccessException` | `web/DbErrorAdvice.java:11-17` | `db_error` 화면, `System.out` 출력(:13) |

### 트랜잭션 경계
- `@Transactional` 은 `DistributionService.distribute` 에만 있다(`service/DistributionService.java:46`).
  컨트롤러의 과제 조회와 마감 확인(`web/DistributionController.java:53-65`)은 이 트랜잭션 **밖**에서 실행된다.
- `spring-boot-starter-jdbc`(`build.gradle:27`)만 쓰므로 트랜잭션 관리자는 자동 구성에 맡긴다. 별도 설정 클래스는 없다(`AssignApplication.java:6-12`).

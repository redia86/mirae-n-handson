# 과제 배포(레거시 Thymeleaf) — 배포 등록 화면 데이터 흐름

대상: `legacy/assignment-thymeleaf/` 배포 등록 화면(`GET /distributions/new`, `POST /distributions`)
스키마: `db/mariadb/init/01-schema.sql`, 시드: `db/mariadb/init/02-seed.sql`. 경로 약어는 [ARCHITECTURE.md](ARCHITECTURE.md)와 같다.

- 코드를 실행해 보지 않았다. DB 에 직접 쿼리하지도 않았다.

## 1. 이 화면이 건드리는 테이블

| 테이블 | 스키마 | 이 화면에서의 용도 |
|---|---|---|
| `assignment` | `01-schema.sql:82-90` | 과제 선택 목록과 존재 · 상태 · 마감 확인 |
| `unit` | `01-schema.sql:11-18` | 과제 선택지에 단원 코드를 표시하려고 LEFT JOIN 한다 |
| `class` | `01-schema.sql:75-80` | 학급 선택 목록과 존재 확인 |
| `distribution` | `01-schema.sql:92-103` | 중복 확인, 다음 id 계산, 새 배포 INSERT. `AssignmentDao` 는 학급 수를 셀 때 서브쿼리로 읽는다 |

이 화면은 `submission`(`01-schema.sql:105-115`), `item` · `tag` · `item_tag` · `v_item_public` 을 쓰지 않는다. 범위 밖이다.

## 2. 읽기 · 쓰기 위치

| 파일:줄번호 | 종류 | 테이블 | 조건 / 내용 | 호출 경로 |
|---|---|---|---|---|
| `dao/AssignmentDao.java:21-24`, `:34-38` | SELECT | `assignment` LEFT JOIN `unit`, 서브쿼리 `distribution` | `WHERE a.status IN ('O', 'X', 'C') ORDER BY a.due_at ASC, a.id ASC` | GET form(`web/DistributionController.java:42`) |
| `dao/ClassDao.java:21` | SELECT | `class` | 조건 없음, `ORDER BY id ASC` | GET form(`:43`) |
| `dao/AssignmentDao.java:41-47` | SELECT | `assignment` LEFT JOIN `unit`, 서브쿼리 `distribution` | `WHERE a.id = ?` | POST 컨트롤러(`:53`), 서비스(`service/DistributionService.java:48`). 두 번 호출된다 |
| `dao/ClassDao.java:25` | SELECT | `class` | `WHERE id = ?` | 서비스 `:52` |
| `dao/DistributionDao.java:48-50` | SELECT COUNT(*) | `distribution` | `WHERE assignment_id = ? AND class_id = ?` | 서비스 `:59` |
| `dao/DistributionDao.java:55` | SELECT COALESCE(MAX(id), 0) | `distribution` | 전체 테이블, 잠금 절 없음 | 서비스 `:62` |
| `dao/DistributionDao.java:60-62` | **INSERT** | `distribution` | `(id, assignment_id, class_id, distributed_at, redistributed) VALUES (?, ?, ?, ?, 0)` | 서비스 `:63` |

- 이 화면에는 UPDATE · DELETE 가 없다. `DistributionDao.java:66` 의 `UPDATE` 는 재배포용이라 범위 밖이다.
- 쓰기는 `distribution` INSERT 하나뿐이고 `@Transactional` 메서드 안에서 실행된다(`service/DistributionService.java:46`).

## 3. 스키마와 코드의 차이

| 항목 | 스키마 | 코드 |
|---|---|---|
| `assignment.status` 값 | 주석에 `O=진행 C=마감` 두 가지만 있다(`01-schema.sql:87`). CHECK 제약은 없다 | `'X'`(연장)를 쓴다(`dao/AssignmentDao.java:36`, `web/DistributionController.java:61`). `'D'`(삭제)도 쓴다(`service/DistributionService.java:56`, `dao/AssignmentDao.java:29`) |
| `(assignment_id, class_id)` 중복 | UNIQUE 제약이 없고 일반 인덱스만 있다(`01-schema.sql:99-100`) | COUNT 로 막는다(`dao/DistributionDao.java:46-50`). 주석: "UNIQUE 제약은 없음, 여기서 막는다" |
| `distribution.id` | `INT NOT NULL` PK, AUTO_INCREMENT 가 아니다(`01-schema.sql:93`, `:98`) | `MAX(id)+1` 로 만든다(`dao/DistributionDao.java:55-56`) |
| `assignment.due_at` | `NOT NULL`(`01-schema.sql:86`) | null 을 검사하는 분기가 있다(`web/DistributionController.java:60`, `templates/distribution_form.html:16`). 스키마대로면 도달하지 않는다 |
| `id` 타입 | `INT` | Java `long` 으로 받는다(`web/DistributionController.java:50-51`) |
| `redistributed` | `DEFAULT 0`(`01-schema.sql:97`) | INSERT 할 때 리터럴 `0` 을 넣는다(`dao/DistributionDao.java:61`) |

- 시드의 `distribution` 3 · 4번은 같은 `(assignment_id=2, class_id=1)` 이다(`02-seed.sql:133-134`, 주석 `:128` "4번은 2번 과제를 1반에 재배포한 이력"). 그래서 이 조합은 등록 화면에서 이미 배포된 것으로 막힌다.
- 시드 `assignment` 에는 `'X'` · `'D'` 상태가 없다(`02-seed.sql:120-125`). 연장 · 삭제 분기는 시드만으로는 확인할 수 없다.

## 4. ERD

```mermaid
erDiagram
    unit ||--o{ assignment : "unit_id (fk_assignment_unit)"
    assignment ||--o{ distribution : "assignment_id (fk_distribution_assignment)"
    class ||--o{ distribution : "class_id (fk_distribution_class)"

    unit {
        INT id PK
        VARCHAR code UK "uk_unit_code"
        VARCHAR name
        TINYINT grade
    }
    assignment {
        INT id PK
        VARCHAR title
        INT unit_id FK
        DATETIME due_at "NOT NULL"
        CHAR status "DEFAULT O. 코드는 O X C D 사용"
    }
    class {
        INT id PK
        VARCHAR name
        VARCHAR teacher_id
    }
    distribution {
        INT id PK "MAX+1 로 채번"
        INT assignment_id FK
        INT class_id FK
        DATETIME distributed_at "AppClock.now()"
        TINYINT redistributed "INSERT 시 0"
    }
```

(`assignment_id`, `class_id`) 조합에는 UNIQUE 제약이 없다. 이 관계는 1:N 이고 중복은 코드로만 막는다.

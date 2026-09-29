# CLAUDE.md

Claude Code 심화 과정 실습 저장소. 더미 에듀테크 도메인(문항 은행 · 과제 배포 · 성적 집계)의 레거시(`legacy/`)를 현행 스택(`modern/`)으로 이관한다.

- 답변과 문서는 한국어로 쓴다.
- `legacy/` 는 분석 · 이관 대상이며 허락 없이 수정하지 않는다.
- Claude Code 는 저장소 루트에서 실행한다. 기준 환경: WSL2 bash, JDK 21, Node.js 22.5+, Docker Compose.

## 1. 빌드 · 테스트 명령

```bash
# modern/api — 테스트는 H2(MariaDB 모드, src/test/resources/application-test.yml)라 DB 없이 통과한다
cd modern/api && ./gradlew test
cd modern/api && ./gradlew test --tests 'com.example.item.ItemServiceTest'   # 단일 테스트
cd modern/api && ./gradlew build                                             # 테스트 포함 전체 빌드
cd modern/api && ./gradlew bootRun     # :8080. 먼저 docker compose --profile modern up -d

# modern/web
cd modern/web && npm ci                                                      # 의존성 설치
cd modern/web && npm run lint && npm run typecheck && npm test               # 검증 3종
cd modern/web && npx vitest run src/components/ItemTable.test.tsx            # 단일 테스트
cd modern/web && npm run build
cd modern/web && npm run dev           # :5173
```

- 의존성 설치는 `npm ci` 만 쓴다. `npm install` 은 `package-lock.json` 을 바꾼다.
- `modern/api` 를 고쳤으면 `./gradlew test`, `modern/web` 을 고쳤으면 검증 3종을 실행하고 통과 · 실패 수를 답변에 적는다. 실행하지 않았으면 "실행하지 않음"이라고 쓴다. 하나라도 실패하면 "완료"라고 쓰지 않는다.
- 테스트는 실제 MariaDB 나 `localhost:8080` 에 붙지 않는다.

## 2. 코딩 컨벤션

### modern/api (`com.example`, Spring Boot 3.3 · Java 21)
- 패키지는 도메인 단위(`item/`, `assignment/`) + `common/`, `config/`. 호출 순서는 Controller → Service → Repository → 엔티티.
- 컨트롤러 생성자에 `*Repository` 타입을 주입하지 않고, 컨트롤러에 SQL 문자열이 없다.
- 서비스는 다른 도메인 패키지의 `*Repository` 를 주입하지 않는다. 필요하면 그 도메인의 Service 를 주입한다.
- 컨트롤러 메서드는 엔티티 타입을 반환하지 않는다. 응답은 record DTO(`ItemResponse` 등)로 낸다.
- 예외 → HTTP 변환은 `common/GlobalExceptionHandler` 에서만 한다. 컨트롤러 메서드 안에 try-catch 가 없다. 없는 리소스는 `NotFoundException`(404).
- `catch` 블록은 비어 있지 않고, 로그를 남긴 뒤 다시 던지거나 다른 예외로 감싸 던진다.
- 로그는 SLF4J(`org.slf4j.Logger`)로만 남긴다. `System.out` · `System.err` · `printStackTrace()` 가 없다.
- 로그 인자에 학생 식별자(`STU-…`) · 이메일 · 토큰 값을 넣지 않는다.
- `item/` · `assignment/` 에서 현재 시각은 주입받은 `Clock`(`common/ClockConfig`)으로 구한다. `now()` 를 인자 없이 호출하지 않는다.
- `@Transactional` 메서드 안에서 외부 HTTP 를 호출하지 않는다.
- 새 public 서비스 메서드 · 새 엔드포인트마다 테스트 1개 이상. 컨트롤러는 `@WebMvcTest`, 서비스는 Mockito, 리포지토리 쿼리는 `@DataJpaTest`, 모두 `@ActiveProfiles("test")`.
- 테스트 메서드명은 camelCase, `@DisplayName` 에 한국어 설명을 붙인다.
- 상수는 `UPPER_SNAKE_CASE`, 와일드카드 import 금지, 들여쓰기 4칸, 한 줄 120자 이내.
- 새 엔드포인트는 `/api/<도메인 복수형>` 아래에 둔다.

### modern/web (React 18 · TypeScript · Vite · Vitest)
- HTTP 요청은 `src/api/client.ts` 의 `getJson` 만 거친다. `client.ts` 밖에서 `fetch` 를 호출하지 않는다.
- 엔드포인트 함수는 `src/api/items.ts`, 응답 타입은 `src/api/types.ts` 에 두고 필드명은 백엔드 JSON 과 같게 쓴다.
- 조회 훅은 `src/hooks/use<이름>.ts` 에 두고 `useApiQuery` 를 호출해 `QueryState` 를 반환한다.
- 컴포넌트는 `QueryState.status` 로 분기하고 HTTP 상태 코드를 직접 비교하지 않는다(오류 문구 변환은 `toErrorMessage`).
- 함수 컴포넌트만 쓴다. 클래스 컴포넌트 · `React.FC` · `defaultProps` 를 쓰지 않는다.
- 한 파일은 컴포넌트 하나를 export 하고 200줄을 넘지 않는다.
- `any`, `as unknown as`, `@ts-ignore`, `dangerouslySetInnerHTML` 을 쓰지 않는다. `console.log` 를 남기지 않는다.
- `eslint-disable` 주석에는 같은 줄에 사유를 적는다.
- 이벤트 prop 은 `on<동작>`, 구현 함수는 `handle<동작>`.
- 새 컴포넌트마다 `<이름>.test.tsx` 를 만든다. fetch 는 `src/test/mockFetch.ts` · `fixtures.ts` 로 가로채고, 조회는 `getByRole` · `getByLabelText` 를 먼저 쓴다. 스냅샷 테스트는 만들지 않는다.

## 3. 금지 사항

### 먼저 묻는다
- DB 스키마 · 시드(`db/`) 변경, 마이그레이션 파일 추가.
- 의존성 추가 · 업그레이드: `build.gradle` 의 `plugins` · `dependencies`, `package.json` 의 `dependencies` · `devDependencies`. 이유와 대안을 함께 적는다.
- `application.yml` 의 DB 접속 · Hikari 풀 설정(최대 5, 대기 3초 — 운영 값과 같음) 변경.

### 하지 않는다
- `package-lock.json` 을 손으로 고치거나 지우지 않는다.
- 요청받지 않은 파일을 고치지 않는다. 포맷팅 · import 정리도 요청 범위 안의 파일에서만 한다.
- 이관 후 `characterization/` 비교가 실패하면 이관 코드를 고친다. 테스트 · `__snapshots__/` 를 고쳐 통과시키지 않는다.
- 실습 자료(`vendor-prs/`, `incident-logs/`, `pipeline-samples/`, `ci-ports/`, `specs/`)에 심어진 문제를 요청 없이 고치지 않는다.
- `templates/` 는 참고용이다. 템플릿과 코드가 다르면 코드를 사실로 본다.
- 루트 `.env` 를 만들지 않는다(권한 실습 더미는 `.env.perm-test`).

### 팀 규칙 (`templates/CLAUDE.iac.md` 에서 옮김)
- 비밀값(운영 계정 · 비밀번호 · API 키 · 토큰 · 인증서)을 코드 리터럴 · 설정 파일 · 변수 기본값에 넣지 않는다. 예외: 실습 더미 `app-pass`, `readonly-pass`, `Readonly-pass1`.
- `.env`, `.env.*`(`.env.perm-test` 제외), `*.pem`, `~/.aws/`, `~/.ssh/` 를 읽지 않는다.
- 운영 DB 호스트(`prod-db` 등) · 운영 계정 프로필(`AWS_PROFILE=prod`)을 명령 · 설정에 쓰지 않는다. 실제 AWS 계정에 접속하지 않는다.
- 버전 고정(`build.gradle` 의 plugin 버전, `package.json` 의 버전 범위 · `engines`, Java toolchain 21)을 풀거나 올리지 않는다. 필요하면 별도 변경으로 제안만 한다.
- Terraform(`pipeline-samples/terraform/`): Claude 는 `fmt`, `init -backend=false`, `validate`, `plan` 만 실행한다. `apply` · `destroy` · `import` · `state` · `taint` · `force-unlock` 은 실행하지 않고 "사람이 실행할 명령"으로만 적는다.
- `*.tfstate*`, `tfplan` 파일은 읽지 않고, 커밋하지 않고, 답변에 붙이지 않는다.
- IAM 정책에 `"Action": "*"` 와 `"Resource": "*"` 를 함께 쓰지 않는다. 보안 그룹 ingress 에 `0.0.0.0/0` · `::/0` 을 80 · 443 외 포트에 쓰지 않는다.

## 4. 아키텍처 안내

| 모듈 | 위치 | 스택 | 주소 | Compose 프로필 |
|---|---|---|---|---|
| 문항 은행 (레거시) | `legacy/item-bank-php/` | PHP 7.4 + MariaDB | :8081 | `php` |
| 과제 배포 (레거시) | `legacy/assignment-thymeleaf/` | Spring MVC + Thymeleaf + JDBC | :8082 | `thymeleaf` |
| 성적 집계 (레거시) | `legacy/grade-mssql/` | MS-SQL 저장 프로시저 + Java 호출부 | :8083 | `mssql` |
| 현행 API | `modern/api/` | Spring Boot 3.3 · Java 21 · JPA | :8080 | `modern`(DB 만) |
| 현행 화면 | `modern/web/` | React 18 · TS · Vite | :5173 | — |

```
modern/api/src/main/java/com/example/
├── item/         문항 · 단원 · 태그 (Item*, Unit*, Tag*)
├── assignment/   배포 · 재배포 · 학급 리포트 (Distribution*, Report*, Submission*)
├── common/       GlobalExceptionHandler, NotFoundException, ErrorResponse, ClockConfig
└── config/       WebConfig
modern/web/src/
├── api/          client.ts(getJson, ApiError) · items.ts(엔드포인트) · types.ts(응답 타입)
├── hooks/        useApiQuery(공통) + useUnits · useUnitItems · useItem
├── components/   화면 조각과 같은 폴더의 *.test.tsx
└── test/         mockFetch.ts · fixtures.ts
```

- 공개 문항만 노출한다(`status = 'A'`, `ItemStatus.ACTIVE`).
- web 데이터 흐름: 컴포넌트 → `hooks/use*` → `api/items.ts` → `getJson` → `modern/api`. 이전 요청은 AbortSignal 로 취소한다. `VITE_API_BASE=""` 이면 Vite 프록시(`/api` → 8080).
- `php` · `thymeleaf` · `modern` 프로필은 MariaDB 하나(DB `itembank`, :3306)를 공유한다. DB 는 tmpfs 라 `down` → `up` 하면 시드 상태로 돌아온다. `down` · `logs` 에도 `--profile` 을 붙인다.
- 조회용 계정: MariaDB `readonly`/`readonly-pass`, MS-SQL `readonly`/`Readonly-pass1`. 쓰기 계정 `app` 은 compose · `application.yml` 안에서만 쓴다.
- 레거시 규칙을 옮길 때는 근거를 `파일:줄번호` 로 적는다(예: `legacy/item-bank-php/search.php:214`).
- 도메인 용어: 문항 `item` · 단원 `unit`(예: `M5-1`) · 난이도 `level`(1~5) · 태그 `tag` · 학급 `class` · 과제 `assignment` · 배포 `distribution` · 제출 `submission` · 학생 `STU-<숫자>`.
- Hook 은 `.claude/settings.json` 에 `bash "$CLAUDE_PROJECT_DIR"/scripts/hook-node.sh hooks/<스크립트>.mjs` 형태로 등록한다.

# 문항 은행(레거시 PHP) 비즈니스 규칙 후보

대상: `legacy/item-bank-php/` · 스키마: `db/mariadb/init/01-schema.sql`
진입점 · 의존 관계는 [ARCHITECTURE.md](ARCHITECTURE.md), 테이블 · 데이터 흐름은 [ERD.md](ERD.md) 참고.

- 읽은 파일: `search.php`(전체), `register.php`(전체), `units.php`(전체), `inc/db.php`, `01-schema.sql:1-70`.
- 코드를 실행해 보지는 않았다. "확실"은 조건문 · SQL 을 읽어 확인했다는 뜻이다.
- 확신도 "추정" 규칙: BR-09, BR-28, BR-41. 근거가 된 "확실" 규칙 바로 아래에 둔다.
- 확신도 "판정"은 CROSS-CHECK.md 와 교차 검증한 뒤 사람이 코드를 확인해 CROSS-CHECK 쪽으로 확정한 규칙이다(10절). 판정 내용이 이 문서에 인용한 코드와 다르게 읽히는 규칙은 비고에 **코드 인용과 다름**으로 표시했다.
- **수정(UPDATE) 규칙은 없다.** 이 모듈에는 문항 수정 화면도 `UPDATE` 문도 없다(ERD.md 3절, grep 확인). 이 문서의 "등록 · 수정 검증"은 등록만 다룬다.

## 먼저 볼 것 (이관 시 결정이 필요한 항목)

| # | 내용 | 관련 규칙 |
|---|---|---|
| 1 | 난이도를 비우고 검색하면 **난이도 5 문항이 빠진다**(`level < 5`). 주석은 "1~5 모두 포함"이라고 한다 | BR-12 |
| 2 | 난이도에 1~5 밖의 값을 넣으면 오류로 거절하지 않고 경고 후 정수 변환 값으로 조회한다(대개 0건). 판정과 인용 코드(`(int)` 변환)가 다르게 읽혀 다시 볼 필요가 있다 | BR-14 |
| 3 | 키워드의 `%` · `_` 를 이스케이프하지 않아 와일드카드로 동작한다(경고만 낸다) | BR-04 |
| 4 | 같은 "유효한 단원 · 난이도 · 태그" 판정이 등록과 검색에서 다르게 구현돼 있다 | 6절 |
| 5 | 등록한 문항은 `status='R'` 로 들어가고, `R` → `A` 로 바꾸는 코드는 이 모듈에 없다 | BR-43, BR-01 |

## 1. 노출 · 제외 조건

| ID | 규칙 | 근거 | 근거 코드 | 확신도 | 비고 |
|---|---|---|---|---|---|
| BR-01 | 문항 검색은 목록 · 건수 모두 공개 문항 뷰 `v_item_public` 에서만 읽는다. 뷰가 무엇을 제외하는지(제외 조건 본체)는 이 모듈 코드로 검증하지 않았다 | `search.php:522,526` | `$from   = " FROM v_item_public";`<br>`$countSql = "SELECT COUNT(*) AS cnt" . $from . $where;` | 판정 | 검수중(`R`) 문항이 검색에 나오지 않는다는 것은 코드 주석의 설명이다(`register.php:84`). **코드 인용과 다름:** 스키마의 뷰 정의(`db/mariadb/init/01-schema.sql:70`)는 `WHERE i.status = 'A';` 이다. 판정(CX-07)에 따라 제외 조건은 규칙으로 올리지 않았다. 스키마 주석(`01-schema.sql:26`)상 `D`=삭제이지만 `D` 를 쓰는 코드는 이 모듈에 없다 |
| BR-02 | 단원 목록의 "공개 문항 수"는 `status = 'A'` 인 문항만 센다 | `units.php:17` | `(SELECT COUNT(*) FROM item i WHERE i.unit_id = u.id AND i.status = 'A') AS item_count` | 확실 | 주석 `units.php:15` 과 일치. 뷰를 쓰지 않고 같은 조건을 직접 적었다(조건이 두 곳에 있음) |

## 2. 문항 검색 — 조건 조합

| ID | 규칙 | 근거 | 근거 코드 | 확신도 | 비고 |
|---|---|---|---|---|---|
| BR-03 | 검색 조건(키워드 · 단원 · 난이도 · 태그)이 여러 개면 모두 AND 로 결합한다 | `search.php:44,98,118,215,218,244` | `$where    = " WHERE 1=1";`<br>`$where .= " AND unit_code = ?";` | 확실 | OR 조합 · 다중 선택은 없다 |
| BR-04 | 키워드가 비어 있지 않으면 제목 또는 지문에 키워드가 포함된 문항을 찾는다 | `search.php:97-98` | `$like = '%' . $q . '%';`<br>`$where .= " AND (title LIKE ? OR stem LIKE ?)";` | 확실 | `%` · `_` 를 이스케이프하지 않는다. 들어 있으면 경고만 낸다(`search.php:94-95`). 대소문자 처리는 BR-09 |
| BR-05 | 키워드는 앞뒤 공백을 지우고, 100자를 넘으면 100자까지만 쓰고 경고한다 | `search.php:85-88`, `search.php:494` | `if (mb_strlen($q, 'UTF-8') > 100) {`<br>`$q = mb_substr($q, 0, 100, 'UTF-8');` | 확실 | 폼 입력도 `maxlength="100"`(`search.php:494`). 100 이 두 곳에 있다 |
| BR-06 | 키워드가 한 글자면 경고만 내고 검색은 그대로 한다 | `search.php:91-92` | `if (mb_strlen($q, 'UTF-8') === 1) {`<br>`$warnings[] = '키워드가 한 글자라 결과가 많을 수 있습니다.';` | 확실 | |
| BR-07 | 단원 코드가 비어 있지 않으면 단원 코드가 같은 문항만 찾는다 | `search.php:109-120` | `$where .= " AND unit_code = ?";` | 확실 | 앞뒤 공백 제거 후 비교. 대소문자 처리는 BR-10 |
| BR-08 | 단원 코드가 `영문1자 + 숫자1~2 + '-' + 숫자1~2` 형식이 아니거나 소문자가 섞여 있으면 경고만 내고 입력값 그대로 조회한다 | `search.php:111-117` | `if (!preg_match('/^[A-Za-z][0-9]{1,2}-[0-9]{1,2}$/', $unit)) {`<br>`if ($unit !== strtoupper($unit)) {` | 확실 | 주석 "결과는 대개 0건"(`search.php:112`). 소문자 입력의 결과는 BR-10 |
| BR-09 | 키워드를 SQL 에서 비교할 때 대소문자를 구분하지 않는다 | `db/mariadb/init/01-schema.sql:33`, `search.php:98` | `) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;` | 추정 | 테이블 콜레이션이 `_ci` 이고 코드에 `BINARY` · `COLLATE` 지정이 없다는 데서 짐작했다. 단원 코드(BR-10) · 태그 이름(BR-19)은 교차 검증에서 대소문자를 그대로 비교하는 것으로 판정됐으므로, 같은 콜레이션을 쓰는 키워드도 이 추정이 맞는지 다시 확인해야 한다 |
| BR-10 | 단원 코드는 대소문자 그대로 정확 일치로 조회한다. 소문자로 넣으면 대문자 입력 경고를 내고 입력값 그대로 조회하며, 결과는 대개 0건이다 | `search.php:112,115-118` | `// 형식이 달라도 그대로 조회한다 (결과는 대개 0건)`<br>`$warnings[] = '단원 코드는 대문자로 입력하세요. (입력값 그대로 조회합니다)';`<br>`$where .= " AND unit_code = ?";` | 판정 | CX-03 판정. 주석 "결과는 대개 0건"(`search.php:112`)과 일치. 형식 · 소문자 · 미등록 코드 모두 오류로 막지 않는다 |
| BR-11 | `unit` 테이블에 없는 단원 코드면 "등록되지 않은 단원 코드" 경고를 내고 조회는 그대로 한다 | `search.php:125,141-143` | `$stmt = $conn->prepare("SELECT name, grade FROM unit WHERE code = ?");`<br>`$warnings[] = '등록되지 않은 단원 코드입니다: ' . $unit;` | 확실 | 있으면 요약에 `이름 (N학년)` 을 붙인다(`search.php:133,145`) |
| BR-12 | 난이도가 비어 있으면 **난이도 5 미만(1~4)** 문항만 찾는다 | `search.php:213-216` | `// 난이도 값이 비어 있으면 전체 난이도 검색 (1~5 모두 포함)`<br>`if ($level == '') {`<br>`$where .= " AND level < 5";` | 확실 | **주석과 코드 불일치.** 주석은 "1~5 모두 포함", 파일 머리 주석도 "빈값 허용"(`search.php:8`). 코드는 5 를 뺀다. 난이도 파라미터가 없을 때(초기 화면 포함)도 적용된다(`search.php:61`). 폼의 "전체" 선택지(`search.php:192`)도 이 조건이 된다. 느슨한 비교 `==` 사용 |
| BR-13 | 난이도가 `1`~`5` 한 자리면 그 난이도와 같은 문항만 찾는다 | `search.php:217-221` | `else if (preg_match('/^[1-5]$/', $level)) {`<br>`$where .= " AND level = ?";` | 확실 | |
| BR-14 | 난이도가 그 밖의 값이면 오류로 거절하지 않고 경고를 낸 뒤, 정수로 변환한 값으로 `level = ?` 비교한다. 숫자가 아닌 문자열은 0 이 되며 결과는 대개 0건이다 | `search.php:223-229` | `// 1~5 밖의 값은 그대로 정수로 비교한다 (대개 0건)`<br>`$warnings[] = '난이도는 1~5 사이여야 합니다.';`<br>`$values[] = (int)$level;` | 판정 | CX-05 판정. **코드 인용과 다름:** 인용한 `search.php:228` 의 `(int)$level` 은 PHP 에서 `'03'` · `'3.5'` · `'3abc'` 를 3 으로 바꾼다. 이 문서는 그런 입력에서 난이도 3 결과가 나온다고 적었으나, 판정에 따라 뺐다 |
| BR-15 | 태그가 비어 있지 않으면 그 이름의 태그가 달린 문항만 찾는다(정확 일치, 태그 1개) | `search.php:235,244-247` | `$where .= " AND EXISTS (SELECT 1 FROM item_tag it JOIN tag t ON t.id = it.tag_id"`<br>`. " WHERE it.item_id = v_item_public.id AND t.name = ?)";` | 확실 | 여러 태그 동시 검색 불가(BR-20) |
| BR-16 | 태그 이름이 50자를 넘으면 50자까지만 쓰고 경고한다 | `search.php:240-242` | `if (mb_strlen($tag, 'UTF-8') > 50) {`<br>`$tag = mb_substr($tag, 0, 50, 'UTF-8');` | 확실 | `tag.name VARCHAR(50)`(`01-schema.sql:37`)과 같은 값 |
| BR-17 | 태그에 `%` · `_` 가 있으면 "부분 일치 미지원" 경고만 내고 정확 일치로 조회한다 | `search.php:237-238` | `if (strpos($tag, '%') !== false \|\| strpos($tag, '_') !== false) {`<br>`$warnings[] = '태그는 부분 일치를 지원하지 않습니다.';` | 확실 | 키워드(BR-04)와 달리 `=` 비교라 와일드카드가 동작하지 않는다 |
| BR-18 | 태그 목록에 없는 태그 이름이면 "등록되지 않은 태그" 경고를 내고 조회는 그대로 한다(결과 0건) | `search.php:250-259` | `// 등록된 태그인지 확인 (없어도 조회는 그대로 한다 → 0건)`<br>`if ($opt['value'] !== '' && $opt['value'] === $tag) {`<br>`$warnings[] = '등록되지 않은 태그입니다: ' . $tag;` | 확실 | 등록 여부 판정은 PHP `===` 라 대소문자를 구분한다. 대소문자만 다른 이름은 BR-19 |
| BR-19 | 등록된 태그와 대소문자만 다른 이름으로 검색하면 "등록되지 않은 태그" 경고가 나고 결과는 0건이다 | `search.php:245,250,253,258-259` | `. " WHERE it.item_id = v_item_public.id AND t.name = ?)";`<br>`// 등록된 태그인지 확인 (없어도 조회는 그대로 한다 → 0건)` | 판정 | CX-06 판정. PHP 판정(`===`)과 SQL 비교(`t.name = ?`) 모두 대소문자를 구분하는 것으로 본다 |
| BR-20 | 같은 파라미터가 배열로 여러 번 오면 첫 값만 쓴다 | `search.php:55,59,63,67,71,75,79` | `$q = is_array($params['q']) ? (string)reset($params['q']) : (string)$params['q'];` | 확실 | 주석 `search.php:51` 과 일치 |

## 3. 문항 검색 — 정렬

| ID | 규칙 | 근거 | 근거 코드 | 확신도 | 비고 |
|---|---|---|---|---|---|
| BR-21 | 정렬 기준이 비어 있으면 난이도 내림차순, 같으면 문항 번호 오름차순으로 정렬한다 | `search.php:317-320` | `// 기본 정렬: 어려운 문항부터, 같은 난이도면 번호 순`<br>`$orderBy = " ORDER BY level DESC, id ASC";` | 확실 | 주석과 일치. BR-12 과 겹치면 기본 화면은 난이도 4 부터 나온다 |
| BR-22 | 정렬 기준이 `id` · `title` · `unit` · `level` · `created` 가 아니면 경고를 내고 기본 정렬을 쓴다 | `search.php:322-326` | `$warnings[] = '알 수 없는 정렬 기준입니다. 기본 정렬을 사용합니다.';`<br>`$orderBy = " ORDER BY level DESC, id ASC";` | 확실 | 기본 정렬 문자열이 `search.php:319,325` 두 곳에 있다 |
| BR-23 | 정렬 기준 · 방향은 앞뒤 공백을 지우고 소문자로 바꿔 비교한다 | `search.php:266-267` | `$sort = strtolower(trim($sort));`<br>`$dir  = strtolower(trim($dir));` | 확실 | |
| BR-24 | 정렬 방향이 `asc` · `desc` 가 아니면(빈값 제외) 경고를 내고 방향 없음으로 처리한다 | `search.php:268-273` | `if ($dir !== 'asc' && $dir !== 'desc') {`<br>`$warnings[] = '정렬 방향은 asc 또는 desc 만 가능합니다.';` | 확실 | |
| BR-25 | 방향을 주지 않으면 `id` · `title` · `unit` 은 오름차순, `level` · `created` 는 내림차순으로 정렬한다 | `search.php:278-315`, `search.php:330` | `$orderBy = " ORDER BY id ASC";` (`:281`, id 의 else 분기)<br>`$orderBy = " ORDER BY level DESC, id ASC";` (`:305`, level 의 else 분기)<br>`$dir = ($sort === 'level' \|\| $sort === 'created') ? 'desc' : 'asc';` (`:330`) | 확실 | SQL 분기(`:278-315`)와 화면 표시용 방향(`:330`)이 따로 구현돼 있으나 결과는 같다 |
| BR-26 | `id` 외 정렬은 동률이면 방향과 관계없이 문항 번호 오름차순으로 정렬한다 | `search.php:287,289,295,297,303,305,311,313` | `$orderBy = " ORDER BY title DESC, id ASC";` | 확실 | |
| BR-27 | 단원 정렬은 단원 코드 방향과 관계없이 2차로 난이도 내림차순을 쓴다 | `search.php:295,297` | `$orderBy = " ORDER BY unit_code DESC, level DESC, id ASC";`<br>`$orderBy = " ORDER BY unit_code ASC, level DESC, id ASC";` | 확실 | 단원 코드 문자열 정렬 순서는 BR-28 |
| BR-28 | 단원 정렬에서 두 자리 학년 코드(`M10-1`)가 한 자리 학년 코드(`M5-1`)보다 오름차순으로 앞에 온다 | `search.php:297`, `db/mariadb/init/01-schema.sql:13` | `$orderBy = " ORDER BY unit_code ASC, level DESC, id ASC";`<br>`code  VARCHAR(16)  NOT NULL,` | 추정 | 문자열 정렬이라 숫자 크기 순이 아니다. 두 자리 학년 단원이 시드에 있는지 확인하지 않았다. 단원 목록(`units.php:19`) · 선택 목록은 `grade` 가 먼저라 영향이 적다 |
| BR-29 | 결과 표 머리글의 정렬 링크는 `id` · `title` · `unit` · `level` 4개이고, 난이도는 첫 클릭에 내림차순, 나머지는 오름차순이다 | `search.php:392-397,400,410-412` | `} else if ($key === 'level') {`<br>`// 난이도는 첫 클릭에 내림차순`<br>`$nextDir = 'desc';` | 확실 | `created` 는 URL 로만 쓸 수 있고 머리글 링크가 없다. 정렬 링크는 현재 조건(q · unit · level · tag)을 유지하고 page 는 버린다(`search.php:369-387`) |

## 4. 문항 검색 — 페이지 · 결과 표시

| ID | 규칙 | 근거 | 근거 코드 | 확신도 | 비고 |
|---|---|---|---|---|---|
| BR-30 | 검색 결과는 한 페이지에 20건씩 보여 준다 | `search.php:349,523,553` | `$offset = ($page - 1) * 20;`<br>`$limit  = " LIMIT 20 OFFSET " . (int)$offset;`<br>`'page_size'    => 20,` | 확실 | 20 이 세 곳에 따로 있다(상수 없음) |
| BR-31 | 페이지 번호가 숫자가 아니거나 0 이하면 1페이지로 바꾸고 경고한다 | `search.php:337-344` | `if (preg_match('/^[0-9]+$/', $pageRaw)) {`<br>`} else if ($pageRaw !== '' && $pageRaw !== '1') {`<br>`$warnings[] = '페이지 번호가 올바르지 않아 1페이지를 표시합니다.';`<br>`if ($page < 1) {` | 판정 | CX-11 판정. **코드 인용과 다름:** `'0'` 은 `search.php:337` 정규식 `^[0-9]+$` 에 맞아 경고 분기(`:339-340`)를 타지 않고, `:342-343` 에서 경고 없이 1 로 바뀌는 것으로 읽힌다 |
| BR-32 | 페이지 번호가 999 를 넘으면 999 로 바꾸고 경고한다 | `search.php:345-347` | `if ($page > 999) {`<br>`$page = 999;` | 확실 | |
| BR-33 | 검색 결과 총 건수가 0 이면 "검색 결과가 없습니다" 를 표시하고 표를 그리지 않는다 | `search.php:657-660` | `if ($total === 0) {`<br>`echo '<p id="message">검색 결과가 없습니다</p>' . "\n";` | 확실 | 총 건수 기준이다. 총 건수 > 0 인데 마지막 페이지를 넘는 page 를 요청하면 빈 표가 나온다 |
| BR-34 | 전체 페이지가 2 이상이면 1 부터 마지막 페이지까지 모든 번호 링크와 이전 · 다음 링크를 표시한다 | `search.php:685-701` | `if ($pages > 1) {`<br>`for ($p = 1; $p <= $pages; $p++) {` | 확실 | 페이지 번호 링크 개수 상한이 없다 |

## 5. 문항 등록 — 검증 · 저장

| ID | 규칙 | 근거 | 근거 코드 | 확신도 | 비고 |
|---|---|---|---|---|---|
| BR-35 | 제목은 앞뒤 공백을 지운 뒤 5자 미만이면 등록을 거부한다 | `register.php:41,48-51` | `$title  = isset($_POST['title'])  ? trim((string)$_POST['title'])  : '';`<br>`if (mb_strlen($title, 'UTF-8') < 5) {` | 확실 | 가운데 공백도 글자로 센다(주석 `register.php:48` 과 일치). 라벨 "제목 (5자 이상)"(`register.php:143`) |
| BR-36 | 제목이 200자를 넘으면 등록을 거부한다 | `register.php:52-54` | `if (mb_strlen($title, 'UTF-8') > 200) {` | 확실 | `item.title VARCHAR(200)`(`01-schema.sql:23`)과 같은 값 |
| BR-37 | 지문이 앞뒤 공백을 지운 뒤 비어 있으면 등록을 거부한다 | `register.php:42,56-58` | `if ($stem === '') {`<br>`$errors[] = '지문을 입력해야 합니다.';` | 확실 | 지문 길이 상한 검증은 없다 |
| BR-38 | 단원 id 가 `unit` 테이블의 id 와 일치하지 않으면 등록을 거부한다 | `register.php:27,60-68` | `if ((string)$u['id'] === $unitId) {`<br>`$errors[] = '단원을 선택해야 합니다.';` | 확실 | 없는 id 도 "선택해야 합니다" 문구가 나온다 |
| BR-39 | 난이도가 `1`~`5` 한 자리 숫자가 아니면 등록을 거부한다 | `register.php:69-72` | `if (!preg_match('/^[1-5]$/', $level)) {` | 확실 | 검색 쪽 처리(BR-12, BR-13, BR-14)와 다르다 |
| BR-40 | 태그 id 중 `tag` 테이블에 없는 것은 오류 없이 버리고 나머지만 저장한다 | `register.php:73-81` | `if ((string)$t['id'] === (string)$tid) {`<br>`$validTagIds[] = (int)$tid;` | 확실 | 태그는 없어도 된다(필수 아님). 같은 id 를 걸러 내지 않는다. 중복 전송 시 동작은 BR-41 |
| BR-41 | 같은 태그 id 가 두 번 이상 전송되면 등록 전체가 실패하고 되돌려진다 | `register.php:75-78,105-109,119-122`, `inc/db.php:20`, `db/mariadb/init/01-schema.sql:45` | `$validTagIds[] = (int)$tid;`<br>`throw new RuntimeException('태그 저장 실패');`<br>`PRIMARY KEY (item_id, tag_id),` | 추정 | 중복 id 를 걸러 내지 않아 두 번째 INSERT 가 PK 충돌로 `execute()` false 가 되고(`mysqli_report(MYSQLI_REPORT_OFF)`) 예외 → 롤백으로 이어질 것으로 코드 흐름상 짐작. 화면 체크박스로는 중복이 생기지 않고 직접 만든 요청에서만 생긴다. 실행하지 않았다 |
| BR-42 | 검증 오류가 하나라도 있으면 저장하지 않고, 모든 오류를 한꺼번에 보여 준다 | `register.php:83,133-138` | `if (empty($errors)) {` | 확실 | |
| BR-43 | 새 문항은 검수중(`status = 'R'`)으로 저장한다 | `register.php:93-94` | `INSERT INTO item (id, unit_id, title, stem, level, status, created_at, updated_at)`<br>`VALUES (?, ?, ?, ?, ?, 'R', NOW(), NOW())` | 확실 | 등록 직후에는 단원별 문항 수(BR-02)에 나오지 않는다. 검색에도 나오지 않는다는 것은 주석(`register.php:84`) · 안내 문구(`register.php:116`)의 설명이며 뷰 제외 조건은 미검증(BR-01). "검수 완료 후 노출"의 `R` → `A` 전환 코드는 이 모듈에 없다(주석 · 문구만 있음). 스키마 기본값은 `'A'`(`01-schema.sql:26`) |
| BR-44 | 새 문항 번호는 트랜잭션 안에서 `item` 테이블을 `FOR UPDATE` 로 잠그고 현재 최대 번호 + 1 로 정한다 | `register.php:85,87-90` | `$conn->begin_transaction();`<br>`$res = $conn->query("SELECT COALESCE(MAX(id), 0) + 1 AS next_id FROM item FOR UPDATE");` | 판정 | CX-18 판정. `item.id` 는 AUTO_INCREMENT 가 아니다(ERD.md). 첫 문항은 1 |
| BR-45 | 등록 시각 · 수정 시각은 둘 다 DB 의 현재 시각으로 넣는다 | `register.php:94` | `VALUES (?, ?, ?, ?, ?, 'R', NOW(), NOW())` | 확실 | 앱 서버 시각이 아니라 DB 시각 |
| BR-46 | 문항과 태그 연결은 한 트랜잭션으로 저장하고, 하나라도 실패하면 전부 되돌린다 | `register.php:85,99-100,108-109,114,119-122` | `$conn->begin_transaction();`<br>`$conn->commit();`<br>`$conn->rollback();` | 확실 | 사용자에게는 "저장 중 오류가 발생했습니다." 만 보인다 |
| BR-47 | 등록에 성공하면 입력 폼을 비운다 | `register.php:117-118` | `$title = $stem = $unitId = $level = '';` | 확실 | 실패 시에는 입력값을 유지한다 |

## 6. 같은 규칙이 여러 곳에 다르게 구현된 경우

| 대상 | 등록(`register.php`) | 검색(`search.php`) |
|---|---|---|
| 난이도 1~5 | `^[1-5]$` 아니면 거부(`:70`) | 빈값 → `level < 5`(`:215`), `^[1-5]$` → 일치, 그 밖 → 경고 + `(int)` 변환 값으로 조회, 대개 0건(`:214-229`, BR-14) |
| 단원 유효성 | `unit.id` 목록에 없으면 거부(`:61-67`) | `unit.code` 로 조회해 없으면 경고만, 조회 계속(`:141-142`) |
| 태그 유효성 | 목록에 없는 id 는 조용히 버림(`:74-81`) | 목록에 없는 이름이면 경고, 조회 계속, 결과 0건(`:250-259`). 대소문자만 다른 이름도 같다(BR-19) |
| 대소문자 | id 비교라 해당 없음 | 단원 코드는 대소문자 그대로 비교, 소문자면 경고(`:115-118`, BR-10). 태그는 등록 판정 `===`(`:253`) · SQL 비교 모두 구분(BR-19). 키워드는 `_ci` 콜레이션으로 구분 안 함(BR-09, 추정) |
| 공개 조건 `status='A'` | — | `units.php:17` 에 직접 적혀 있다. 검색은 뷰 `v_item_public` 를 거치며 뷰의 제외 조건은 미검증(BR-01) |
| 단원 선택 목록 정렬 `grade ASC, code ASC` | `register.php:27` | `search.php:156`, `units.php:19` |
| 태그 목록 정렬 `id ASC` | `register.php:34` | `search.php:178`, 결과의 `tag_names` 도 `ORDER BY t.id`(`01-schema.sql:64`) |

## 7. 매직 넘버

| 값 | 위치 | 추정 의미 |
|---|---|---|
| `5` | `register.php:49` | 제목 최소 글자 수 |
| `200` | `register.php:52` | 제목 최대 글자 수 (`item.title VARCHAR(200)` 과 같음) |
| `1`~`5` (`/^[1-5]$/`) | `register.php:70`, `search.php:217`, 선택지 `register.php:163`, `search.php:193-199` | 난이도 범위. 스키마는 주석만(`01-schema.sql:25`), CHECK 제약 없음 |
| `5` (`level < 5`) | `search.php:215` | 난이도 상한으로 추정. 주석 "1~5 모두 포함"과 달리 5 를 제외한다 (`<= 5` 의 오기 가능성) |
| `100` | `search.php:86-87`, `search.php:494`(`maxlength`) | 키워드 최대 글자 수 |
| `50` | `search.php:240-241` | 태그 이름 최대 글자 수 (`tag.name VARCHAR(50)` 과 같음) |
| `[0-9]{1,2}-[0-9]{1,2}` | `search.php:111` | 단원 코드 형식: 학년 숫자 1~2자리 - 단원 숫자 1~2자리 (예 `M5-1`) |
| `20` | `search.php:349,523,553` | 페이지 크기 |
| `999` | `search.php:345-346` | 최대 페이지 번호 |
| `'A'` | `01-schema.sql:26,70`, `units.php:17` | 공개 상태 코드 |
| `'R'` | `register.php:94` | 검수중 상태 코드 (등록 시 초기값) |
| `'D'` | `01-schema.sql:26` (주석만) | 삭제 상태 코드. 코드에서 쓰지 않음 |

## 8. 주석만 있고 코드로 확인되지 않은 것

규칙으로 올리지 않았다.

| 내용 | 위치 | 비고 |
|---|---|---|
| 난이도 1~5 저장 제약 | `01-schema.sql:25` | 주석만 있음. DB 에 CHECK 가 없고 등록 화면만 검증한다 |
| `status` 값 `A` · `D` · `R` 의 의미, `D`=삭제 | `01-schema.sql:26` | 주석만 있음. `D` 를 쓰는 코드 없음 |
| 검수 완료 후 검색에 노출 (`R` → `A`) | `register.php:84,116` | 주석 · 안내 문구만 있음. 전환 코드는 이 모듈에 없다 |
| 등록 화면도 `v_item_public` 을 기준으로 삼는다 | `01-schema.sql:51` | 주석만 있음. `register.php` 는 뷰를 참조하지 않는다(주석과 코드 불일치) |
| 난이도 라벨 1 매우 쉬움 ~ 5 최상 | `search.php:193-199` | 화면 라벨. 판정 로직에는 쓰이지 않는다 |

## 9. 미확인

| 항목 | 이유 |
|---|---|
| `_ci` 콜레이션에 따른 키워드의 대소문자 무시 여부 (BR-09) | DB 설정으로 추정. 실제 조회를 실행하지 않았다. 단원 코드 · 태그는 교차 검증에서 대소문자 구분으로 판정됐다(BR-10, BR-19) |
| 뷰 `v_item_public` 의 제외 조건 (BR-01) | 교차 검증 판정(CX-07)에 따라 이 모듈 코드로는 검증하지 않은 것으로 둔다. 스키마 뷰 정의는 `01-schema.sql:70` |
| `M10-1` 과 `M5-1` 의 정렬 순서 (BR-28) | 시드에 두 자리 학년 단원이 있는지 확인하지 않았다 |
| 태그 id 중복 전송 시 롤백 (BR-41) | 코드 흐름으로 추정. 실행하지 않았다 |
| 난이도 5 문항이 시드에 있어 BR-12 이 실제 결과에 영향을 주는지 | `db/mariadb/init/02-seed.sql` 을 행 단위로 보지 않았다 |

## 10. 검증 이력

2026-09-29 — CROSS-CHECK.md(CX-*)와 교차 검증. 내용이 다른 6개 항목을 사람이 코드로 확인해 모두 CROSS-CHECK 쪽으로 판정했다.

- BR-10: 소문자 단원 코드가 대문자와 같은 결과를 낸다(추정) → 대소문자 그대로 정확 일치, 대개 0건 (CX-03, 판정)
- BR-09: 키워드 · 단원 코드 · 태그의 대소문자 무시(추정) → 키워드만 남김, 추정 유지 (BR-10 · BR-19 판정에 따른 범위 축소)
- BR-19: 대소문자만 다른 태그가 경고와 함께 결과로 나온다(추정) → 경고 후 0건 (CX-06, 판정). BR-18 에 "결과 0건" 추가
- BR-14: `03` · `3.5` 는 3 으로 바뀌어 결과가 나온다 → 경고 후 `(int)` 값으로 비교, 대개 0건 (CX-05, 판정, 코드 인용과 다름 표시)
- BR-31: page=0 은 경고 없이 1페이지 → 숫자가 아니거나 0 이하이면 1페이지 + 경고 (CX-11, 판정, 코드 인용과 다름 표시)
- BR-01: `status='A'` 만 검색 대상(확실) → `v_item_public` 만 조회, 뷰의 제외 조건은 미검증 (CX-07, 판정, 코드 인용과 다름 표시). BR-43 비고 · 6절 · 9절 · ERD.md 1절을 같이 고침
- BR-44: 조회할 때 잠금, 동시성 효과는 미확인 → `item` 테이블을 `FOR UPDATE` 로 잠그고 MAX+1 (CX-18, 판정). 9절 "동시 등록" 행 삭제
- 머리말 추정 규칙 목록에서 BR-10 · BR-19 제거, "먼저 볼 것" 2번을 BR-14 판정에 맞춰 고침

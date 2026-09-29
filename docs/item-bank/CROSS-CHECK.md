# 문항 은행(PHP) 비즈니스 규칙 — 코드 기준 추출 (CROSS-CHECK)

- 대상: `legacy/item-bank-php/` 의 `search.php`, `register.php` (보조로 `units.php`, `inc/db.php`). `vendor/` 는 읽지 않았다.
- `docs/` 아래의 다른 문서는 읽지 않았다.
- 주석과 코드가 다르면 코드를 기준으로 적었다.
- 줄번호는 `legacy/item-bank-php/` 기준 상대 경로다.

## 확인 범위와 한계

- 수정 화면(`edit.php`, `update.php`, `item.php`, `item_edit.php`)은 없다. 메뉴는 검색 · 등록 · 단원 목록 3개뿐이다(`index.php:9-11`). 따라서 **수정 시 검증 규칙은 이 폴더 코드에 없다.** 코드에 없는 규칙은 쓰지 않는다는 원칙에 따라 뺐다.
- 검색은 `v_item_public` 뷰를 조회한다(`search.php:522`). 이 폴더에는 뷰 정의(제외 조건 본체)가 없다. 그래서 "무엇이 제외되는가"는 이 폴더에서 확인되는 것만 적었다(CX-07). 뷰 정의는 이 범위 밖이라 읽지 않았다.

## 1. 검색 조건 조합

### CX-01 조건 조합 — 모든 조건은 AND 로 결합한다
- 규칙: 키워드 · 단원 · 난이도 · 태그 조건은 모두 `AND` 로 붙는다. 조건이 없으면 `WHERE 1=1` 뿐이다.
- 근거: `search.php:44`, `:98`, `:118`, `:244`
```php
$where    = " WHERE 1=1";
$where .= " AND (title LIKE ? OR stem LIKE ?)";
$where .= " AND unit_code = ?";
```

### CX-02 키워드 — 제목 또는 지문에 부분 일치, 100자 초과는 자른다
- 규칙: `q` 는 앞뒤 공백을 제거하고 100자를 넘으면 100자로 자른다. 비어 있지 않으면 제목 OR 지문에 `%q%` LIKE 로 찾는다. `%` · `_` 는 이스케이프하지 않아 와일드카드로 동작한다(경고만 낸다).
- 근거: `search.php:85-89`, `:94-98`
```php
$q = trim($q);
if (mb_strlen($q, 'UTF-8') > 100) {
    $q = mb_substr($q, 0, 100, 'UTF-8');
$where .= " AND (title LIKE ? OR stem LIKE ?)";
```

### CX-03 단원 — 코드 정확 일치, 형식 · 소문자 · 미등록 코드도 막지 않는다
- 규칙: `unit` 은 `unit_code = ?` 로 대소문자 그대로 정확 일치 조회한다. 형식이 틀리거나 소문자이거나 미등록 코드여도 오류 없이 경고만 내고 조회한다(대개 0건).
- 근거: `search.php:111-120`, `:141-143`
```php
if (!preg_match('/^[A-Za-z][0-9]{1,2}-[0-9]{1,2}$/', $unit)) {
    // 형식이 달라도 그대로 조회한다 (결과는 대개 0건)
$where .= " AND unit_code = ?";
```

### CX-04 난이도 미지정 — 난이도 5 문항이 제외된다 (주석과 코드가 다름)
- 규칙: `level` 이 비어 있으면 `level < 5` 조건이 붙어 **난이도 5 는 결과에서 빠진다.** 주석은 "1~5 모두 포함"이라 하지만 코드는 그렇게 동작하지 않는다. 난이도 5 는 `level=5` 로 명시해야만 나온다.
- 근거: `search.php:213-216`
```php
// 난이도 값이 비어 있으면 전체 난이도 검색 (1~5 모두 포함)
if ($level == '') {
    $where .= " AND level < 5";
```
- 이 조건은 `unit` · `tag` 조건과 독립이라 어떤 조합에서도 항상 적용된다.

### CX-05 난이도 지정 — 1~5 는 정확 일치, 범위 밖 값은 정수로 바꿔 비교한다
- 규칙: `^[1-5]$` 면 `level = ?`. 그 밖의 값은 경고 후 `(int)` 변환값으로 `level = ?` 비교한다(숫자가 아닌 문자열은 0, 대개 0건). 오류로 거절하지 않는다.
- 근거: `search.php:217-229`
```php
else if (preg_match('/^[1-5]$/', $level)) {
    $where .= " AND level = ?";
    $values[] = (int)$level;
```
```php
$warnings[] = '난이도는 1~5 사이여야 합니다.';
$values[] = (int)$level;
```

### CX-06 태그 — 이름 정확 일치, 50자 초과는 자른다, 단일 태그만
- 규칙: `tag` 는 앞뒤 공백 제거 후 50자를 넘으면 자른다. 문항에 그 이름의 태그가 하나라도 있으면 통과(`EXISTS`)한다. 부분 일치는 지원하지 않는다. 미등록 태그여도 조회는 하고 경고만 낸다(0건). 태그는 1개만 받는다(배열 입력은 첫 값만 씀, `search.php:51-80`).
- 근거: `search.php:240-245`
```php
if (mb_strlen($tag, 'UTF-8') > 50) {
    $tag = mb_substr($tag, 0, 50, 'UTF-8');
$where .= " AND EXISTS (SELECT 1 FROM item_tag it JOIN tag t ON t.id = it.tag_id"
        . " WHERE it.item_id = v_item_public.id AND t.name = ?)";
```

### CX-07 조회 대상 — 공개 문항 뷰만 조회한다
- 규칙: 목록과 건수 모두 `v_item_public` 에서만 읽는다. 검수중(`R`) 문항은 등록 직후 이 목록에 나오지 않는다는 것이 코드 주석의 설명이며, 뷰 정의는 이 폴더 밖이라 제외 조건 자체는 여기서 검증하지 못했다.
- 같은 폴더에서 확인되는 제외 관련 코드: 단원 목록의 공개 문항 수는 `status = 'A'` 만 센다(`units.php:15-17`).
- 근거: `search.php:522`, `units.php:17`
```php
$from   = " FROM v_item_public";
```
```php
(SELECT COUNT(*) FROM item i WHERE i.unit_id = u.id AND i.status = 'A') AS item_count
```

### CX-08 건수 — 목록과 같은 조건으로 센다
- 규칙: 총 건수는 목록과 같은 `WHERE` 로 계산한다. 페이지와 무관하게 조건 전체의 건수다.
- 근거: `search.php:526`
```php
$countSql = "SELECT COUNT(*) AS cnt" . $from . $where;
```

## 2. 목록 정렬 · 페이지

### CX-09 기본 정렬 — 난이도 높은 순, 같으면 id 오름차순
- 규칙: `sort` 가 비어 있으면 `level DESC, id ASC`. 알 수 없는 `sort` 값도 경고 후 같은 기본 정렬로 되돌린다.
- 근거: `search.php:317-326`
```php
case '':
    // 기본 정렬: 어려운 문항부터, 같은 난이도면 번호 순
    $orderBy = " ORDER BY level DESC, id ASC";
```
```php
$warnings[] = '알 수 없는 정렬 기준입니다. 기본 정렬을 사용합니다.';
```

### CX-10 정렬 기준별 방향 기본값과 2차 키
- 규칙: `dir` 은 `asc`/`desc` 만 인정하고 그 밖의 값은 무시한다(경고). 방향을 안 주거나 잘못 주면 기준별로 다음이 적용된다. 2차 키는 항상 `id ASC` 다(`id` 정렬 제외).

| sort | 기본(dir 없음) | 명시 시 | 2차 키 |
|---|---|---|---|
| `id` | id ASC | desc 면 id DESC | 없음 |
| `title` | title ASC | desc 면 title DESC | id ASC |
| `unit` | unit_code ASC | desc 면 unit_code DESC | level DESC, id ASC (방향과 무관하게 level DESC) |
| `level` | level DESC | asc 면 level ASC | id ASC |
| `created` | created_at DESC | asc 면 created_at ASC | id ASC |

- 근거: `search.php:268-273`, `:277-315`
```php
$orderBy = " ORDER BY unit_code DESC, level DESC, id ASC";
$orderBy = " ORDER BY level DESC, id ASC";   // level, dir 이 asc 가 아니면
$orderBy = " ORDER BY created_at DESC, id ASC";  // created, dir 이 asc 가 아니면
```
- 참고: 화면의 정렬 링크는 `id · title · unit · level` 만 제공하고 `created` 는 링크 없이 URL 로만 쓸 수 있다(`search.php:392-397`).

### CX-11 페이지 — 20건 단위, 1~999 로 보정
- 규칙: 한 페이지는 20건. 숫자가 아닌 `page` 는 1, 0 이하는 1, 999 초과는 999 로 보정하고 경고를 낸다.
- 근거: `search.php:337-349`, `:523`
```php
if ($page > 999) {
    $page = 999;
$limit  = " LIMIT 20 OFFSET " . (int)$offset;
```

## 3. 등록 시 검증 (`register.php`)

### CX-12 제목 — 앞뒤 공백 제거 후 5~200자
- 규칙: 제목은 `trim` 후 글자 수(mb) 가 5 미만이면 오류, 200 초과면 오류. 내부 공백은 글자 수에 포함된다.
- 근거: `register.php:41`, `:49-54`
```php
if (mb_strlen($title, 'UTF-8') < 5) {
if (mb_strlen($title, 'UTF-8') > 200) {
```

### CX-13 지문 — 필수, 길이 상한 없음
- 규칙: `trim` 후 빈 문자열이면 오류. 최대 길이 검증은 없다.
- 근거: `register.php:56-58`
```php
if ($stem === '') {
    $errors[] = '지문을 입력해야 합니다.';
```

### CX-14 단원 — 필수, 등록된 단원 id 여야 한다
- 규칙: `unit_id` 가 `unit` 테이블에서 읽은 목록의 `id` 와 일치해야 한다. 아니면 오류.
- 근거: `register.php:27`, `:60-68`
```php
if ((string)$u['id'] === $unitId) {
    $unitOk = true;
$errors[] = '단원을 선택해야 합니다.';
```

### CX-15 난이도 — 1~5 정수 필수
- 규칙: `^[1-5]$` 가 아니면 오류(빈 값 포함). 등록에서는 검색과 달리 범위 밖 값을 거절한다.
- 근거: `register.php:70-72`
```php
if (!preg_match('/^[1-5]$/', $level)) {
    $errors[] = '난이도는 1~5 사이여야 합니다.';
```

### CX-16 태그 — 선택 사항, 목록에 없는 id 는 오류 없이 버린다
- 규칙: 태그는 없어도 등록된다. 목록에 없는 태그 id 는 오류 없이 조용히 무시하고, 있는 것만 저장한다.
- 근거: `register.php:74-81`, `:104-113`
```php
if ((string)$t['id'] === (string)$tid) {
    $validTagIds[] = (int)$tid;
```

## 4. 등록 저장 규칙

### CX-17 신규 문항은 검수중(`R`) 상태로 저장한다
- 규칙: 등록된 문항의 `status` 는 항상 `'R'` 이다. 등록 화면 안내 문구는 검수 완료 후 검색에 노출된다고 한다. `A` 로 바꾸는 코드는 이 폴더에서 찾지 못했다.
- 근거: `register.php:93-94`, `:116`
```php
"INSERT INTO item (id, unit_id, title, stem, level, status, created_at, updated_at)
 VALUES (?, ?, ?, ?, ?, 'R', NOW(), NOW())"
```

### CX-18 id 는 MAX+1 로 채번, 문항과 태그는 한 트랜잭션
- 규칙: `item` 테이블을 `FOR UPDATE` 로 잠그고 `MAX(id)+1` 을 id 로 쓴다. 문항과 태그 저장은 한 트랜잭션이며 하나라도 실패하면 롤백하고 "저장 중 오류가 발생했습니다."를 낸다.
- 근거: `register.php:85-87`, `:114`, `:120-122`
```php
$conn->begin_transaction();
$res = $conn->query("SELECT COALESCE(MAX(id), 0) + 1 AS next_id FROM item FOR UPDATE");
$conn->rollback();
```

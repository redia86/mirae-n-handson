// 동작 보존 테스트 — 문항 은행(item-bank)의 문항 검색 화면 search.php
//
// 규칙 ID(BR-xx)는 docs/item-bank/BUSINESS-RULES.md 를 따른다.
// - 대상 주소는 lib/target.mjs 가 정한다(환경 변수 TARGET_BASE_URL, 없으면 모듈의 레거시 기본 주소).
// - 응답은 fetchNormalized 로 {status, rows, count, message} 모양으로 바꾼 뒤 스냅샷과 비교한다.
// - 기대값을 손으로 적지 않는다. 지금 시스템의 실제 응답이 기대값이다(npm run baseline -- item-bank).
// - 레거시와 새 API 양쪽에서 같은 결과가 나와야 하므로 건너뛰기를 두지 않는다.
import { describe, expect, it } from 'vitest';
import { fetchNormalized } from '../lib/target.mjs';

const MODULE = 'item-bank';
const SEARCH_PATH = '/search.php';

// 경계값 · 이상한 값에 쓰는 긴 문자열
const KEYWORD_OVER_LIMIT = '분'.repeat(101); // 키워드 상한 100자 + 1 (BR-05)
const TAG_OVER_LIMIT = '가'.repeat(51); // 태그 상한 50자 + 1 (BR-16)
const KEYWORD_VERY_LONG = 'a'.repeat(1000); // 아주 긴 키워드

function search(params) {
  return fetchNormalized(MODULE, SEARCH_PATH, params);
}

describe('item-bank · 문항 검색(search.php)', () => {
  describe('정상', () => {
    it('조건 3개 AND 조합과 공개 문항만 노출 — q=분수 unit=M5-1 level=3 (BR-03 · 04 · 07 · 13 · 01)', async () => {
      const result = await search({ q: '분수', unit: 'M5-1', level: '3' });
      expect(result).toMatchSnapshot();
    });

    it('태그 정확 일치 + 제목 오름차순, 동률은 id 순 — tag=문장제 sort=title dir=asc (BR-15 · 23 · 26)', async () => {
      const result = await search({ tag: '문장제', sort: 'title', dir: 'asc' });
      expect(result).toMatchSnapshot();
    });

    it('단원 정렬 방향 생략 시 기본 방향과 2차 난이도 정렬 — level=2 sort=unit (BR-25 · 27 · 01)', async () => {
      const result = await search({ level: '2', sort: 'unit' });
      expect(result).toMatchSnapshot();
    });
  });

  describe('경계값', () => {
    it('난이도 5 를 명시하면 조회되는지 — level=5 (BR-13 · 12)', async () => {
      const result = await search({ level: '5' });
      expect(result).toMatchSnapshot();
    });

    it('총 건수가 페이지 크기와 같을 때 2페이지 — page=2 (BR-30 · 33 · 34)', async () => {
      const result = await search({ page: '2' });
      expect(result).toMatchSnapshot();
    });

    it('페이지 상한 999 바로 위 — page=1000 (BR-32)', async () => {
      const result = await search({ page: '1000' });
      expect(result).toMatchSnapshot();
    });

    it('페이지 0 — page=0 (BR-31)', async () => {
      const result = await search({ page: '0' });
      expect(result).toMatchSnapshot();
    });

    it('키워드 한 글자 — q=분 (BR-06)', async () => {
      const result = await search({ q: '분' });
      expect(result).toMatchSnapshot();
    });

    it('키워드 상한 100자 바로 위 — q=분×101 (BR-05)', async () => {
      const result = await search({ q: KEYWORD_OVER_LIMIT });
      expect(result).toMatchSnapshot();
    });

    it('태그 상한 50자 바로 위 — tag=가×51 (BR-16 · 18)', async () => {
      const result = await search({ tag: TAG_OVER_LIMIT });
      expect(result).toMatchSnapshot();
    });
  });

  describe('빈 값 · 누락', () => {
    it('파라미터 없음(초기 화면) — 빈 난이도 조건 · 기본 정렬 · 페이지 크기 (BR-12 · 21 · 30 · 01)', async () => {
      const result = await search({});
      expect(result).toMatchSnapshot();
    });

    it('모든 파라미터 빈 값 · 공백만 — q=␠␠ unit=␠ level= tag= sort= dir= page= (BR-05 · 07 · 12 · 24 · 31)', async () => {
      const result = await search({ q: '  ', unit: ' ', level: '', tag: '', sort: '', dir: '', page: '' });
      expect(result).toMatchSnapshot();
    });
  });

  describe('이상한 값', () => {
    it('음수 난이도 · 음수 페이지 — level=-1 page=-3 (BR-14 · 31)', async () => {
      const result = await search({ level: '-1', page: '-3' });
      expect(result).toMatchSnapshot();
    });

    it('아주 긴 키워드 — q=a×1000 (BR-05 · 04)', async () => {
      const result = await search({ q: KEYWORD_VERY_LONG });
      expect(result).toMatchSnapshot();
    });

    it('형식은 맞지만 없는 단원 코드 — unit=X9-99 (BR-11 · 07)', async () => {
      const result = await search({ unit: 'X9-99' });
      expect(result).toMatchSnapshot();
    });

    it('있는 단원 코드의 소문자 입력 — unit=m5-1 (BR-08 · 10)', async () => {
      const result = await search({ unit: 'm5-1' });
      expect(result).toMatchSnapshot();
    });

    it('알 수 없는 정렬 기준 · 방향과 와일드카드 키워드 — q=% sort=price dir=up (BR-22 · 24 · 04)', async () => {
      const result = await search({ q: '%', sort: 'price', dir: 'up' });
      expect(result).toMatchSnapshot();
    });
  });
});

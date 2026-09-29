package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
class ItemSearchConditionTest {

    private static ItemSearchCondition conditionOf(String rawQuery) {
        return ItemSearchCondition.from(ItemSearchParams.parse(rawQuery));
    }

    @Test
    @DisplayName("from: 파라미터가 없으면 조건 없음 · 난이도 null · 기본 정렬 · 1페이지")
    void defaultsWhenNoParams() {
        ItemSearchCondition condition = conditionOf(null);

        assertThat(condition.keyword()).isNull();
        assertThat(condition.unitCode()).isNull();
        assertThat(condition.level()).isNull();
        assertThat(condition.tag()).isNull();
        assertThat(condition.sort()).isEqualTo(Sort.by(Direction.DESC, "level").and(Sort.by(Direction.ASC, "id")));
        assertThat(condition.page()).isEqualTo(1);
    }

    @Test
    @DisplayName("from: 키워드 · 단원 · 태그는 PHP trim 후 빈값이면 조건에서 뺀다")
    void trimsLikePhp() {
        ItemSearchCondition condition = conditionOf("q=++&unit=+M5-1%09&tag=%0B");

        assertThat(condition.keyword()).isNull();
        assertThat(condition.unitCode()).isEqualTo("M5-1");
        assertThat(condition.tag()).isNull();
        assertThat(ItemSearchCondition.phpTrim("　분수\f")).isEqualTo("　분수\f");
    }

    @Test
    @DisplayName("from: 키워드는 100자, 태그는 50자까지 코드 포인트 기준으로 자른다(BR-05, BR-16)")
    void truncatesKeywordAndTag() {
        ItemSearchCondition condition = conditionOf("q=" + "a".repeat(101) + "&tag=" + "b".repeat(51));

        assertThat(condition.keyword()).hasSize(ItemSearchCondition.KEYWORD_MAX_LENGTH);
        assertThat(condition.tag()).hasSize(ItemSearchCondition.TAG_MAX_LENGTH);
        assertThat(ItemSearchCondition.truncate("😀😀😀", 2)).isEqualTo("😀😀");
    }

    @Test
    @DisplayName("parseLevel: 빈값은 null, 1~5 는 그 값, 그 밖은 PHP (int) 변환 값(BR-12~14)")
    void parsesLevelLikePhp() {
        assertThat(ItemSearchCondition.parseLevel("")).isNull();
        assertThat(ItemSearchCondition.parseLevel("5")).isEqualTo(5L);
        assertThat(ItemSearchCondition.parseLevel("03")).isEqualTo(3L);
        assertThat(ItemSearchCondition.parseLevel("3.9")).isEqualTo(3L);
        assertThat(ItemSearchCondition.parseLevel("3abc")).isEqualTo(3L);
        assertThat(ItemSearchCondition.parseLevel("+3")).isEqualTo(3L);
        assertThat(ItemSearchCondition.parseLevel("0.3e1")).isEqualTo(3L);
        assertThat(ItemSearchCondition.parseLevel("-1")).isEqualTo(-1L);
        assertThat(ItemSearchCondition.parseLevel("abc")).isEqualTo(0L);
        assertThat(ItemSearchCondition.parseLevel("99999999999999999999")).isEqualTo(Long.MAX_VALUE);
        assertThat(ItemSearchCondition.parseLevel("1e1000")).isEqualTo(0L);
    }

    @Test
    @DisplayName("parsePage: 숫자가 아니거나 0 이하면 1, 999 초과면 999, 끝 \\n 하나는 허용(BR-31, BR-32)")
    void parsesPageLikePhp() {
        assertThat(ItemSearchCondition.parsePage("2")).isEqualTo(2);
        assertThat(ItemSearchCondition.parsePage("0")).isEqualTo(1);
        assertThat(ItemSearchCondition.parsePage("")).isEqualTo(1);
        assertThat(ItemSearchCondition.parsePage("-3")).isEqualTo(1);
        assertThat(ItemSearchCondition.parsePage(" 2")).isEqualTo(1);
        assertThat(ItemSearchCondition.parsePage("2\n")).isEqualTo(2);
        assertThat(ItemSearchCondition.parsePage("1000")).isEqualTo(ItemSearchCondition.MAX_PAGE);
        assertThat(ItemSearchCondition.parsePage("99999999999999999999")).isEqualTo(ItemSearchCondition.MAX_PAGE);
    }

    @Test
    @DisplayName("from: 정렬 기준 · 방향은 trim · 소문자 후 비교, 방향 생략 시 기준별 기본 방향(BR-21~27)")
    void resolvesSort() {
        Sort idAsc = Sort.by(Direction.ASC, "id");

        assertThat(conditionOf("sort=+ID+").sort()).isEqualTo(idAsc);
        assertThat(conditionOf("sort=id&dir=DESC").sort()).isEqualTo(Sort.by(Direction.DESC, "id"));
        assertThat(conditionOf("sort=title&dir=desc").sort())
            .isEqualTo(Sort.by(Direction.DESC, "title").and(idAsc));
        assertThat(conditionOf("sort=unit").sort()).isEqualTo(
            Sort.by(Direction.ASC, "unit.code").and(Sort.by(Direction.DESC, "level")).and(idAsc));
        assertThat(conditionOf("sort=level&dir=up").sort()).isEqualTo(Sort.by(Direction.DESC, "level").and(idAsc));
        assertThat(conditionOf("sort=created").sort()).isEqualTo(Sort.by(Direction.DESC, "createdAt").and(idAsc));
        assertThat(conditionOf("sort=price&dir=asc").sort()).isEqualTo(Sort.by(Direction.DESC, "level").and(idAsc));
    }
}

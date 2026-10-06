package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
class ItemSearchSortTest {

    private static final Sort ID_ASC = Sort.by(Direction.ASC, "id");
    private static final Sort DEFAULT_SORT = Sort.by(Direction.DESC, "level").and(ID_ASC);

    @Test
    @DisplayName("resolve: 기준 · 방향이 비면 level DESC, id ASC(BR-21)")
    void resolvesEmptyToDefault() {
        assertThat(ItemSearchSort.resolve("", "")).isEqualTo(DEFAULT_SORT);
    }

    @Test
    @DisplayName("resolve: 모르는 기준이면 방향을 줘도 무시하고 기본 정렬(BR-22)")
    void resolvesUnknownKeyToDefaultIgnoringDirection() {
        assertThat(ItemSearchSort.resolve("price", "asc")).isEqualTo(DEFAULT_SORT);
        assertThat(ItemSearchSort.resolve("price", "desc")).isEqualTo(DEFAULT_SORT);
    }

    @Test
    @DisplayName("resolve: 방향이 asc · desc 가 아니면 기준별 기본 방향(BR-24 · 25)")
    void resolvesUnknownDirectionToDefaultDirection() {
        assertThat(ItemSearchSort.resolve("id", "up")).isEqualTo(ID_ASC);
        assertThat(ItemSearchSort.resolve("title", "")).isEqualTo(Sort.by(Direction.ASC, "title").and(ID_ASC));
        assertThat(ItemSearchSort.resolve("created", "x"))
            .isEqualTo(Sort.by(Direction.DESC, "createdAt").and(ID_ASC));
    }

    @Test
    @DisplayName("resolve: 단원 오름차순 지정 시에도 2차 난이도는 내림차순 · 3차 id 오름차순(BR-26 · 27)")
    void resolvesUnitWithFixedTieBreakers() {
        assertThat(ItemSearchSort.resolve("unit", "asc")).isEqualTo(
            Sort.by(Direction.ASC, "unit.code").and(Sort.by(Direction.DESC, "level")).and(ID_ASC));
        assertThat(ItemSearchSort.resolve("level", "asc")).isEqualTo(Sort.by(Direction.ASC, "level").and(ID_ASC));
    }
}

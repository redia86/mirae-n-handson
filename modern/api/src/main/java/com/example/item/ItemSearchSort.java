package com.example.item;

import java.util.Arrays;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;

/**
 * 문항 검색 정렬 기준. 레거시 {@code legacy/item-bank-php/search.php:264-326} 의 ORDER BY 를 그대로 옮긴다.
 *
 * <ul>
 *   <li>방향을 주지 않으면 id · title · unit 은 오름차순, level · created 는 내림차순(BR-25).</li>
 *   <li>id 외에는 동률이면 방향과 관계없이 id 오름차순(BR-26), unit 은 2차로 난이도 내림차순(BR-27).</li>
 *   <li>기준이 비었거나 모르는 값이면 {@code level DESC, id ASC}(BR-21, BR-22).</li>
 * </ul>
 */
enum ItemSearchSort {

    ID("id", "id", Direction.ASC, Sort.unsorted()),
    TITLE("title", "title", Direction.ASC, Sort.by(Direction.ASC, "id")),
    UNIT("unit", "unit.code", Direction.ASC, Sort.by(Direction.DESC, "level").and(Sort.by(Direction.ASC, "id"))),
    LEVEL("level", "level", Direction.DESC, Sort.by(Direction.ASC, "id")),
    CREATED("created", "createdAt", Direction.DESC, Sort.by(Direction.ASC, "id"));

    private final String key;
    private final String property;
    private final Direction defaultDirection;
    private final Sort tieBreaker;

    ItemSearchSort(String key, String property, Direction defaultDirection, Sort tieBreaker) {
        this.key = key;
        this.property = property;
        this.defaultDirection = defaultDirection;
        this.tieBreaker = tieBreaker;
    }

    /**
     * 정렬 기준 · 방향(이미 trim · 소문자 변환된 값)을 {@link Sort} 로 바꾼다.
     * 방향이 {@code asc} · {@code desc} 가 아니면 방향 없음으로 본다(BR-24).
     */
    static Sort resolve(String sortKey, String dirKey) {
        Direction direction = switch (dirKey) {
            case "asc" -> Direction.ASC;
            case "desc" -> Direction.DESC;
            default -> null;
        };
        return Arrays.stream(values())
            .filter(sort -> sort.key.equals(sortKey))
            .findFirst()
            .map(sort -> sort.toSort(direction == null ? sort.defaultDirection : direction))
            .orElseGet(() -> LEVEL.toSort(Direction.DESC));
    }

    private Sort toSort(Direction direction) {
        return Sort.by(direction, property).and(tieBreaker);
    }
}

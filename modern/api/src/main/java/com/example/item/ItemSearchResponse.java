package com.example.item;

import java.util.List;

/**
 * 문항 검색 응답.
 *
 * @param rows    현재 페이지의 행(정렬 순서 그대로)
 * @param count   조건에 맞는 총 건수(페이지와 무관)
 * @param message 총 건수가 0 이면 {@code "검색 결과가 없습니다"}, 아니면 {@code null}
 */
public record ItemSearchResponse(List<ItemSearchRow> rows, long count, String message) {
}

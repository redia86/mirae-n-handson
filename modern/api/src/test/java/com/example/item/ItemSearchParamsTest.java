package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
class ItemSearchParamsTest {

    @Test
    @DisplayName("parse: + 는 공백, %XX 는 UTF-8 로 디코딩, 잘못된 % 는 그대로")
    void decodesLikePhp() {
        ItemSearchParams params = ItemSearchParams.parse("q=%EB%B6%84%EC%88%98+%ED%95%A9&tag=%ZZ&unit");

        assertThat(params.get("q")).isEqualTo("분수 합");
        assertThat(params.get("tag")).isEqualTo("%ZZ");
        assertThat(params.get("unit")).isEmpty();
        assertThat(params.get("level")).isNull();
    }

    @Test
    @DisplayName("parse: 같은 이름이 반복되면 뒤 값, 배열이면 첫 값(BR-20)")
    void resolvesDuplicatesAndArraysLikePhp() {
        assertThat(ItemSearchParams.parse("level=3&level=5").get("level")).isEqualTo("5");
        assertThat(ItemSearchParams.parse("level%5B%5D=3&level%5B%5D=5").get("level")).isEqualTo("3");
        assertThat(ItemSearchParams.parse("level[b]=4&level[a]=2").get("level")).isEqualTo("4");
        assertThat(ItemSearchParams.parse("level=3&level[]=5").get("level")).isEqualTo("5");
        assertThat(ItemSearchParams.parse("level[]=5&level=3").get("level")).isEqualTo("3");
        assertThat(ItemSearchParams.parse("level[0][x]=3").get("level")).isEqualTo("Array");
    }

    @Test
    @DisplayName("getOrDefault: 없는 파라미터는 기본값, 빈값은 빈 문자열")
    void getOrDefaultKeepsEmptyValue() {
        ItemSearchParams params = ItemSearchParams.parse("page=");

        assertThat(params.getOrDefault("page", "1")).isEmpty();
        assertThat(ItemSearchParams.parse(null).getOrDefault("page", "1")).isEqualTo("1");
    }
}

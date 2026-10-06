package com.example.item;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 문항 검색 쿼리 문자열을 레거시 PHP({@code $_GET})와 같은 방식으로 푼다.
 *
 * <ul>
 *   <li>같은 이름이 여러 번 오면 뒤 값이 앞 값을 덮어쓴다.</li>
 *   <li>{@code name[]=v} · {@code name[k]=v} 는 배열이 되고, 배열이면 첫 값을 쓴다
 *       ({@code legacy/item-bank-php/search.php:55-79}, BR-20).</li>
 *   <li>{@code name[a][b]=v} 처럼 중첩 배열이면 첫 값이 배열이라 PHP 의 {@code (string)} 변환 결과 {@code "Array"} 가 된다.</li>
 *   <li>{@code =} 없이 이름만 오면 빈 문자열이다.</li>
 * </ul>
 * Spring 의 {@code @RequestParam} 은 중복 값을 쉼표로 합치고 {@code name[]} 을 다른 이름으로 보므로 쓰지 않는다.
 */
final class ItemSearchParams {

    private static final int HEX_RADIX = 16;
    /** {@code %} 뒤에 오는 16진수 자리 수. */
    private static final int PERCENT_HEX_DIGITS = 2;
    private static final String PHP_ARRAY_TO_STRING = "Array";
    /** PHP 가 정수 키로 바꾸는 배열 키(long 범위 안의 10진수). */
    private static final Pattern INT_KEY = Pattern.compile("0|[1-9][0-9]{0,17}");

    private final Map<String, Object> values;

    private ItemSearchParams(Map<String, Object> values) {
        this.values = values;
    }

    /** 원본 쿼리 문자열(디코딩 전, {@code null} 가능)을 푼다. */
    static ItemSearchParams parse(String rawQuery) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return new ItemSearchParams(values);
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            put(values, name, value);
        }
        return new ItemSearchParams(values);
    }

    /** 파라미터 값. 없으면 {@code null}, 배열이면 첫 값. */
    String get(String name) {
        Object value = values.get(name);
        if (value instanceof ArrayValue array) {
            return array.first();
        }
        return (String) value;
    }

    /** 파라미터 값. 없으면 {@code defaultValue}. */
    String getOrDefault(String name, String defaultValue) {
        String value = get(name);
        return value == null ? defaultValue : value;
    }

    private static void put(Map<String, Object> values, String name, String value) {
        int open = name.indexOf('[');
        int close = open < 0 ? -1 : name.indexOf(']', open);
        if (open <= 0 || close < 0) {
            if (open != 0) {
                values.put(name, value);
            }
            return;
        }
        String base = name.substring(0, open);
        String key = name.substring(open + 1, close);
        boolean nested = name.indexOf('[', close) >= 0;
        Object current = values.get(base);
        ArrayValue array = current instanceof ArrayValue existing ? existing : new ArrayValue();
        array.put(key, nested ? PHP_ARRAY_TO_STRING : value);
        values.put(base, array);
    }

    /** PHP {@code urldecode} 와 같이 {@code +} 는 공백, 잘못된 {@code %} 인코딩은 그대로 둔다. */
    private static String decode(String raw) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            byte b = bytes[i];
            if (b == '+') {
                out.write(' ');
            } else if (b == '%' && i + PERCENT_HEX_DIGITS < bytes.length
                    && isHex(bytes[i + 1]) && isHex(bytes[i + PERCENT_HEX_DIGITS])) {
                int high = Character.digit(bytes[i + 1], HEX_RADIX);
                int low = Character.digit(bytes[i + PERCENT_HEX_DIGITS], HEX_RADIX);
                out.write(high * HEX_RADIX + low);
                i += PERCENT_HEX_DIGITS;
            } else {
                out.write(b);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static boolean isHex(byte b) {
        return Character.digit(b, HEX_RADIX) >= 0;
    }

    /** PHP 배열 하나. 빈 키는 다음 정수 인덱스로 넣고, 같은 키는 값을 덮어쓴다(위치는 유지). */
    private static final class ArrayValue {

        private final Map<String, String> entries = new LinkedHashMap<>();
        private long nextIndex;

        void put(String key, String value) {
            String resolved = key;
            if (key.isEmpty()) {
                resolved = Long.toString(nextIndex);
            }
            if (INT_KEY.matcher(resolved).matches()) {
                nextIndex = Math.max(nextIndex, Long.parseLong(resolved) + 1);
            }
            entries.put(resolved, value);
        }

        String first() {
            return entries.values().iterator().next();
        }
    }
}

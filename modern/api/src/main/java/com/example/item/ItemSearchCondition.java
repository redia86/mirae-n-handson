package com.example.item;

import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.data.domain.Sort;

/**
 * 문항 검색 조건. 레거시 {@code buildSearchQuery}({@code legacy/item-bank-php/search.php:42-564})의 입력 처리를 옮긴다.
 * 형식이 이상한 값도 오류로 막지 않고 레거시와 같은 값으로 바꿔 조회한다.
 *
 * @param keyword  키워드. 없으면 {@code null}. trim 후 100자까지(BR-05)
 * @param unitCode 단원 코드. 없으면 {@code null}. trim 만 하고 대소문자는 DB 비교에 맡긴다(BR-07, BR-10)
 * @param level    난이도. {@code null} 이면 난이도 조건 없음 → {@code level < 5}(BR-12).
 *                 {@code 1}~{@code 5} 밖의 값도 PHP {@code (int)} 변환 값 그대로 담는다(BR-14)
 * @param tag      태그 이름. 없으면 {@code null}. trim 후 50자까지(BR-16)
 * @param sort     정렬(BR-21~BR-27)
 * @param page     페이지 번호. 1~999(BR-31, BR-32)
 */
record ItemSearchCondition(String keyword, String unitCode, Long level, String tag, Sort sort, int page) {

    static final int KEYWORD_MAX_LENGTH = 100;
    static final int TAG_MAX_LENGTH = 50;
    static final int MAX_PAGE = 999;

    /** PHP {@code trim()} 이 지우는 문자: 공백, \t, \n, \r, \0, \x0B. */
    private static final String PHP_TRIM_CHARS = " \t\n\r\0\u000B";
    /** 난이도 1~5 한 자리({@code search.php:217}). */
    private static final Pattern LEVEL_IN_RANGE = Pattern.compile("\\A[1-5]\\z");
    /** 페이지 숫자({@code search.php:337}). PCRE 의 {@code $} 는 끝의 \n 하나 앞에서도 맞는다. */
    private static final Pattern PAGE_DIGITS = Pattern.compile("\\A([0-9]+)\\n?\\z");
    /** PHP 숫자 문자열의 앞부분(앞 공백 · 부호 · 소수점 · 지수). 뒤에 붙은 문자는 무시된다. */
    private static final Pattern PHP_NUMERIC_PREFIX =
        Pattern.compile("\\A[ \\t\\n\\r\\u000B\\f]*([+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)");
    private static final String DEFAULT_PAGE = "1";

    /** 쿼리 파라미터 → 검색 조건. 파라미터 이름은 레거시와 같다(q, unit, level, tag, sort, dir, page). */
    static ItemSearchCondition from(ItemSearchParams params) {
        String keyword = truncate(phpTrim(params.getOrDefault("q", "")), KEYWORD_MAX_LENGTH);
        String unitCode = phpTrim(params.getOrDefault("unit", ""));
        String levelRaw = phpTrim(params.getOrDefault("level", ""));
        String tag = truncate(phpTrim(params.getOrDefault("tag", "")), TAG_MAX_LENGTH);
        String sortKey = asciiLower(phpTrim(params.getOrDefault("sort", "")));
        String dirKey = asciiLower(phpTrim(params.getOrDefault("dir", "")));
        String pageRaw = params.getOrDefault("page", DEFAULT_PAGE);

        return new ItemSearchCondition(
            emptyToNull(keyword),
            emptyToNull(unitCode),
            parseLevel(levelRaw),
            emptyToNull(tag),
            ItemSearchSort.resolve(sortKey, dirKey),
            parsePage(pageRaw));
    }

    /** 빈값이면 {@code null}, 1~5 한 자리면 그 값, 그 밖이면 PHP {@code (int)} 변환 값({@code search.php:213-229}). */
    static Long parseLevel(String trimmed) {
        if (trimmed.isEmpty()) {
            return null;
        }
        if (LEVEL_IN_RANGE.matcher(trimmed).matches()) {
            return Long.parseLong(trimmed);
        }
        return phpIntval(trimmed);
    }

    /** 숫자만(끝 \n 하나 허용)이면 그 값, 아니면 1. 1 미만은 1, 999 초과는 999({@code search.php:335-347}). */
    static int parsePage(String raw) {
        Matcher matcher = PAGE_DIGITS.matcher(raw);
        if (!matcher.matches()) {
            return 1;
        }
        BigInteger page = new BigInteger(matcher.group(1));
        if (page.compareTo(BigInteger.valueOf(MAX_PAGE)) > 0) {
            return MAX_PAGE;
        }
        return Math.max(page.intValue(), 1);
    }

    /**
     * PHP 7.4 의 문자열 → {@code (int)} 변환. 앞쪽 숫자 부분만 읽고({@code "3abc"} → 3, {@code "3.9"} → 3,
     * {@code "1e1"} → 10), 숫자로 시작하지 않으면 0. long 범위를 넘으면 최댓값 · 최솟값, 무한대는 0.
     */
    static long phpIntval(String value) {
        Matcher matcher = PHP_NUMERIC_PREFIX.matcher(value);
        if (!matcher.find()) {
            return 0;
        }
        String number = matcher.group(1);
        boolean integral = number.indexOf('.') < 0 && number.indexOf('e') < 0 && number.indexOf('E') < 0;
        if (integral) {
            BigInteger parsed = new BigInteger(number.startsWith("+") ? number.substring(1) : number);
            if (parsed.bitLength() < Long.SIZE) {
                return parsed.longValue();
            }
        }
        double parsed = Double.parseDouble(number);
        if (Double.isInfinite(parsed) || Double.isNaN(parsed)) {
            return 0;
        }
        return (long) parsed;
    }

    /** PHP {@code trim()} — Java {@code String.trim()} 과 지우는 문자가 다르다. */
    static String phpTrim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && PHP_TRIM_CHARS.indexOf(value.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && PHP_TRIM_CHARS.indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        return value.substring(start, end);
    }

    /** PHP {@code mb_substr($s, 0, max, 'UTF-8')} — 코드 포인트 기준 앞에서 max 자. */
    static String truncate(String value, int max) {
        if (value.codePointCount(0, value.length()) <= max) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, max));
    }

    /** PHP 7.4 {@code strtolower()} — ASCII 대문자만 바꾼다. */
    static String asciiLower(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            out.append(c >= 'A' && c <= 'Z' ? Character.toLowerCase(c) : c);
        }
        return out.toString();
    }

    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }
}

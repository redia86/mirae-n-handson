package com.example.item;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

/**
 * 문항 검색 WHERE 조건. 레거시는 조건을 모두 AND 로 잇는다({@code legacy/item-bank-php/search.php:44}, BR-03).
 * 문자열 비교(LIKE · =)는 Java 에서 하지 않고 DB 에 맡긴다 — 대소문자 처리가 DB 콜레이션을 따르게 하기 위해서다.
 */
final class ItemSearchSpecifications {

    /** 난이도 조건이 없을 때의 상한(미만). 레거시 그대로 5 를 뺀다({@code search.php:215}, BR-12). */
    static final int LEVEL_UNSPECIFIED_UPPER_BOUND = 5;

    private ItemSearchSpecifications() {
    }

    static Specification<Item> matching(ItemSearchCondition condition) {
        return Specification.allOf(
            active(),
            keyword(condition.keyword()),
            unitCode(condition.unitCode()),
            level(condition.level()),
            tag(condition.tag()));
    }

    /** 공개 문항만. 뷰 {@code v_item_public}({@code db/mariadb/init/01-schema.sql:51-70})의 조건과 같다(BR-01). */
    static Specification<Item> active() {
        return (root, query, cb) -> cb.equal(root.get("status"), ItemStatus.ACTIVE);
    }

    /** 제목 또는 지문 LIKE. {@code %} · {@code _} 를 이스케이프하지 않는다({@code search.php:97-98}, BR-04). */
    static Specification<Item> keyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String pattern = "%" + keyword + "%";
        return (root, query, cb) -> cb.or(cb.like(root.get("title"), pattern), cb.like(root.get("stem"), pattern));
    }

    /** 단원 코드 일치({@code search.php:118}, BR-07). 없는 코드여도 조회는 그대로 한다(BR-11). */
    static Specification<Item> unitCode(String unitCode) {
        if (unitCode == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("unit").get("code"), unitCode);
    }

    /** 난이도({@code search.php:213-229}). 없으면 5 미만, 있으면 그 값과 일치. int 범위 밖이면 0건. */
    static Specification<Item> level(Long level) {
        if (level == null) {
            return (root, query, cb) -> cb.lessThan(root.get("level"), LEVEL_UNSPECIFIED_UPPER_BOUND);
        }
        if (level < Integer.MIN_VALUE || level > Integer.MAX_VALUE) {
            return (root, query, cb) -> cb.disjunction();
        }
        int value = level.intValue();
        return (root, query, cb) -> cb.equal(root.get("level"), value);
    }

    /** 이름이 같은 태그가 달린 문항({@code search.php:244-245}, BR-15). 없는 태그면 0건(BR-18). */
    static Specification<Item> tag(String tag) {
        if (tag == null) {
            return null;
        }
        return (root, query, cb) -> {
            Subquery<Integer> sub = query.subquery(Integer.class);
            Root<Item> correlated = sub.correlate(root);
            Join<Item, Tag> tags = correlated.join("tags");
            sub.select(cb.literal(1)).where(cb.equal(tags.get("name"), tag));
            return cb.exists(sub);
        };
    }
}

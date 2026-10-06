package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;

/** 검색 Specification 의 개별 조건 — H2(MariaDB 모드)에 실제 SQL 을 날린다. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ItemSearchSpecificationsTest {

    private static final int REVIEWING_LEVEL = 3;
    private static final int HIGH_LEVEL = 4;
    private static final int TOP_LEVEL = 5;
    private static final int DAYS_LATER = 2;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ItemRepository itemRepository;

    @BeforeEach
    void seed() {
        Unit fraction = entityManager.persist(ItemFixtures.unit(1, "M5-1", "분수의 덧셈과 뺄셈", TOP_LEVEL));
        Tag word = entityManager.persist(ItemFixtures.tag(1, "문장제"));

        LocalDateTime base = ItemFixtures.SEED_TIME;
        entityManager.persist(new Item(fraction, "첫 문항", "지문에만 사과가 있다", 1, ItemStatus.ACTIVE, base));
        Item later = new Item(fraction, "둘째 문항", "본문", HIGH_LEVEL, ItemStatus.ACTIVE, base.plusDays(DAYS_LATER));
        later.addTag(word);
        entityManager.persist(later);
        entityManager.persist(new Item(fraction, "셋째 문항", "본문", 1, ItemStatus.ACTIVE, base.plusDays(1)));
        entityManager.persist(new Item(fraction, "검수 문항", "사과", REVIEWING_LEVEL, ItemStatus.REVIEWING, base));
        entityManager.persist(new Item(fraction, "어려운 문항", "본문", TOP_LEVEL, ItemStatus.ACTIVE, base));
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("active: 공개 문항만 남기고 검수 중 문항은 뺀다")
    void activeExcludesNonPublicItems() {
        List<Item> items = itemRepository.findAll(ItemSearchSpecifications.active());

        assertThat(items).extracting(Item::getTitle)
            .containsExactlyInAnyOrder("첫 문항", "둘째 문항", "셋째 문항", "어려운 문항");
    }

    @Test
    @DisplayName("keyword: 제목뿐 아니라 지문(stem)도 LIKE 로 찾는다")
    void keywordMatchesStem() {
        assertThat(titles("q=%EC%82%AC%EA%B3%BC")).containsExactly("첫 문항");
    }

    @Test
    @DisplayName("keyword: _ 와 % 를 이스케이프하지 않아 와일드카드로 동작한다(BR-04)")
    void keywordWildcardsAreNotEscaped() {
        assertThat(titles("q=_&sort=id")).containsExactly("첫 문항", "둘째 문항", "셋째 문항");
        assertThat(titles("q=%25&sort=id")).containsExactly("첫 문항", "둘째 문항", "셋째 문항");
    }

    @Test
    @DisplayName("level: int 범위를 넘는 난이도는 0건, 검수 문항의 난이도를 지정해도 공개 문항만 나온다")
    void levelOutOfIntRangeReturnsNothing() {
        assertThat(search("level=99999999999999999999").getTotalElements()).isZero();
        assertThat(search("level=3").getTotalElements()).isZero();
        assertThat(titles("level=5")).containsExactly("어려운 문항");
    }

    @Test
    @DisplayName("level: Long 범위를 직접 넘겨도 int 범위 밖이면 0건")
    void levelSpecificationRejectsLongOutsideInt() {
        Specification<Item> spec = ItemSearchSpecifications.level((long) Integer.MAX_VALUE + 1);

        assertThat(itemRepository.findAll(spec)).isEmpty();
    }

    @Test
    @DisplayName("sort=created: 등록일 내림차순(BR-25), 오름차순 지정 시 반대, 동률은 id 오름차순")
    void sortsByCreatedAt() {
        assertThat(titles("sort=created")).containsExactly("둘째 문항", "셋째 문항", "첫 문항");
        assertThat(titles("sort=created&dir=asc")).containsExactly("첫 문항", "셋째 문항", "둘째 문항");
    }

    @Test
    @DisplayName("tag: 태그 조건은 다른 조건과 AND, 같은 문항이 중복되지 않는다")
    void tagDoesNotDuplicateRows() {
        Page<Item> page = search("tag=%EB%AC%B8%EC%9E%A5%EC%A0%9C");

        assertThat(page.getContent()).extracting(Item::getTitle).containsExactly("둘째 문항");
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(search("tag=%EB%AC%B8%EC%9E%A5%EC%A0%9C&level=1").getTotalElements()).isZero();
    }

    private List<String> titles(String rawQuery) {
        return search(rawQuery).getContent().stream().map(Item::getTitle).toList();
    }

    private Page<Item> search(String rawQuery) {
        ItemSearchCondition condition = ItemSearchCondition.from(ItemSearchParams.parse(rawQuery));
        Sort sort = condition.sort();
        PageRequest pageRequest = PageRequest.of(condition.page() - 1, ItemSearchService.PAGE_SIZE, sort);
        return itemRepository.findAll(ItemSearchSpecifications.matching(condition), pageRequest);
    }
}

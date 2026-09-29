package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

/** 저장소 슬라이스 — H2(MariaDB 모드)에 실제 SQL 을 날린다. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ItemRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private UnitRepository unitRepository;

    private Unit fraction;

    @BeforeEach
    void seed() {
        fraction = entityManager.persist(ItemFixtures.unit(1, "M5-1", "분수의 덧셈과 뺄셈", 5));
        Unit decimal = entityManager.persist(ItemFixtures.unit(3, "M5-3", "소수의 곱셈", 5));
        Tag calc = entityManager.persist(ItemFixtures.tag(1, "계산"));
        Tag word = entityManager.persist(ItemFixtures.tag(2, "문장제"));

        entityManager.persist(ItemFixtures.item(null, fraction, "분모가 같은 분수의 덧셈", 2, ItemStatus.ACTIVE, calc));
        entityManager.persist(ItemFixtures.item(null, fraction, "분수 문장제", 4, ItemStatus.ACTIVE, calc, word));
        entityManager.persist(ItemFixtures.item(null, fraction, "검수 중 문항", 3, ItemStatus.REVIEWING));
        entityManager.persist(ItemFixtures.item(null, fraction, "삭제된 문항", 5, ItemStatus.DELETED));
        entityManager.persist(ItemFixtures.item(null, fraction, "난이도 5 분수", 5, ItemStatus.ACTIVE, word));
        entityManager.persist(ItemFixtures.item(null, decimal, "소수 곱셈", 1, ItemStatus.ACTIVE));
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("findByUnitCodeAndStatus: 공개 문항만, level DESC · id ASC, 태그 함께 로드")
    void findsActiveItemsOrderedWithTags() {
        List<Item> items = itemRepository.findByUnitCodeAndStatus("M5-1", ItemStatus.ACTIVE);

        assertThat(items).hasSize(3);
        assertThat(items).extracting(Item::getTitle)
            .containsExactly("난이도 5 분수", "분수 문장제", "분모가 같은 분수의 덧셈");
        assertThat(items).allSatisfy(item -> assertThat(item.getStatus()).isEqualTo("A"));
        assertThat(items.get(1).getTags()).extracting(Tag::getName).containsExactly("계산", "문장제");
    }

    @Test
    @DisplayName("findWithDetailsById: 단원 · 태그가 한 번에 로드된다(지연 로딩 아님)")
    void findWithDetailsLoadsUnitAndTags() {
        Integer id = itemRepository.findByUnitCodeAndStatus("M5-1", ItemStatus.ACTIVE).get(1).getId();
        entityManager.clear();

        Item item = itemRepository.findWithDetailsById(id).orElseThrow();

        assertThat(Hibernate.isInitialized(item.getUnit())).isTrue();
        assertThat(Hibernate.isInitialized(item.getTags())).isTrue();
        assertThat(item.getUnit().getCode()).isEqualTo("M5-1");
        assertThat(item.getTags()).hasSize(2);
    }

    @Test
    @DisplayName("countByUnitIdAndStatus: 상태별 문항 수")
    void countsByStatus() {
        assertThat(itemRepository.countByUnitIdAndStatus(fraction.getId(), ItemStatus.ACTIVE)).isEqualTo(3);
        assertThat(itemRepository.countByUnitIdAndStatus(fraction.getId(), ItemStatus.DELETED)).isEqualTo(1);
        assertThat(unitRepository.findByCode("M5-3")).isPresent();
    }

    @Test
    @DisplayName("검색 Specification: 조건 없음은 공개 · 난이도 5 미만만, level DESC · id ASC")
    void searchWithoutConditionExcludesLevelFive() {
        Page<Item> page = search("");

        assertThat(page.getContent()).extracting(Item::getTitle)
            .containsExactly("분수 문장제", "분모가 같은 분수의 덧셈", "소수 곱셈");
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("검색 Specification: 키워드 · 단원 · 난이도 · 태그를 AND 로 잇는다")
    void searchCombinesConditionsWithAnd() {
        assertThat(search("tag=%EB%AC%B8%EC%9E%A5%EC%A0%9C&level=5").getContent())
            .extracting(Item::getTitle).containsExactly("난이도 5 분수");
        assertThat(search("q=%EB%AC%B8%EC%9E%A5%EC%A0%9C&unit=M5-1").getContent())
            .extracting(Item::getTitle).containsExactly("분수 문장제");
        assertThat(search("unit=X9-99").getTotalElements()).isZero();
        assertThat(search("level=-1").getTotalElements()).isZero();
        assertThat(search("tag=%EC%97%86%EB%8A%94%ED%83%9C%EA%B7%B8").getTotalElements()).isZero();
    }

    @Test
    @DisplayName("검색 Specification: 단원 내림차순 정렬은 2차로 난이도 내림차순, 범위 밖 페이지는 빈 목록과 총 건수")
    void searchSortsByUnitAndPagesBeyondEnd() {
        assertThat(search("sort=unit&dir=desc").getContent()).extracting(Item::getTitle)
            .containsExactly("소수 곱셈", "분수 문장제", "분모가 같은 분수의 덧셈");

        Page<Item> beyond = search("page=2");
        assertThat(beyond.getContent()).isEmpty();
        assertThat(beyond.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("findWithDetailsByIdIn: 여러 건의 단원 · 태그를 한 번에 로드한다")
    void findWithDetailsByIdInLoadsTags() {
        List<Integer> ids = search("").getContent().stream().map(Item::getId).toList();
        entityManager.clear();

        List<Item> items = itemRepository.findWithDetailsByIdIn(ids);

        assertThat(items).hasSize(ids.size());
        assertThat(items).allSatisfy(item -> assertThat(Hibernate.isInitialized(item.getTags())).isTrue());
    }

    private Page<Item> search(String rawQuery) {
        ItemSearchCondition condition = ItemSearchCondition.from(ItemSearchParams.parse(rawQuery));
        PageRequest pageRequest = PageRequest.of(condition.page() - 1, ItemSearchService.PAGE_SIZE, condition.sort());
        return itemRepository.findAll(ItemSearchSpecifications.matching(condition), pageRequest);
    }
}

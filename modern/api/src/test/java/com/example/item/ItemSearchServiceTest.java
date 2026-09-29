package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;

@ExtendWith(MockitoExtension.class)
@ActiveProfiles("test")
class ItemSearchServiceTest {

    private static final long TOTAL = 21;
    private static final int LAST_PAGE = 999;

    @Mock
    private ItemRepository itemRepository;

    @InjectMocks
    private ItemSearchService itemSearchService;

    private final Unit unit = ItemFixtures.unit(1, "M5-1", "분수의 덧셈과 뺄셈", 5);

    private static ItemSearchCondition conditionOf(String rawQuery) {
        return ItemSearchCondition.from(ItemSearchParams.parse(rawQuery));
    }

    @Test
    @DisplayName("search: 페이지 쿼리 순서대로 행을 만들고, 태그는 따로 읽은 결과를 붙인다")
    void searchKeepsPageOrderAndAttachesTags() {
        Item hard = ItemFixtures.item(18, unit, "분수의 뺄셈 문장제", 4, ItemStatus.ACTIVE,
            ItemFixtures.tag(2, "문장제"), ItemFixtures.tag(4, "오답률높음"));
        Item easy = ItemFixtures.item(1, unit, "분모가 같은 분수의 덧셈", 1, ItemStatus.ACTIVE);
        when(itemRepository.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(hard, easy), Pageable.ofSize(ItemSearchService.PAGE_SIZE), TOTAL));
        when(itemRepository.findWithDetailsByIdIn(List.of(18, 1))).thenReturn(List.of(easy, hard));

        ItemSearchResponse response = itemSearchService.search(conditionOf("unit=M5-1"));

        assertThat(response.rows()).extracting(ItemSearchRow::id).containsExactly(18, 1);
        assertThat(response.rows().get(0).unit()).isEqualTo("M5-1");
        assertThat(response.rows().get(0).tags()).containsExactly("문장제", "오답률높음");
        assertThat(response.rows().get(1).tags()).isEmpty();
        assertThat(response.count()).isEqualTo(TOTAL);
        assertThat(response.message()).isNull();
    }

    @Test
    @DisplayName("search: 총 0건이면 안내 문구, 태그 조회는 하지 않는다")
    void searchWithNoResultReturnsMessage() {
        when(itemRepository.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(ItemSearchService.PAGE_SIZE), 0));

        ItemSearchResponse response = itemSearchService.search(conditionOf("unit=X9-99"));

        assertThat(response.rows()).isEmpty();
        assertThat(response.count()).isZero();
        assertThat(response.message()).isEqualTo(ItemSearchService.NO_RESULT_MESSAGE);
        verify(itemRepository, never()).findWithDetailsByIdIn(any());
    }

    @Test
    @DisplayName("search: 페이지 번호 · 크기 20 · 정렬을 Pageable 로 넘긴다")
    void searchPassesPageRequest() {
        when(itemRepository.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(ItemSearchService.PAGE_SIZE), 0));
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);

        ItemSearchCondition condition = conditionOf("page=1000&sort=title");
        itemSearchService.search(condition);

        verify(itemRepository).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(LAST_PAGE - 1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(ItemSearchService.PAGE_SIZE);
        assertThat(pageable.getValue().getSort()).isEqualTo(condition.sort());
        verify(itemRepository, never()).findWithDetailsByIdIn(any());
    }
}

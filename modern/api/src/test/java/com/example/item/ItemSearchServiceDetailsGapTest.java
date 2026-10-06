package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;

/** 페이지 조회와 상세 조회 사이에 문항이 지워지거나 비공개로 바뀐 경우. */
@ExtendWith(MockitoExtension.class)
@ActiveProfiles("test")
class ItemSearchServiceDetailsGapTest {

    private static final long TOTAL = 3;
    private static final int KEPT_ID = 1;
    private static final int GONE_ID = 2;
    private static final int HIDDEN_ID = 3;

    @Mock
    private ItemRepository itemRepository;

    @InjectMocks
    private ItemSearchService itemSearchService;

    private final Unit unit = ItemFixtures.unit(1, "M5-1", "분수의 덧셈과 뺄셈", 5);

    @Test
    @DisplayName("search: 상세 조회에서 빠졌거나 비공개가 된 문항은 행에서 뺀다(예외 없이)")
    void searchDropsItemsMissingOrHiddenInDetails() {
        Item kept = ItemFixtures.item(KEPT_ID, unit, "남은 문항", 1, ItemStatus.ACTIVE);
        Item gone = ItemFixtures.item(GONE_ID, unit, "지워진 문항", 1, ItemStatus.ACTIVE);
        Item hiddenOnPage = ItemFixtures.item(HIDDEN_ID, unit, "비공개 전환 문항", 1, ItemStatus.ACTIVE);
        Item hiddenInDetails = ItemFixtures.item(HIDDEN_ID, unit, "비공개 전환 문항", 1, ItemStatus.REVIEWING);
        when(itemRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(
            List.of(kept, gone, hiddenOnPage), Pageable.ofSize(ItemSearchService.PAGE_SIZE), TOTAL));
        when(itemRepository.findWithDetailsByIdIn(List.of(KEPT_ID, GONE_ID, HIDDEN_ID)))
            .thenReturn(List.of(kept, hiddenInDetails));

        ItemSearchResponse response = itemSearchService.search(
            ItemSearchCondition.from(ItemSearchParams.parse("unit=M5-1")));

        assertThat(response.rows()).extracting(ItemSearchRow::id).containsExactly(KEPT_ID);
    }
}

package com.example.item;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 문항 검색 서비스. 레거시 {@code legacy/item-bank-php/search.php} 의 조회 동작을 그대로 옮긴다.
 * 없는 단원 · 태그, 범위 밖 난이도 · 페이지도 오류가 아니라 0건(또는 빈 페이지)으로 응답한다.
 */
@Service
@Transactional(readOnly = true)
public class ItemSearchService {

    /** 한 페이지 건수({@code search.php:349,523}, BR-30). */
    static final int PAGE_SIZE = 20;
    /** 총 건수가 0 일 때의 안내 문구({@code search.php:657-658}, BR-33). */
    static final String NO_RESULT_MESSAGE = "검색 결과가 없습니다";

    private static final Logger log = LoggerFactory.getLogger(ItemSearchService.class);

    private final ItemRepository itemRepository;

    public ItemSearchService(ItemRepository itemRepository) {
        this.itemRepository = itemRepository;
    }

    /**
     * 조건에 맞는 공개 문항의 한 페이지와 총 건수.
     * 페이지 쿼리는 태그 없이 읽고, 그 페이지의 태그는 한 번에 따로 읽는다(컬렉션 fetch 와 페이징을 같이 쓰지 않는다).
     */
    public ItemSearchResponse search(ItemSearchCondition condition) {
        PageRequest pageRequest = PageRequest.of(condition.page() - 1, PAGE_SIZE, condition.sort());
        Page<Item> page = itemRepository.findAll(ItemSearchSpecifications.matching(condition), pageRequest);

        List<Integer> ids = page.getContent().stream().map(Item::getId).toList();
        Map<Integer, Item> details = ids.isEmpty()
            ? Map.of()
            : itemRepository.findWithDetailsByIdIn(ids).stream()
                .collect(Collectors.toMap(Item::getId, Function.identity()));
        List<ItemSearchRow> rows = ids.stream().map(id -> ItemSearchRow.from(details.get(id))).toList();

        long total = page.getTotalElements();
        log.debug("item search page {} returned {} of {} items", condition.page(), rows.size(), total);
        return new ItemSearchResponse(rows, total, total == 0 ? NO_RESULT_MESSAGE : null);
    }
}

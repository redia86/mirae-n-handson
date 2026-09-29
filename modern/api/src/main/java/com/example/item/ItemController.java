package com.example.item;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 문항 API. 컨트롤러는 서비스만 호출하고, 예외는 {@code GlobalExceptionHandler} 가 처리한다.
 */
@RestController
@RequestMapping("/api")
public class ItemController {

    private final ItemService itemService;
    private final ItemSearchService itemSearchService;

    public ItemController(ItemService itemService, ItemSearchService itemSearchService) {
        this.itemService = itemService;
        this.itemSearchService = itemSearchService;
    }

    /**
     * {@code GET /api/items/search} — 문항 검색(레거시 {@code search.php}). 파라미터 이름은 레거시와 같다:
     * {@code q, unit, level, tag, sort, dir, page}. 쿼리 문자열은 레거시(PHP)와 같은 방식으로 푼다.
     */
    @GetMapping("/items/search")
    public ItemSearchResponse searchItems(HttpServletRequest request) {
        ItemSearchParams params = ItemSearchParams.parse(request.getQueryString());
        return itemSearchService.search(ItemSearchCondition.from(params));
    }

    /** {@code GET /api/items/{id}} — 문항 단건. */
    @GetMapping("/items/{id}")
    public ItemResponse getItem(@PathVariable Integer id) {
        return itemService.getItem(id);
    }

    /** {@code GET /api/units/{code}/items} — 단원의 공개 문항 목록. */
    @GetMapping("/units/{code}/items")
    public List<ItemResponse> listItemsByUnit(@PathVariable String code) {
        return itemService.listActiveItemsByUnit(code);
    }
}

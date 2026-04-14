package com.listerizer.api.item;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/items")
public class ItemController {

    private final ItemService itemService;

    public ItemController(ItemService itemService) {
        this.itemService = itemService;
    }

    @PostMapping
    public ResponseEntity<ItemResponse> create(@RequestBody ItemRequest request) {
        InsertResult result = itemService.create(request);
        HttpStatus status = result.isNew() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(toResponse(result.item()));
    }

    @GetMapping
    public List<ItemResponse> list() {
        return itemService.list().stream()
                .map(this::toResponse)
                .toList();
    }

    private ItemResponse toResponse(Item item) {
        return new ItemResponse(item.id(), item.url(), item.createTime());
    }
}

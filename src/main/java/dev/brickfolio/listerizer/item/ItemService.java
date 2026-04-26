package dev.brickfolio.listerizer.item;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

@Service
public class ItemService {

    private final ItemRepository repository;

    public ItemService(ItemRepository repository) {
        this.repository = repository;
    }

    public InsertResult create(ItemRequest request) {
        validateRequest(request);
        // createTime is already normalized to ISO 8601 UTC by CreateTimeDeserializer
        return repository.insertOrFetch(request.url(), request.createTime());
    }

    public List<Item> list() {
        return repository.findAll();
    }

    private void validateRequest(ItemRequest request) {
        if(request == null) {
            throw new ValidationException("Request must be valid and non-empty");
        }
        if (request.url() == null || request.url().isBlank()) {
            throw new ValidationException("url is required and must be a valid URL");
        }
        try {
            URI uri = new URI(request.url());
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new ValidationException("url is required and must be a valid URL");
            }
        } catch (URISyntaxException e) {
            throw new ValidationException("url is required and must be a valid URL");
        }
    }
}

package dev.brickfolio.listerizer.service;

import dev.brickfolio.listerizer.api.ItemRequest;
import dev.brickfolio.listerizer.domain.Item;
import dev.brickfolio.listerizer.repository.ItemRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Optional;

@Service
public class ItemService {

    private final ItemRepository repository;

    public ItemService(ItemRepository repository) {
        this.repository = repository;
    }

    // Not @Transactional: CrudRepository.save() runs in its own transaction and commits
    // immediately, so a duplicate-URL DataIntegrityViolationException is thrown from save()
    // and caught here. An outer transaction would defer the flush past this catch block.
    public InsertResult create(ItemRequest request) {
        validateRequest(request);
        long resolvedCreateTime = request.createTime() != null
                ? request.createTime()
                : System.currentTimeMillis() / 1000;
        try {
            Item saved = repository.save(new Item(
                    request.url(),
                    resolvedCreateTime,
                    request.title(),
                    Boolean.TRUE.equals(request.hasBeenRead())));
            return new InsertResult(saved, true);
        } catch (DataIntegrityViolationException e) {
            Item existing = repository.findByUrl(request.url())
                    .orElseThrow(() -> new IllegalStateException(
                            "URL conflict but no existing row found: " + request.url(), e));
            boolean needsUpdate = false;
            if (Boolean.TRUE.equals(request.hasBeenRead()) && !existing.hasBeenRead()) {
                existing.setHasBeenRead(true);
                needsUpdate = true;
            }
            if (request.title() != null && !request.title().isBlank()
                    && (existing.title() == null || existing.title().isBlank())) {
                existing.setTitle(request.title());
                needsUpdate = true;
            }
            if (needsUpdate) {
                existing = repository.save(existing);
            }
            return new InsertResult(existing, false);
        }
    }

    public List<Item> list() {
        return repository.findAllByOrderByIdAsc();
    }

    public Optional<Item> findRandomUnread() {
        return repository.findRandomUnread();
    }

    private void validateRequest(ItemRequest request) {
        if (request == null) {
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
        if (request.createTime() != null && request.createTime() < 0) {
            throw new ValidationException("createTime must be a non-negative epoch seconds integer");
        }
    }
}

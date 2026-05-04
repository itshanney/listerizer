package dev.brickfolio.listerizer.repository;

import dev.brickfolio.listerizer.domain.Item;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

public interface ItemRepository extends CrudRepository<Item, Long> {

    List<Item> findAll();

    Optional<Item> findByUrl(String url);

    List<Item> findAllByOrderByIdAsc();

    List<Item> findAllByHasBeenReadFalseOrderByIdAsc();

    @Query(value = "SELECT * FROM items WHERE has_been_read = 0 ORDER BY RAND() LIMIT 1", nativeQuery = true)
    Optional<Item> findRandomUnread();
}

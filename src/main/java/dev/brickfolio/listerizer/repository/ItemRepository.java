package dev.brickfolio.listerizer.repository;

import dev.brickfolio.listerizer.domain.Item;
import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

public interface ItemRepository extends CrudRepository<Item, Long> {
    
    List<Item> findAll();
    
    Optional<Item> findByUrl(String url);

    List<Item> findAllByOrderByIdAsc();
    
}

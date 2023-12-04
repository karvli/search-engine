package searchengine.model;

import jakarta.transaction.Transactional;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SiteRepository extends CrudRepository<Site, Integer> {
    Site findByName(String names);
    List<Site> findByNameIn(List<String> names);
    List<Site> findByStatusAndNameIn(IndexingStatus status, List<String> names);
}

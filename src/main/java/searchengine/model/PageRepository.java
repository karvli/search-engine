package searchengine.model;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PageRepository extends CrudRepository<Page, Integer> {

    List<Page> findBySiteAndPath(Site site, String path);
    List<Page> findBySiteAndPathIn(Site site, List<String> paths);

}

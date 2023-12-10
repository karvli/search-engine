package searchengine.model;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface IndexRepository extends CrudRepository<Index, Integer> {
    List<Index> findByPage(Page page);
}

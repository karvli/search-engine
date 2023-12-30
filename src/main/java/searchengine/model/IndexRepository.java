package searchengine.model;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IndexRepository extends CrudRepository<Index, Integer> {
    List<Index> findByPage(Page page);
    List<Index> findByLemma(Lemma lemma);
    List<Index> findByLemmaAndPageIn(Lemma lemma, Iterable<Page> pages);
}

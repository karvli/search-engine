package searchengine.model;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LemmaRepository extends CrudRepository<Lemma, Integer> {
    List<Lemma> findBySiteAndLemmaIn(Site site, Iterable<String> lemma);

}

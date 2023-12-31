package searchengine.services;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.jsoup.Jsoup;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import searchengine.config.SitesList;
import searchengine.dto.search.SearchData;
import searchengine.dto.search.SearchResponse;
import searchengine.model.*;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {

    private final SitesList sitesSettings;
    private final ApplicationContext applicationContext;
    private final SiteRepository siteRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;

    @Override
    public SearchResponse searchSite(String siteUrl, String query, int limit, int offset) {
        if (siteUrl.endsWith("/")) {
            // Далее ожидается формат без слэша на конце
            siteUrl = siteUrl.substring(0, siteUrl.length() - 1);
        }
        val url = siteUrl.toLowerCase();

        var notInSettings = sitesSettings.getSites().stream()
                .map(searchengine.config.Site::getUrl)
                .noneMatch(s -> s.equals(url));
        if (notInSettings) {
            return SearchResponse.builder()
                    .result(false)
                    .error("Сайт не указан в настройках индексации")
                    .build();
        }

        var site = siteRepository.findByUrl(siteUrl);
        if (site == null || site.getStatus() == IndexingStatus.INDEXING) {
            // Индексация ещё не выполнена. Failed не учитывается, т.к. это тоже статус "завершения" индексации.
            return SearchResponse.builder()
                    .result(false)
                    .error("Индексация сайта ещё не завершена")
                    .build();
        }

        var sites = List.of(site);
        return search(sites, query, limit, offset);
    }

    @Override
    public SearchResponse searchAllSites(String query, int limit, int offset) {
        // Дополнительный отбор на случай изменения состава сайтов в настройках.
        var sitesInSettings = sitesSettings.getSites().stream()
                .map(searchengine.config.Site::getUrl)
                .toList();

        var sitesInDB = siteRepository.findByUrlIn(sitesInSettings);

        if (sitesInSettings.size() != sitesInDB.size()) {
            return SearchResponse.builder()
                    .result(false)
                    .error("Индексация части сайтов из настроек не запускалась")
                    .build();
        }

        var indexingInProcess = sitesInDB.stream()
                .anyMatch(site -> site.getStatus() == IndexingStatus.INDEXING);
        if (indexingInProcess) {
            return SearchResponse.builder()
                    .result(false)
                    .error("Индексация части сайтов ещё не завершена")
                    .build();
        }

        return search(sitesInDB, query, limit, offset);
    }

    private SearchResponse search(List<Site> sites, String query, int limit, int offset) {
        if (query == null || query.isBlank()) {
            return SearchResponse.builder()
                    .result(false)
                    .error("Задан пустой поисковый запрос")
                    .build();
        }
        if (limit <= 0) {
            return SearchResponse.builder()
                    .result(false)
                    .error("Параметр limit должен быть больше нуля")
                    .build();
        }
        if (offset < 0) {
            return SearchResponse.builder()
                    .result(false)
                    .error("Параметр offset не может быть отрицательным")
                    .build();
        }

        var lemmas = findLemmas(sites, query);

        if (lemmas.isEmpty()) {
            return SearchResponse.builder()
                    .result(true)
                    .count(0)
                    .data(Collections.emptyList())
                    .build();
        }

        var absoluteRelevance = computeAbsoluteRelevance(lemmas);
        var relativeRelevance = computeRelativeRelevance(absoluteRelevance);

        var lemmasFinder = applicationContext.getBean(LemmasFinder.class);
        var lemmasNames = lemmasFinder.findLemmas(query).keySet();
        var data = getSearchData(relativeRelevance, limit, offset, lemmasNames);

        return SearchResponse.builder()
                .result(true)
                .count(relativeRelevance.size())
                .data(data)
                .build();
    }

    private Map<Site, List<Lemma>> findLemmas(@NonNull List<Site> sites, @NonNull String query) {
        if (sites.isEmpty() || query.isBlank()) {
            return Collections.emptyMap();
        }

        var lemmasFinder = applicationContext.getBean(LemmasFinder.class);
        var lemmasNames = lemmasFinder.findLemmas(query).keySet();
        var lemmas = lemmaRepository.findBySiteInAndLemmaIn(sites, lemmasNames);

        return lemmas.stream()
                .collect(Collectors.groupingBy(Lemma::getSite))
                .entrySet().stream()
                // Если хотя бы одной леммы нет в базе данных, значит заведомо не найдётся подходящая страница
                .filter(entry -> entry.getValue().size() == lemmasNames.size())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    // Собирает информацию обо всех сайтах. Корректность группировки данных по сайтам не проверяется.
    private Map<Page, Float> computeAbsoluteRelevance(@NonNull Map<Site, List<Lemma>> lemmas) {

        if (lemmas.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Page, Float> relevance = new HashMap<>();

        for (var siteLemmas : lemmas.values()) {
            if (siteLemmas.isEmpty()) {
                continue;
            }
            var tmpRelevance = computeAbsoluteRelevance(siteLemmas);

            relevance.putAll(tmpRelevance);
        }

//        pages.sort();

        return relevance;
    }

    // Собирает информацию об одном сайте
    private Map<Page, Float> computeAbsoluteRelevance(@NonNull List<Lemma> lemmas) {
        if (lemmas.isEmpty()) {
            return Collections.emptyMap();
        }

        lemmas.sort(Comparator.comparingInt(Lemma::getFrequency));

        var relevance = indexRepository.findByLemma(lemmas.get(0))
                .stream()
                .collect(Collectors.toMap(Index::getPage, Index::getRank));

        for (int i = 1; i < lemmas.size(); i++) {
            var tmpRelevance = indexRepository.findByLemmaAndPageIn(lemmas.get(i), relevance.keySet())
                    .stream()
                    .collect(Collectors.toMap(Index::getPage, Index::getRank));

            if (tmpRelevance.isEmpty()) {
                return tmpRelevance;
            }

            for (var entry : tmpRelevance.entrySet()) {
                var newValue = entry.getValue() + relevance.get(entry.getKey());
                entry.setValue(newValue);
            }
            relevance = tmpRelevance;
        }

        return relevance;
    }

    private Map<Page, Float> computeRelativeRelevance(@NonNull Map<Page, Float> absoluteRelevance) {
        if (absoluteRelevance.isEmpty()) {
            return Collections.emptyMap();
        }

        var maxRelevance = absoluteRelevance.values().stream()
                .max(Float::compareTo)
                .get();

        return absoluteRelevance.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue() / maxRelevance));
    }

    private List<SearchData> getSearchData(@NonNull Map<Page, Float> relevance, int limit, int offset,
                                           @NonNull Set<String> lemmas) {
        if (relevance.isEmpty() || offset > (relevance.size() - 1) || lemmas.isEmpty() || limit <= 0 || offset < 0) {
            return Collections.emptyList();
        }

        var sortedRelevance = relevance.entrySet().stream()
                .sorted(Collections.reverseOrder(Map.Entry.comparingByValue()))
                .toList();

        List<SearchData> data = new ArrayList<>(limit);
        var lemmasFinder = applicationContext.getBean(LemmasFinder.class);

        var maxIndex = Math.min(offset + limit, relevance.size());
        for (int i = offset; i < maxIndex; i++) {
            var entry = sortedRelevance.get(i);
            var page = entry.getKey();
            var site = page.getSite();

            var title = "";
            var snippet = "";
            if (page.canBeParsed()) {
                var document = Jsoup.parse(page.getContent());
                title = document.title();
                snippet = lemmasFinder.getSnippet(document.text(), lemmas);
            }

            var searchData = new SearchData();
            searchData.setSite(site.getUrl());
            searchData.setSiteName(site.getName());
            searchData.setUri(page.getPath());
            searchData.setTitle(title);
            searchData.setSnippet(snippet);
            searchData.setRelevance(entry.getValue());

            data.add(searchData);
        }

        return data;
    }

}

package searchengine.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.util.Streamable;
import org.springframework.stereotype.Service;
import searchengine.dto.statistics.DetailedStatisticsItem;
import searchengine.dto.statistics.StatisticsData;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.dto.statistics.TotalStatistics;
import searchengine.model.*;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class StatisticsServiceImpl implements StatisticsService {

    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;


    @Override
    public StatisticsResponse getStatistics() {

        var start = System.currentTimeMillis();

        var sitesFromDB = Streamable.of(siteRepository.findAll()).toList();
        var isIndexing = sitesFromDB.stream().anyMatch(site -> site.getStatus() == IndexingStatus.INDEXING);

        var pages = pageRepository.findBySiteIn(sitesFromDB);
        var pagesCounts = pages.stream()
                .collect(Collectors.groupingBy(Page::getSite, Collectors.summingInt(value -> 1)));

        List<Lemma> lemmas = lemmaRepository.findBySiteIn(sitesFromDB);
        var lemmasCounts = lemmas.stream()
                .collect(Collectors.groupingBy(Lemma::getSite, Collectors.summingInt(value -> 1)));

        TotalStatistics total = getTotalStatistics(sitesFromDB, isIndexing, pages, lemmas);

        List<DetailedStatisticsItem> detailed = new ArrayList<>();
        for (var site : sitesFromDB) {
            DetailedStatisticsItem item = getDetailedStatisticsItem(site, pagesCounts, lemmasCounts);
            detailed.add(item);
        }

        var response = getStatisticsResponse(total, detailed);

        log.info("Запрос статистики сформирован за {} мс.", System.currentTimeMillis() - start);

        return response;
    }

    private TotalStatistics getTotalStatistics(List<Site> sitesFromDB, boolean isIndexing, List<Page> pages, List<Lemma> lemmas) {
        TotalStatistics total = new TotalStatistics();
        total.setSites(sitesFromDB.size());
        total.setIndexing(isIndexing);
        total.setPages(pages.size());
        total.setLemmas(lemmas.size());

        return total;
    }

    private DetailedStatisticsItem getDetailedStatisticsItem(Site site, Map<Site, Integer> pagesCounts,
                                                             Map<Site, Integer> lemmasCounts) {
        DetailedStatisticsItem item = new DetailedStatisticsItem();
        item.setName(site.getName());
        item.setUrl(site.getUrl());
        var pagesCount = pagesCounts.getOrDefault(site, 0);
        var lemmasCount = lemmasCounts.getOrDefault(site, 0);
        item.setPages(pagesCount);
        item.setLemmas(lemmasCount);
        item.setStatus(site.getStatus());
        item.setError(site.getLastError());
        item.setStatusTime(getStatusTimeMillis(site));

        return item;
    }

    private long getStatusTimeMillis(Site site) {
        var zoneDateTime = site.getStatusTime().atZone(ZoneOffset.systemDefault());
        return zoneDateTime.toInstant().toEpochMilli();
    }

    private StatisticsResponse getStatisticsResponse(TotalStatistics total, List<DetailedStatisticsItem> detailed) {
        StatisticsResponse response = new StatisticsResponse();
        StatisticsData data = new StatisticsData();
        data.setTotal(total);
        data.setDetailed(detailed);
        response.setStatistics(data);
        response.setResult(true);
        return response;
    }
}

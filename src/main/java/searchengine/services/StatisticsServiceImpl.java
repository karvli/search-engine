package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.springframework.data.util.Streamable;
import org.springframework.stereotype.Service;
import searchengine.config.SitesList;
import searchengine.dto.statistics.DetailedStatisticsItem;
import searchengine.dto.statistics.StatisticsData;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.dto.statistics.TotalStatistics;
import searchengine.model.*;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StatisticsServiceImpl implements StatisticsService {

    private final Random random = new Random();
    private final SitesList sites;

    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;


    @Override
    public StatisticsResponse getStatistics() {

        var sitesFromDB = Streamable.of(siteRepository.findAll()).toList();
        var isIndexing = sitesFromDB.stream().anyMatch(site -> site.getStatus() == IndexingStatus.INDEXING);

        var pages = pageRepository.findBySiteIn(sitesFromDB);
        var pagesCounts = pages.stream()
                .collect(Collectors.groupingBy(Page::getSite, Collectors.summingInt(value -> 1)));

        List<Lemma> lemmas = lemmaRepository.findBySiteIn(sitesFromDB);
        var lemmasCounts = lemmas.stream()
                .collect(Collectors.groupingBy(Lemma::getSite, Collectors.summingInt(value -> 1)));

        TotalStatistics total = new TotalStatistics();
        total.setSites(sitesFromDB.size());
        total.setIndexing(isIndexing);
        total.setPages(pages.size());
        total.setLemmas(lemmas.size());

        List<DetailedStatisticsItem> detailed = new ArrayList<>();
        for (var site : sitesFromDB) {
            DetailedStatisticsItem item = new DetailedStatisticsItem();
            item.setName(site.getName());
            item.setUrl(site.getUrl());
            var pagesCount = pagesCounts.getOrDefault(site, 0);
            var lemmasCount = lemmasCounts.getOrDefault(site, 0);
            item.setPages(pagesCount);
            item.setLemmas(lemmasCount);
            item.setStatus(site.getStatus());
            item.setError(site.getLastError());
//            item.setStatusTime(site.getStatusTime());

            // TODO Найти вариант проще. Возможно, аннотацией поля @JsonFormat
            var zoneDateTime = site.getStatusTime().atZone(ZoneOffset.systemDefault());
            var millis = zoneDateTime.toInstant().toEpochMilli();
            item.setStatusTime(millis);

            detailed.add(item);
        }

        StatisticsResponse response = new StatisticsResponse();
        StatisticsData data = new StatisticsData();
        data.setTotal(total);
        data.setDetailed(detailed);
        response.setStatistics(data);
        response.setResult(true);
        return response;
    }
}

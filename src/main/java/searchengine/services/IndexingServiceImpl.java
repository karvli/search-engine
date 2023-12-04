package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import searchengine.config.Site;
import searchengine.config.SitesList;
import searchengine.dto.indexing.IndexingResponse;
import searchengine.dto.indexing.PageAnalyzer;
import searchengine.model.IndexingStatus;
import searchengine.model.Page;
import searchengine.model.PageRepository;
import searchengine.model.SiteRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;

@Service
@RequiredArgsConstructor
public class IndexingServiceImpl implements IndexingService{

    private final SitesList sites;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final ApplicationContext applicationContext;

    public static ForkJoinPool indexingPool;
    public static List<PageAnalyzer> indexingTasks;
    public static List<Thread> indexingThreads;


    @Override
    public synchronized IndexingResponse startIndexing() {

        var siteSettings = sites.getSites();
        var names = siteSettings.stream().map(Site::getName).toList();

        var currentSites = siteRepository.findByNameIn(names);

        if (currentSites.stream().anyMatch(site -> site.getStatus() == IndexingStatus.INDEXING)) {
            return IndexingResponse.builder()
                    .result(false)
                    .error("Индексация уже запущена")
                    .build();
        }


//        var rootUrl = SiteMap.getNormalizedUrl("https://skillbox.ru/");
//        var siteMap = new SiteMap(rootUrl);
//
//        // ФОРМИРОВАНИЕ КАРТЫ САЙТА
//        var task = new SiteMapAnalyzer(siteMap.getRootNode());
//
//        var start = System.currentTimeMillis();
//        try (var pool = new ForkJoinPool()) {
//            pool.invoke(task);
//        }

        siteRepository.deleteAll(currentSites);

        indexingPool = new ForkJoinPool();
//        indexingTasks = new ArrayList<>();
//        indexingThreads = new ArrayList<>();

        var now = LocalDateTime.now();

//        var indexingSites = sites.getSites().stream()
//                .map(site -> {
//                    var newSite = new searchengine.model.Site();
//                    newSite.setName(site.getName());
//                    newSite.setUrl(site.getUrl());
//                    newSite.setStatus(IndexingStatus.INDEXING);
//                    newSite.setStatusTime(now);
//
//                    return newSite;
//                })
//                .toList();

        var indexingSites = new ArrayList<searchengine.model.Site>();
        var rootPages = new ArrayList<Page>();

        for (Site site : sites.getSites()) {

            var newSite = new searchengine.model.Site();
            newSite.setName(site.getName());
            newSite.setUrl(site.getUrl());
            newSite.setStatus(IndexingStatus.INDEXING);
            newSite.setStatusTime(now);

            indexingSites.add(newSite);

            var page = new Page();
            page.setSite(newSite);
            page.setPath("/");
            page.setCode(102); // http 102 - "Идёт обработка"

            rootPages.add(page);
        }

        siteRepository.saveAll(indexingSites);
        pageRepository.saveAll(rootPages);

//        var taskList = new ArrayList<PageAnalyzer>();

//        var pool = new ForkJoinPool(
//                Runtime.getRuntime().availableProcessors(),
//                defaultForkJoinWorkerThreadFactory,
//                null
//                , true);
//        var pool = new ForkJoinPool();
        for (var page : rootPages) {
            var task = applicationContext.getBean(PageAnalyzer.class);
            task.setPage(page);
//            pool.execute(task);
            indexingPool.execute(task);
//            commonPool().submit(task);
//            task.fork();
//            taskList.add(task);

//            indexingTasks.add(task);

//            var thread = new Thread(() -> {
//                var task = applicationContext.getBean(PageAnalyzer.class);
//                task.setPage(page);
//                commonPool().invoke(task);
//
//                var site = page.getSite();
//                site.setStatusTime(LocalDateTime.now());
//                site.setStatus(IndexingStatus.INDEXED);
//                siteRepository.save(site);
//            });
//
//            indexingThreads.add(thread);
//            thread.start();
        }

//        taskList.forEach(ForkJoinTask::join);

        return IndexingResponse.builder()
                .result(true)
                .build();
    }

    @Override
    public synchronized IndexingResponse stopIndexing() {

//        var indexingSites = siteRepository.findByStatus(IndexingStatus.INDEXING);

        var names = sites.getSites().stream().map(Site::getName).toList();
        var indexingSites = siteRepository.findByStatusAndNameIn(IndexingStatus.INDEXING, names);

        if (indexingSites.isEmpty()) {
            return IndexingResponse.builder()
                    .result(false)
                    .error("Индексация не запущена")
                    .build();
        }

//        indexingThreads.forEach(Thread::interrupt);
//        indexingTasks.forEach(pageAnalyzer -> pageAnalyzer.quietlyJoinUninterruptibly());
        indexingPool.shutdownNow();

        var now = LocalDateTime.now();

        for (var indexingSite : indexingSites) {
            indexingSite.setStatus(IndexingStatus.FAILED);
            indexingSite.setStatusTime(now);
        }

        siteRepository.saveAll(indexingSites);

        return IndexingResponse.builder()
                .result(true)
                .build();
    }

    @Override
    public synchronized IndexingResponse indexPage(String url) {
        if (url.isBlank()) {
            return IndexingResponse.builder()
                    .result(false)
                    .error("Не передано значение url")
                    .build();
        }

        url = url.toLowerCase();

        Site configSite = null;

        for (var site: sites.getSites()) {
            var siteUrl = site.getUrl();
            if (url.startsWith(siteUrl)) {
                configSite = site;
                break;
            }
        }
        if (configSite == null) {
            return IndexingResponse.builder()
                    .result(false)
                    .error("Данная страница находится за пределами сайтов, указанных в конфигурационном файле")
                    .build();
        }

        var site = siteRepository.findByName(configSite.getName());
        var newSite = site == null;
        if (newSite) {
            site = new searchengine.model.Site();
            site.setName(configSite.getName());
            site.setUrl(configSite.getUrl());

            siteRepository.save(site);
        }

        var path = url.substring(configSite.getUrl().length());
        path = PageAnalyzer.getNormalizedPath(site, path);

        Page page = null;
        if (!newSite) {
            // TODO Пересмотреть работу со страницами
            page = pageRepository.findBySiteAndPath(site, path).get(0);
        }

        if (page == null) {
            page = new Page();
            page.setSite(site);
            page.setPath(path);
            page.setCode(102);

            pageRepository.save(page);
        } else if (page.getCode() == 102) {
            // Страница уже в очереди на обновление. Дальнейшее действия не требуются.
            return IndexingResponse.builder()
                    .result(true)
                    .build();
        }



        return IndexingResponse.builder()
                .result(true)
                .build();
    }


}

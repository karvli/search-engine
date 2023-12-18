package searchengine.services;

import lombok.RequiredArgsConstructor;
import lombok.val;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.config.Site;
import searchengine.config.SitesList;
import searchengine.dto.indexing.IndexingResponse;
import searchengine.dto.indexing.PageAnalyzer;
import searchengine.model.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class IndexingServiceImpl implements IndexingService {

    private final SitesList sites;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
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

        indexingPool = new ForkJoinPool();
        indexingTasks = new ArrayList<>();
//        indexingThreads = new ArrayList<>();

        var now = LocalDateTime.now();

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

//        var taskList = new ArrayList<PageAnalyzer>();

//        var pool = new ForkJoinPool(
//                Runtime.getRuntime().availableProcessors(),
//                defaultForkJoinWorkerThreadFactory,
//                null
//                , true);
//        var pool = new ForkJoinPool();

        new Thread(() -> {
            siteRepository.deleteAll(currentSites);
            siteRepository.saveAll(indexingSites);
            pageRepository.saveAll(rootPages);

            for (var page : rootPages) {
                var task = applicationContext.getBean(PageAnalyzer.class);
                task.setPage(page);
//            pool.execute(task);
                indexingPool.execute(task);
//            commonPool().submit(task);
//            task.fork();
//            taskList.add(task);

                indexingTasks.add(task);

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
        }).start();

//        indexingTasks = rootPages.stream().map(page -> {
//            var task = applicationContext.getBean(PageAnalyzer.class);
//            task.setPage(page);
//            return task;
//        }).toList();
//
//        indexingPool.invokeAll(indexingTasks);

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

        new Thread(() -> {
            //        indexingThreads.forEach(Thread::interrupt);
            if (indexingPool != null) {
//                indexingPool.shutdown();
                indexingPool.shutdownNow();


//                try {
//                    var terminated = false;
//                    while (!terminated) {
//                        terminated = indexingPool.awaitTermination(5, TimeUnit.SECONDS);
//                    }
//                } catch (Exception e) {
//                    e.printStackTrace();
//                }

            }

            // TODO Дождаться завершения ForkJoinPool или отменить Task
//            if (indexingTasks != null) {
////                indexingTasks.forEach(pageAnalyzer -> pageAnalyzer.cancel(true));
//                indexingTasks.forEach(ForkJoinTask::quietlyJoin);
//            }

            var now = LocalDateTime.now();
            for (var indexingSite : indexingSites) {
                indexingSite.setStatus(IndexingStatus.FAILED);
                indexingSite.setLastError("Индексация остановлена пользователем");
                indexingSite.setStatusTime(now);
            }

            siteRepository.saveAll(indexingSites);
        }).start();

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

        for (var site : sites.getSites()) {
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

            // По идее, статусом управляет общий процесс, но т.к. он не запущен, меняем статус
            site.setStatus(IndexingStatus.INDEXING);
        }
        site.setStatusTime(LocalDateTime.now());
        siteRepository.save(site);

        var path = url.substring(configSite.getUrl().length());
        path = PageAnalyzer.getNormalizedPath(site, path);

        Page page = null;
        Thread deletingThread = null;

        if (!newSite) {
            page = pageRepository.findBySiteAndPath(site, path);
        }

        var newPage = page == null;

        if (!newPage)  {

            if (page.getCode() == 102) {
                // Страница уже в очереди на обновление. Дальнейшее действия не требуются.
                return IndexingResponse.builder()
                        .result(true)
                        .build();
            }

            val finalPage = page;
            deletingThread = new Thread(() -> deleteLeLemmatizationInfo(finalPage));
            deletingThread.start();
        }

        page = new Page();
        page.setSite(site);
        page.setPath(path);
        page.setCode(102);

        pageRepository.save(page);

        val finalSite = site;
        val finalPage = page;
        val finalDeletingThread = deletingThread;

        new Thread(() -> {

            if (finalDeletingThread != null) {
                try {
                    finalDeletingThread.join();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

            var pageAnalyzer = applicationContext.getBean(PageAnalyzer.class);
            pageAnalyzer.setPage(finalPage);
            pageAnalyzer.analyzePage();

            if (newSite) {
                // Если общего процесса нет, определяем статус по этой странице
                finalSite.setStatus(IndexingStatus.INDEXED);
                siteRepository.save(finalSite);
            }
        }).start();

        return IndexingResponse.builder()
                .result(true)
                .build();
    }

    // Удаление Page, Lemma, Index в соответствии с ТЗ
    @Transactional
    private void deleteLeLemmatizationInfo(Page page) {
        // Можно было бы синхронизировать по Page, но в случае возникновении ошибки записи Page нет гарантии, что
        // в репозитории ещё не изменилась информация по Lemma.
        synchronized (page.getSite()) {
            var checkingLemmas = indexRepository.findByPage(page).stream()
                    .map(Index::getLemma)
                    .collect(Collectors.groupingBy(lemma -> lemma.getFrequency() > 1));

            var deletingLemmas = checkingLemmas.getOrDefault(false, Collections.emptyList());

            var changedLemmas = checkingLemmas.getOrDefault(true, Collections.emptyList());
            for (var lemma : changedLemmas) {
                var frequency = lemma.getFrequency() - 1;
                lemma.setFrequency(frequency);
            }

            lemmaRepository.deleteAll(deletingLemmas);
            lemmaRepository.saveAll(changedLemmas);
            pageRepository.delete(page);
        }
    }

}

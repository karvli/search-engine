package searchengine.services;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.concurrent.ForkJoinTask;
import java.util.stream.Collectors;

import static java.util.concurrent.ForkJoinPool.commonPool;

@Slf4j
@Service
@RequiredArgsConstructor
public class IndexingServiceImpl implements IndexingService {

    private final SitesList sites;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final ApplicationContext applicationContext;

    private static final List<PageAnalyzer> indexingTasks = new ArrayList<>();
    private static boolean indexingCancelling = false; // Для конкретизации сообщений об ошибках

    @Override
    public synchronized IndexingResponse startIndexing() {
        var siteSettings = sites.getSites();
        var urls = siteSettings.stream().map(Site::getUrl).toList();
        var currentSites = siteRepository.findByUrlIn(urls);

        if (!indexingTasks.isEmpty() // На случай перезапуска приложения в процессе индексации
                && currentSites.stream().anyMatch(site -> site.getStatus() == IndexingStatus.INDEXING)) {
            var error = indexingCancelling ? "Предыдущая индексация ещё останавливается"
                    : "Индексация уже запущена";

            return IndexingResponse.builder().result(false).error(error).build();
        }

        indexingCancelling = false;
        var indexingSites = new ArrayList<searchengine.model.Site>();
        var rootPages = new ArrayList<Page>();

        for (Site site : sites.getSites()) {
            var newSite = createSite(site);
            indexingSites.add(newSite);

            var page = createPage(newSite, "/");
            rootPages.add(page);

            // Чтобы запретить запуск нового индексирования
            var task = applicationContext.getBean(PageAnalyzer.class);
            task.setPage(page);

            indexingTasks.add(task);
        }

        getStartIndexingThread(currentSites, indexingSites, rootPages).start();

        return IndexingResponse.builder().result(true).build();
    }

    private Thread getStartIndexingThread(List<searchengine.model.Site> currentSites,
                                          ArrayList<searchengine.model.Site> indexingSites, ArrayList<Page> rootPages) {
        return new Thread(() -> {
            log.info("Запуск полной индексации");
            var start = System.currentTimeMillis();

            siteRepository.deleteAll(currentSites);
            siteRepository.saveAll(indexingSites);
            pageRepository.saveAll(rootPages);

            indexingTasks.forEach(task -> commonPool().execute(task));
            indexingTasks.forEach(ForkJoinTask::quietlyJoin);

            if (indexingCancelling) {
                log.info("Полная индексации отменена пользователем");
            } else {
                log.info("Полная индексация выполнена за {} мс.", System.currentTimeMillis() - start);
            }
        });
    }

    @Override
    public synchronized IndexingResponse stopIndexing() {
        var urls = sites.getSites().stream().map(Site::getUrl).toList();
        var indexingSites = siteRepository.findByStatusAndUrlIn(IndexingStatus.INDEXING, urls);

        if (indexingSites.isEmpty()) {
            return IndexingResponse.builder().result(false).error("Индексация не запущена").build();
        } else if (indexingCancelling) {
            return IndexingResponse.builder().result(false).error("Индексация уже останавливается").build();
        }

        indexingCancelling = true;
        getStopIndexingThread(indexingSites).start();

        return IndexingResponse.builder().result(true).build();
    }

    private Thread getStopIndexingThread(List<searchengine.model.Site> indexingSites) {
        return new Thread(() -> {
            log.info("Запуск остановки полной индексации");
            var start = System.currentTimeMillis();

            if (!indexingTasks.isEmpty()) {
                indexingTasks.stream()
                        .filter(task -> !task.isDone())
                        .forEach(task -> task.cancel(true));

                // У отменённых задач могут быть ещё не отменены подчинённые. Ожидание отмены всей иерархии.
                indexingTasks.forEach(ForkJoinTask::quietlyJoin);
            }

            var now = LocalDateTime.now();
            for (var indexingSite : indexingSites) {
                synchronized (indexingSite) {
                    indexingSite.setStatus(IndexingStatus.FAILED);
                    indexingSite.setLastError("Индексация остановлена пользователем");
                    indexingSite.setStatusTime(now);
                    siteRepository.save(indexingSite);
                }
            }

            indexingTasks.clear(); // Чтобы разрешить запуск нового индексирования

            log.info("Полная индексация остановлена за {} мс.", System.currentTimeMillis() - start);
        });
    }

    @Override
    public synchronized IndexingResponse indexPage(@NonNull String url) {
        if (url.isBlank()) {
            return IndexingResponse.builder().result(false).error("Не передано значение url").build();
        }

        url = url.toLowerCase();
        Site configSite = findConfigSite(url);
        if (configSite == null) {
            var error = "Данная страница находится за пределами сайтов, указанных в конфигурационном файле";
            return IndexingResponse.builder().result(false).error(error).build();
        }

        var site = siteRepository.findByUrl(configSite.getUrl());
        var newSite = site == null;

        Page oldPage = null;
        var path = url.substring(configSite.getUrl().length());
        path = PageAnalyzer.getNormalizedPath(site, path);

        if (newSite) {
            // В стандартной ситуации статусом управляет общий процесс. Но т.к. он не запускался, меняем статус.
            site = createSite(configSite);
        } else {
            site.setStatusTime(LocalDateTime.now());
            oldPage = pageRepository.findBySiteAndPath(site, path);
        }
        siteRepository.save(site);

        if (oldPage != null && oldPage.getCode() == 102) {
            // Страница уже в очереди на обновление. Дальнейшее действия не требуются.
            return IndexingResponse.builder().result(true).build();
        }

        var newPage = createPage(site, path);
        getIndexPageThread(oldPage, newPage, newSite).start();

        return IndexingResponse.builder().result(true).build();
    }

    private Thread getIndexPageThread(Page oldPage, @NonNull Page newPage, boolean newSite) {
        return new Thread(() -> {
            var url = newPage.getUrl();

            log.info("Запуск индексации страницы {}", url);
            var start = System.currentTimeMillis();

            if (oldPage != null) {
                deleteLeLemmatizationInfo(oldPage);
            }

            pageRepository.save(newPage);

            var pageAnalyzer = applicationContext.getBean(PageAnalyzer.class);
            pageAnalyzer.setPage(newPage);
            pageAnalyzer.analyzePage();

            if (newSite) {
                var site = newPage.getSite();
                // Если общего процесса нет, определяем статус по этой странице
                site.setStatus(IndexingStatus.INDEXED);
                siteRepository.save(site);
            }

            log.info("Индексации страницы {} выполнена за {} мс.", url, System.currentTimeMillis() - start);
        });
    }

    private searchengine.model.Site createSite(@NonNull Site site) {
        var newSite = new searchengine.model.Site();
        newSite.setName(site.getName());
        newSite.setUrl(site.getUrl());
        newSite.setStatus(IndexingStatus.INDEXING);
        newSite.setStatusTime(LocalDateTime.now());

        return newSite;
    }

    private Page createPage(@NonNull searchengine.model.Site site, @NonNull String path) {
        var page = new Page();
        page.setSite(site);
        page.setPath(path);
        page.setCode(102); // Processing («Идёт обработка»)

        return page;
    }

    private Site findConfigSite(@NonNull String url) {
        Site configSite = null;

        for (var site : sites.getSites()) {
            var siteUrl = site.getUrl();
            if (url.startsWith(siteUrl)) {
                configSite = site;
                break;
            }
        }
        return configSite;
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

package searchengine.dto.indexing;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.java.Log;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.UnsupportedMimeTypeException;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import searchengine.config.SearchBot;
import searchengine.model.*;
import searchengine.services.LemmasFinder;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;
import java.util.function.Function;
import java.util.logging.FileHandler;
import java.util.logging.SimpleFormatter;
import java.util.stream.Collectors;

@Log
@RequiredArgsConstructor
@Getter
@Setter
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class PageAnalyzer extends RecursiveAction {

    private final ApplicationContext applicationContext;
    private final SearchBot searchBot;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;

    private final Random random = new Random();

    private Page page;

    private List<PageAnalyzer> taskList = Collections.emptyList(); // Подчинённые задачи

    public static String getNormalizedPath(Site site, String url) {
        url = url.strip();

        // Очистка от лишних параметров
        var endIndex = url.indexOf('?');
        if (endIndex != -1) {
            url = url.substring(0, endIndex);
        }

        // Приведение к единому виду
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }

        url = url.toLowerCase();

        // URL может быть указан не с начала корня. Проверка соответствия сайта URL должна была быть до вызова метода.
        var rootUrl = site.getUrl().toLowerCase();
        if (url.startsWith(rootUrl)) {
            url = url.substring(rootUrl.length());
        }

        // Если в URL остались "http://" или аналогичное, значит URL был некорректным или проверка была не выполнена
        var slashesIndex = url.indexOf("://");
        if (slashesIndex > 0 && slashesIndex + 1 == url.indexOf("/")) {
            throw new IllegalArgumentException(
                    "URL \"" + url + "\" должен начинаться с \"" + rootUrl + "\" или с \"/\"");
        }

        // Обработка обоих вариантов окончания пути к сайту
        if(!url.startsWith("/")) {
            url = "/" + url;
        }

        return url;
    }

    private String getNormalizedPath(String url) {
        return getNormalizedPath(page.getSite(), url);
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        new Thread(this::stopChildrenTasks).start();
//        stopChildrenTasks();
        return super.cancel(mayInterruptIfRunning);
    }

    @Override
    protected void compute() {

        // МЕТКА
        try {
            var fh = new FileHandler("logs/pages/" + System.currentTimeMillis() + ".log");
//            SimpleFormatter formatter = new SimpleFormatter();
//            fh.setFormatter(formatter);
            log.addHandler(fh);
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }


        randomTimeout();

        if (analyzeStopped()) {System.out.println("Остановлена"); return;}

        analyzePage();
        if (!page.canBeParsed()) {
            return;
        }

        var site = page.getSite();

//        if (isCancelled()) {System.out.println("Остановлена"); return;}
//        if (site.indexingFailed()) {System.out.println("Остановлена"); return;}
        if (analyzeStopped()) {System.out.println("Остановлена"); return;}

//        var taskList = new ArrayList<PageAnalyzer>();

        // URL начинается с переданного корня и не содержит ссылок на внутренние элементы страницы (не содержит #)
        var document = Jsoup.parse(page.getContent());
        var regex = "(?i)^((" + site.getUrl() + ")|/)[^#]*$";
        var elements = document.select("a[href~=" + regex + "]");

//        var taskList =
        var paths = elements.stream()
                .map(element -> element.attr("href"))
//                .filter(s -> !s.isBlank())
                .map(this::getNormalizedPath)
                .distinct()

//                .map(this::forkTask)
//                .filter(Objects::nonNull)

//                .map(ForkJoinTask::fork)
                .toList();
//                .forEach(ForkJoinTask::quietlyJoin);

        List<Page> newPages;

//        if (site.indexingFailed()) {System.out.println("Остановлена"); return;}
        if (analyzeStopped()) {System.out.println("Остановлена"); return;}

        synchronized (site) {
            var existingPaths = pageRepository.findBySiteAndPathIn(site, paths).stream()
                    .map(Page::getPath)
                    .map(this::getNormalizedPath)
                    .distinct()
                    .toList();

//            if (isCancelled()) {System.out.println("Остановлена"); return;}
            if (analyzeStopped()) {System.out.println("Остановлена"); return;}

            newPages = paths.stream()
                    .filter(p -> !existingPaths.contains(p))
                    .map(path -> {
                        var newPage = new Page();
                        newPage.setSite(site);
                        newPage.setPath(path);
                        newPage.setCode(102); // Processing («Идёт обработка»)

                        return newPage;
                    })
                    .toList();

//            if (site.indexingFailed()) {System.out.println("Остановлена"); return;}
            if (analyzeStopped()) {System.out.println("Остановлена"); return;}

            pageRepository.saveAll(newPages);
        }

        updateSite(site);

//        if (isCancelled()) {System.out.println("Остановлена"); return;}

//        var pageNode = applicationContext.getBean(PageNode.class);
//        pageNode.compute();
//        var newPages = pageNode.getChildren();

//        var taskList
        taskList = newPages.stream()
                .map(newPage -> {
                    var task = applicationContext.getBean(PageAnalyzer.class);
                    task.setPage(newPage);

                    if (analyzeStopped()) {System.out.println("Остановлена"); return task;}

                    task.fork();
//                    randomTimeout();

                    return task;
                })
//                .map(ForkJoinTask::fork)
                .toList();

//        var taskList = new ArrayList<PageAnalyzer>();
//        for (Page newPage : newPages) {
//            var task = applicationContext.getBean(PageAnalyzer.class);
//            task.setPage(newPage);
//            task.fork();
//
//            taskList.add(task);
//            randomTimeout();
//        }


//        taskList.forEach(ForkJoinTask::join);

//        if (isCancelled()) {System.out.println("Остановлена"); return;}
//        if (site.indexingFailed()) {System.out.println("Остановлена"); return;}
        if (analyzeStopped()) {System.out.println("Остановлена"); return;}

        for (var task : taskList) {
            try {
                task.join();
//                task.invoke();
//                ForkJoinPool.commonPool().invoke(task);
//                randomTimeout();
            } catch (Exception e) {
                e.printStackTrace(System.err);

                page.setCode(500); // Internal Server Error («Внутренняя ошибка сервера»)
                page.setContent("taskList: " + e.getLocalizedMessage()); // TODO Удалить после отладки
                savePage(page);

                saveError(site, e);
            }
            if (analyzeStopped()) {System.out.println("Остановлена"); return;}
        }

//        if (isCancelled()) {System.out.println("Остановлена"); return;}

        if (page.isRoot() && site.getStatus() == IndexingStatus.INDEXING) {
            site.setStatus(IndexingStatus.INDEXED);
            saveSite(site);
        }
    }

    public void analyzePage() {

        var site = page.getSite();

        Document document;
        int statusCode;

        try {
            // По умолчанию выполняется нужный метод - Get()
            var response = Jsoup.connect(page.getUrl())
                    .userAgent(searchBot.getUserAgent())
                    .referrer(searchBot.getReferer())
//                    .ignoreContentType(true)
                    .execute();
            statusCode = response.statusCode();
            document = response.parse();
        } catch (HttpStatusException e) {
            // Ошибка не связана напрямую с программой - подробное описание анализировать не требуется
            System.out.println(e.getLocalizedMessage());

            statusCode = e.getStatusCode();
            page.setCode(statusCode);
            savePage(page);

            updateSite(site);

            return;
        } catch (UnsupportedMimeTypeException e) {
            // По ссылке не страница, а, например, картинка. Не ошибка. Но такие страницы не нужны - удаляем их.
            System.out.println(e.getLocalizedMessage());

            page.setCode(415); // Unsupported Media Type («Неподдерживаемый тип данных»)
            savePage(page);

            updateSite(site);
            return;
        } catch (Exception e) {
            e.printStackTrace(System.err);

            page.setCode(500); // Internal Server Error («Внутренняя ошибка сервера»)
            page.setContent("jsoup: " + e.getLocalizedMessage()); // TODO Удалить после отладки
            savePage(page);

            saveError(site, e);

            return;
        }

        if (isCancelled()) {System.out.println("Отменена"); return;}

        var html = document.html();
//        byte[] b = html.getBytes(document.charset());
//        html = new String(b, StandardCharsets.UTF_8);

        /* При сохранении может возникнуть ошибка:
        SQL Error: 1366, SQLState: HY000
        Incorrect string value: '\xF0\x9F\x98\x83',...' for column 'content' at row 1
        Это связано с недостаточной битностью кодировки в базе данных. Для исправления надо поменять кодировку БД.
        Для MySQL в случае UTF-8 нужно использовать utf8mb4 */
        page.setCode(statusCode);
        page.setContent(html);
        savePage(page);

        updateSite(site);

        // Леммы
        var lemmasFinder = applicationContext.getBean(LemmasFinder.class);
        var lemmas = lemmasFinder.findLemmasInHtml(html);

        if (isCancelled()) {System.out.println("Отменена"); return;}

        synchronized (site) {

            var existingLemmas = lemmaRepository.findBySiteAndLemmaIn(site, lemmas.keySet());
            var lemmasCache = existingLemmas.stream()
                    .collect(Collectors.toMap(Lemma::getLemma, Function.identity()));

            var usedIndexes = indexRepository.findByPage(page);
            var indexesCache = usedIndexes.stream()
                    .collect(Collectors.toMap(Index::getLemma, Function.identity()));

            // Страницу могут и обновить. Если индекс с леммой уже был, значит не надо корректировать frequency в лемме
            var usedLemmas = usedIndexes.stream()
                    .map(Index::getLemma)
                    .distinct().toList();

            List<Lemma> changedLemmas = new LinkedList<>();
            List<Lemma> unchangedLemmas = new LinkedList<>();
            List<Index> changedIndexes = new LinkedList<>();
            List<Index> unchangedIndexes = new LinkedList<>();

            for (var lemmaEntry : lemmas.entrySet()) {

                if (isCancelled()) {System.out.println("Отменена"); return;}

                // ЛЕММА

                var lemmaName = lemmaEntry.getKey();

                var lemma = lemmasCache.get(lemmaName);
                var frequency = 1;
                var newLemma = lemma == null;
                var saveLemma = true;

                if (newLemma) {
                    lemma = new Lemma();
                    lemma.setSite(site);
                    lemma.setLemma(lemmaName);
                } else {
                    lemmasCache.remove(lemmaName); // Более не нужно

                    if (usedLemmas.contains(lemma)) {
                        // Статистика лемм не изменилась. Перезаписывать не нужно.
                        saveLemma = false;
                        unchangedLemmas.add(lemma);
                    } else {
                        frequency += lemma.getFrequency();
                    }
                }

                if (saveLemma) {
                    lemma.setFrequency(frequency);
                    changedLemmas.add(lemma);
                }

                if (isCancelled()) {System.out.println("Отменена"); return;}

                // ИНДЕКС

                var rank = lemmaEntry.getValue();
                Index index = null;
                var newIndex = true;

                if (!newLemma) {
                    index = indexesCache.get(lemma);
                    newIndex = index == null;

                    if (!newIndex) {
                        indexesCache.remove(lemma); // Более не нужно

                        if (index.getRank() == rank) {
                            unchangedIndexes.add(index);
                            continue;
                        }
                    }
                }

                if (newIndex) {
                    index = new Index();
                    index.setPage(page);
                    index.setLemma(lemma);
                }

                index.setRank(rank);
                changedIndexes.add(index);
            }

            if (isCancelled()) {System.out.println("Отменена"); return;}

            // Проверка неиспользованных лемм: корректировка frequency или удаление леммы
            var checkingLemmas = usedLemmas.stream()
                    .filter(lemma -> !changedLemmas.contains(lemma))
                    .filter(lemma -> !unchangedLemmas.contains(lemma))
                    .collect(Collectors.groupingBy(lemma -> lemma.getFrequency() > 1));

            // Леммы, у которых frequency = 1, после корректировки станут = 0. Их можно сразу удалить.
            var deletingLemmas = checkingLemmas.getOrDefault(false, Collections.emptyList());

            // Уменьшаем значения frequency, т.к. уменьшилось количество страниц, на которых упоминается лемма
            for (Lemma lemma : checkingLemmas.getOrDefault(true, Collections.emptyList())) {
                var frequency = lemma.getFrequency();
                lemma.setFrequency(--frequency);

                changedLemmas.add(lemma);
            }

            var deletingIndexes =usedIndexes.stream()
                    .filter(index -> !changedIndexes.contains(index))
                    .filter(index -> !unchangedIndexes.contains(index))
                    .toList();

            if (isCancelled()) {System.out.println("Отменена"); return;}

            try {
                saveLemmatizationChanges(deletingLemmas, changedLemmas, deletingIndexes, changedIndexes);
            } catch (Exception e) {
                e.printStackTrace(System.err);
                saveError(site, e);
            }

        }
    }

    private void savePage(Page page) {
        synchronized (page) {
            try {
                pageRepository.save(page);
            } catch (Exception e) {
                saveError(page.getSite(), e);
                throw e;
            }
        }
    }

    private void updateSite(Site site) {
//        synchronized (site) {
//            site = siteRepository.findById(site.getId()).get();
//            if (site.indexingFailed()) {
//                return;
//            }

            site.setStatusTime(LocalDateTime.now());
            saveSite(site);
//        }
    }

    private void saveError(Site site, String error) {
        site.setLastError(error);
        site.setStatus(IndexingStatus.FAILED);
        updateSite(site);
//        site.setStatusTime(LocalDateTime.now());
//        saveSite(site);
    }

    private void saveError(Site site, Exception error) {
        saveError(site,  error.getLocalizedMessage());
    }

    private void saveSite(Site site) {
        if (analyzeStopped()) {
            log.info("Stopped saving " + site.getUrl());
            return;
        }
        log.info("Saving site " + site.getUrl());

        synchronized (site) {
            try {
                siteRepository.save(site);
            } catch (Exception e) {
                saveError(page.getSite(), e);
                throw e;
            }

        }
    }

//    private synchronized PageAnalyzer forkTask(String path) {
//
//        Page newPage = null;
//
//        var site = page.getSite();
//
////        synchronized (site) {
//
//        newPage = pageRepository.findBySiteAndPath(site, path);
//        if (newPage == null) {
//            return null;
//        }
//
//        try {
//            newPage = new Page();
////                newPage = applicationContext.getBean(Page.class);
//            newPage.setSite(site);
//            newPage.setPath(path);
//            newPage.setCode(102); // http 102 - "Идёт обработка"
//            savePage(newPage);
//
//            updateSite(site);
//        } catch (Exception e) {
//
//            page.setCode(500); // Internal Server Error («Внутренняя ошибка сервера»)
//            page.setContent("task: " + e.getLocalizedMessage()); // TODO Удалить после отладки
//            savePage(page);
//
//            site.setLastError(e.getLocalizedMessage());
//            updateSite(site);
//
//            var msg = e.toString();
//            e.printStackTrace(System.err);
//
//            return null;
//        }
////        }
//
////        PageAnalyzer task;
////
////        synchronized (site) {
//        var task = applicationContext.getBean(PageAnalyzer.class);
//        task.setPage(newPage);
//        task.fork();
////        }
//
//        return task;
////        return task.fork();
//    }

    private void randomTimeout() {
        synchronized (page.getSite()) {
            try {
                var interval = searchBot.getRequestsInterval(); // Интервал миллисекунд
                if (interval == null) {
                    return;
                }

                var min = interval.getMin();
                var time = min;
                var max = interval.getMax();
                if (max != null && max > min) {
                    time += random.nextInt(max - min + 1);
                }

                if (time == 0) {
                    return;
                }

                Thread.sleep(time);
            } catch (Exception e) {
                e.printStackTrace(System.err);
            }
        }
    }

    @Transactional
    private void saveLemmatizationChanges(List<Lemma> deletingLemmas, List<Lemma> savingLemmas,
                                          List<Index> deletingIndexes, List<Index> savingIndexes){
        synchronized (page) {
            indexRepository.deleteAll(deletingIndexes);
            lemmaRepository.deleteAll(deletingLemmas);
            lemmaRepository.saveAll(savingLemmas);
            indexRepository.saveAll(savingIndexes);
        }
    }

    private boolean analyzeStopped() {
        var stopped = isCancelled() || page.getSite().indexingFailed();

        if (stopped) {
            stopChildrenTasks();
//            log.info("Stopped");
        }

        return stopped;
    }

    private void stopChildrenTasks() {
        var tasks = taskList.stream()
                .filter(task -> !task.isDone())
                .toList();
        int stoppingTasks = tasks.size();
        if (stoppingTasks > 0) log.info("Stopping tasks: " + stoppingTasks);
        tasks.forEach(task -> task.cancel(true));
        taskList.forEach(ForkJoinTask::quietlyJoin);
        if (stoppingTasks > 0) log.info("Stopped tasks: " + stoppingTasks);
    }
}

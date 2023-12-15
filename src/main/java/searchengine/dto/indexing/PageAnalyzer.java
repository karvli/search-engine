package searchengine.dto.indexing;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.UnsupportedMimeTypeException;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import searchengine.LemmasFinder;
import searchengine.config.SearchBot;
import searchengine.model.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.RecursiveAction;
import java.util.function.Function;
import java.util.stream.Collectors;

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

//        if (!url.startsWith("/")) {
//            return "/" + url;
//        }

//        return url;

        // URL может быть указан не с начала корня. Проверка соответствия сайта URL должна была быть ранее.
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

//        var path = url.substring(rootUrl.length());

        // Обработка обоих вариантов окончания пути к сайту
        if(!url.startsWith("/")) {
            url = "/" + url;
        }

        return url;
    }

    private String getNormalizedPath(String url) {
        return getNormalizedPath(page.getSite(), url);
//        url = url.strip();
//
//        // Очистка от лишних параметров
//        var endIndex = url.indexOf('?');
//        if (endIndex != -1) {
//            url = url.substring(0, endIndex);
//        }
//
//        // Приведение к единому виду
//        if (url.endsWith("/")) {
//            url = url.substring(0, url.length() - 1);
//        }
//
//        url = url.toLowerCase();
//
//        if (!url.startsWith("/")) {
//            return "/" + url;
//        }
//
//        return url;

//        var rootUrl = page.getSite().getUrl().toLowerCase();
//
//        if (!url.startsWith(rootUrl)) {
//            throw new IllegalArgumentException("URL \"" + url + "\" должен начинаться с \"" + rootUrl + "\" или с
//            \"/\"");
//        }
//
//        var path = url.substring(rootUrl.length());
//
//        // Обработка обоих вариантов окончания пути к сайту
//        if(!path.startsWith("/")) {
//            path = "/" + path;
//        }
//
//        return path;
    }

    @Override
    protected void compute() {

        var site = page.getSite();

        Document document;
        int statusCode;

        if (isCancelled()) {System.out.println("Остановлена"); return;}

        randomTimeout();

//        synchronized (site) {
//            try {
//                var interval = searchBot.getRequestsInterval();
//                int min = interval.getMin();
//                var time = min + random.nextInt(interval.getMax() - min + 1); // Интервал миллисекунд
//                Thread.sleep(time);
//            } catch (Exception e) {
//                e.printStackTrace(System.err);
//            }

        try {
            // По умолчанию выполняется нужный метод - Get()
            var response = Jsoup.connect(page.getUrl())
                    .userAgent(searchBot.getUserAgent())
                    .referrer(searchBot.getReferer())
//                        .method(Connection.Method.GET)
//                    .ignoreContentType(true)
                    .execute();
            statusCode = response.statusCode();
            document = response.parse();
//            document = connection
//                    .get();
        } catch (HttpStatusException e) {
            // Ошибка не связана напрямую с программой - подробное описание анализировать не требуется
            System.err.println(e.getLocalizedMessage());

            statusCode = e.getStatusCode();
            page.setCode(statusCode);
            savePage(page);

            updateSite(site);

            return;
        } catch (UnsupportedMimeTypeException e) {
            // По ссылке не страница, а, например, картинка. Не ошибка. Но такие страницы не нужны - удаляем их.
            System.err.println(e.getLocalizedMessage());

            page.setCode(415); // Unsupported Media Type («Неподдерживаемый тип данных»)
            savePage(page);

            updateSite(site);
            return;
        } catch (Exception e) {
            e.printStackTrace();

            page.setCode(500); // Internal Server Error («Внутренняя ошибка сервера»)
            page.setContent("jsoup: " + e.getLocalizedMessage()); // TODO Удалить после отладки
            savePage(page);

            saveError(site, e.getLocalizedMessage());

            return;
//        } finally {
            // В любом случае нужен перерыв между запросами
//                randomTimeout();
////                synchronized (site) {
//                    try {
//                        var interval = searchBot.getRequestsInterval();
//                        int min = interval.getMin();
//                        var time = min + random.nextInt(interval.getMax() - min + 1); // Интервал миллисекунд
//                        Thread.sleep(time);
//                    } catch (Exception e) {
//                        e.printStackTrace(System.err);
//                    }
//                }
        }
//        }


        var html = document.html(); //.replaceAll("\r\n", "");

//            var byteBuffer = StandardCharsets.UTF_8.encode(html);
//            html = new String(byteBuffer.array(), StandardCharsets.UTF_8);

        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        html = new String(b, StandardCharsets.UTF_8);

        page.setCode(statusCode);
        page.setContent(html);
        savePage(page);

        updateSite(site);

        if (isCancelled()) {System.out.println("Остановлена"); return;}

//        var taskList = new ArrayList<PageAnalyzer>();

        // URL начинается с переданного корня и не содержит ссылок на внутренние элементы страницы (не содержит #)
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

        synchronized (site) {
            var existingPaths = pageRepository.findBySiteAndPathIn(site, paths).stream()
                    .map(Page::getPath)
                    .map(this::getNormalizedPath)
                    .distinct()
                    .toList();

            if (isCancelled()) {System.out.println("Остановлена"); return;}

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

            pageRepository.saveAll(newPages);
        }

        updateSite(site);

        if (isCancelled()) {System.out.println("Остановлена"); return;}

//        var pageNode = applicationContext.getBean(PageNode.class);
//        pageNode.compute();
//        var newPages = pageNode.getChildren();

        var taskList = newPages.stream()
                .map(newPage -> {
                    var task = applicationContext.getBean(PageAnalyzer.class);
                    task.setPage(newPage);
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

        if (isCancelled()) {System.out.println("Остановлена"); return;}

        for (var task : taskList) {
            try {
                task.join();
//                task.invoke();
//                ForkJoinPool.commonPool().invoke(task);
//                randomTimeout();
            } catch (Exception e) {
                e.printStackTrace();

                page.setCode(500); // Internal Server Error («Внутренняя ошибка сервера»)
                page.setContent("taskList: " + e.getLocalizedMessage()); // TODO Удалить после отладки
                savePage(page);

                saveError(site, e.getLocalizedMessage());
            }
        }

        if (isCancelled()) {System.out.println("Остановлена"); return;}

        if (page.isRoot()) {
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
            System.err.println(e.getLocalizedMessage());

            statusCode = e.getStatusCode();
            page.setCode(statusCode);
            savePage(page);

            saveError(site, e.getLocalizedMessage());

            return;
        } catch (Exception e) {
            e.printStackTrace();

            page.setCode(500); // Internal Server Error («Внутренняя ошибка сервера»)
            page.setContent("jsoup: " + e.getLocalizedMessage()); // TODO Удалить после отладки
            savePage(page);

            saveError(site, e.getLocalizedMessage());

            return;
        }

        var html = document.html();
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        html = new String(b, StandardCharsets.UTF_8);

        page.setCode(statusCode);
        page.setContent(html);
        savePage(page);

        updateSite(site);

        // Леммы
        var lemmasFinder = applicationContext.getBean(LemmasFinder.class);
        var lemmas = lemmasFinder.findLemmasInHtml(html);

        synchronized (page) {

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

            try {
                saveLemmatizationChanges(deletingLemmas, changedLemmas, deletingIndexes, changedIndexes);
            } catch (Exception e) {
                e.printStackTrace();
                saveError(site, e.getLocalizedMessage());
            }

        }
    }

    private synchronized void savePage(Page page) {
        pageRepository.save(page);
    }

    private void updateSite(Site site) {
        site.setStatusTime(LocalDateTime.now());
        saveSite(site);
    }

    private void saveError(Site site, String error) {
        site.setLastError(error);
        site.setStatus(IndexingStatus.FAILED);
        updateSite(site);
    }

    private synchronized void saveSite(Site site) {
        siteRepository.save(site);
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

    private synchronized void randomTimeout() {
        try {
            var interval = searchBot.getRequestsInterval();
            int min = interval.getMin();
            var time = min + random.nextInt(interval.getMax() - min + 1); // Интервал миллисекунд
            Thread.sleep(time);
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }

    @Transactional
    private void saveLemmatizationChanges(List<Lemma> deletingLemmas, List<Lemma> savingLemmas,
                                          List<Index> deletingIndexes, List<Index> savingIndexes){
        indexRepository.deleteAll(deletingIndexes);
        lemmaRepository.deleteAll(deletingLemmas);
        lemmaRepository.saveAll(savingLemmas);
        indexRepository.saveAll(savingIndexes);
    }
}

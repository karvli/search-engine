package searchengine.dto.indexing;

import lombok.AccessLevel;
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
import searchengine.config.SearchBot;
import searchengine.model.*;
import searchengine.services.LemmasFinder;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ForkJoinTask;
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

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private List<PageAnalyzer> children = new ArrayList<>(); // Подчинённые задачи

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
        if (!url.startsWith("/")) {
            url = "/" + url;
        }

        return url;
    }

    private String getNormalizedPath(String url) {
        return getNormalizedPath(page.getSite(), url);
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (!cancelChildrenTasks(mayInterruptIfRunning)) {
            return false;
        }

        return super.cancel(mayInterruptIfRunning);
    }

    @Override
    protected void compute() {
        randomTimeout();

        if (analyzeStopped()) {
            return;
        }

        analyzePage();
        if (!page.canBeParsed()) {
            return;
        }

        var site = page.getSite();

        if (analyzeStopped()) {
            return;
        }

        var newPages = findNewPages();

        for (var newPage : newPages) {
            var task = applicationContext.getBean(PageAnalyzer.class);
            task.setPage(newPage);

            if (analyzeStopped()) {
                return;
            }

            task.fork();
            children.add(task);
        }

        if (analyzeStopped()) {
            return;
        }

        for (var task : children) {
            try {
                task.join();
            } catch (Exception e) {
                e.printStackTrace(System.err);

                page.setCode(500); // Internal Server Error («Внутренняя ошибка сервера»)
                savePage(page);

                saveError(site, e);
            }

            if (analyzeStopped()) {
                return;
            }
        }

        if (analyzeStopped()) {
            return;
        }

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
            savePage(page);

            saveError(site, e);

            return;
        }

        if (isCancelled()) {
            return;
        }

        var html = document.html();

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

        if (isCancelled()) {
            return;
        }

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

                if (isCancelled()) {
                    return;
                }

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

                if (isCancelled()) {
                    return;
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

            if (isCancelled()) {
                return;
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

            var deletingIndexes = usedIndexes.stream()
                    .filter(index -> !changedIndexes.contains(index))
                    .filter(index -> !unchangedIndexes.contains(index))
                    .toList();

            if (isCancelled()) {
                return;
            }

            try {
                saveLemmatizationChanges(deletingLemmas, changedLemmas, deletingIndexes, changedIndexes);
            } catch (Exception e) {
                e.printStackTrace(System.err);
                saveError(site, e);
            }

        }
    }

    private List<Page> findNewPages() {
        var site = page.getSite();

        // URL начинается с переданного корня и не содержит ссылок на внутренние элементы страницы (не содержит #)
        var document = Jsoup.parse(page.getContent());
        var regex = "(?i)^((" + site.getUrl() + ")|/)[^#]*$";
        var elements = document.select("a[href~=" + regex + "]");

        var paths = elements.stream()
                .map(element -> element.attr("href"))
                .map(this::getNormalizedPath)
                .distinct()
                .toList();

        if (analyzeStopped()) {
            return Collections.emptyList();
        }

        List<Page> newPages;

        synchronized (site) {
            var existingPaths = pageRepository.findBySiteAndPathIn(site, paths).stream()
                    .map(Page::getPath)
                    .map(this::getNormalizedPath)
                    .distinct()
                    .toList();

            if (analyzeStopped()) {
                return Collections.emptyList();
            }

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

            if (analyzeStopped()) {
                return Collections.emptyList();
            }

            pageRepository.saveAll(newPages);
        }

        updateSite(site);

        return newPages;
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
        site.setStatusTime(LocalDateTime.now());
        saveSite(site);
    }

    private void saveError(Site site, String error) {
        site.setLastError(error);
        site.setStatus(IndexingStatus.FAILED);
        updateSite(site);
    }

    private void saveError(Site site, Exception error) {
        saveError(site, error.getLocalizedMessage());
    }

    private void saveSite(Site site) {
        if (analyzeStopped()) {
            return;
        }

        synchronized (site) {
            try {
                siteRepository.save(site);
            } catch (Exception e) {
                saveError(page.getSite(), e);
                throw e;
            }

        }
    }

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
                                          List<Index> deletingIndexes, List<Index> savingIndexes) {
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
            cancelChildrenTasks();
            children.forEach(ForkJoinTask::quietlyJoin);
        }

        return stopped;
    }

    private boolean cancelChildrenTasks(boolean mayInterruptIfRunning) {
        return children.stream()
                .filter(task -> !task.isDone())
                .allMatch(task -> task.cancel(mayInterruptIfRunning));
    }

    private boolean cancelChildrenTasks() {
        return cancelChildrenTasks(true);
    }
}

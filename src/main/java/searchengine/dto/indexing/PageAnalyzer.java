package searchengine.dto.indexing;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import searchengine.config.SearchBot;
import searchengine.model.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;
import java.util.concurrent.RecursiveAction;

@RequiredArgsConstructor
@Getter
@Setter
@Component
public class PageAnalyzer extends RecursiveAction {

    private final ApplicationContext applicationContext;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final SearchBot searchBot;

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

            saveError(site, e.getLocalizedMessage());

            return;
        } catch (Exception e) {
            e.printStackTrace();

            page.setCode(500);
            page.setContent("jsoup"); // TODO Удалить после отладки
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

            newPages = paths.stream()
                    .filter(p -> !existingPaths.contains(p))
                    .map(path -> {
                        var newPage = new Page();
                        newPage.setSite(site);
                        newPage.setPath(path);
                        newPage.setCode(102);

                        return newPage;
                    })
                    .toList();

            pageRepository.saveAll(newPages);
        }

        updateSite(site);

        var taskList = newPages.stream()
                .map(newPage -> {
                    var task = applicationContext.getBean(PageAnalyzer.class);
                    task.setPage(newPage);
                    task.fork();
                    randomTimeout();

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

        for (var task : taskList) {
            try {
                task.join();
//                task.invoke();
//                ForkJoinPool.commonPool().invoke(task);
                randomTimeout();
            } catch (Exception e) {
                e.printStackTrace();

                page.setCode(500);
                page.setContent("taskList"); // TODO Удалить после отладки
                savePage(page);

                saveError(site, e.getLocalizedMessage());
            }
        }

        if (page.isRoot()) {
            site.setStatus(IndexingStatus.INDEXED);
            saveSite(site);
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
//        var foundCount = pageRepository.findBySiteAndPath(site, path).size();
//        if (foundCount != 0) {
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
//            page.setCode(500);
//            page.setContent("task"); // TODO Удалить после отладки
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
}

package searchengine.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import searchengine.dto.indexing.IndexPageRequest;
import searchengine.dto.indexing.IndexingResponse;
import searchengine.dto.search.SearchResponse;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.services.IndexingService;
import searchengine.services.SearchService;
import searchengine.services.StatisticsService;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api")
public class ApiController {

    private final StatisticsService statisticsService;
    private final IndexingService indexingService;
    private final SearchService searchService;

    @GetMapping("/statistics")
    public ResponseEntity<StatisticsResponse> statistics() {
        return ResponseEntity.ok(statisticsService.getStatistics());
    }

    @GetMapping({"/startIndexing", "/startindexing"})
    public ResponseEntity<IndexingResponse> startIndexing() {
        var response = indexingService.startIndexing();

        if (!response.isResult()) {
            return ResponseEntity
                    .badRequest()
                    .body(response);
        }

        return ResponseEntity.ok(response);
    }

    @GetMapping({"/stopIndexing", "/stopindexing"})
    public ResponseEntity<IndexingResponse> stopIndexing() {
        var response = indexingService.stopIndexing();

        if (!response.isResult()) {
            return ResponseEntity
                    .badRequest()
                    .body(response);
        }

        return ResponseEntity.ok(response);
    }

    // C @RequestBody возникает ошибка Content-Type 'application/x-www-form-urlencoded;charset=UTF-8' is not supported.
    // Способ обхода, найденный в интернете: оставить автоматическое определение параметров.
    @PostMapping({"/indexPage", "/indexpage"})
    public ResponseEntity<IndexingResponse> indexPage(IndexPageRequest body) {
        var url = body.getUrl();
        var response = indexingService.indexPage(url);

        if (!response.isResult()) {
            return ResponseEntity
                    .badRequest()
                    .body(response);
        }

        return ResponseEntity.ok(response);
    }

    @GetMapping("/search")
    public ResponseEntity<SearchResponse> search(String query,
                                                 @RequestParam(required = false) String site,
                                                 @RequestParam(required = false) Integer offset,
                                                 @RequestParam(required = false) Integer limit) {
        SearchResponse response;

        if (offset == null) {
            offset = 0;
        }
        if (limit == null) {
            limit = 20;
        }

        var allSites = site == null || site.isBlank();
        if (allSites) {
            response = searchService.searchAllSites(query, limit, offset);
        } else {
            response = searchService.searchSite(site, query, limit, offset);
        }

        if (!response.isResult()) {
            return ResponseEntity
                    .badRequest()
                    .body(response);
        }

        return ResponseEntity.ok(response);
    }
}

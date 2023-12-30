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

    @GetMapping("/startIndexing")
    public ResponseEntity<IndexingResponse> startIndexing() {
        return ResponseEntity.ok(indexingService.startIndexing());
    }

    @GetMapping("/stopIndexing")
    public ResponseEntity<IndexingResponse> stopIndexing() {
        return ResponseEntity.ok(indexingService.stopIndexing());
    }

    // C @RequestBody возникает ошибка Content-Type 'application/x-www-form-urlencoded;charset=UTF-8' is not supported.
    // Способ обхода, найденный в интернете: оставить автоматическое определение параметров.
    @PostMapping("/indexPage")
    public ResponseEntity<IndexingResponse> indexPage(IndexPageRequest body) {
        var url = body.getUrl();

//        if (url.isBlank()) {
//            return ResponseEntity
//                    .status(HttpStatus.BAD_REQUEST)
//                    .body(IndexingResponse.builder().result(false).error("Не передано значение url").build());
//        }

        return ResponseEntity.ok(indexingService.indexPage(url));
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

        return ResponseEntity.ok(response);
    }
}

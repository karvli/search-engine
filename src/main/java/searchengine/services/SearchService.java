package searchengine.services;

import searchengine.dto.search.SearchResponse;

public interface SearchService {
    SearchResponse searchSite(String siteUrl, String query, int limit, int offset);
    SearchResponse searchAllSites(String query, int limit, int offset);
}

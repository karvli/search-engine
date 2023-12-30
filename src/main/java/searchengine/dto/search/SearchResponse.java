package searchengine.dto.search;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResponse {
    private boolean result;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer count;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<SearchData> data;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String error;
}

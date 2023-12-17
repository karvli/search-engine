package searchengine.dto.statistics;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import searchengine.model.IndexingStatus;

@Data
public class DetailedStatisticsItem {
    private String url;
    private String name;
    private IndexingStatus status;

//    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
//    @JsonSerialize(using = LocalDateTimeSerializer.class)
    private long statusTime;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String error;

    private int pages;
    private int lemmas;
}

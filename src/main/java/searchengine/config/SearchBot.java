package searchengine.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "search-bot-settings")
public class SearchBot {
    private String userAgent;
    private String referer;
    private RequestsInterval requestsInterval;
}

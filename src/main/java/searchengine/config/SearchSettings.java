package searchengine.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "search-settings")
public class SearchSettings {
    int wordsRange = 2;

    public void setWordsRange(int wordsRange) {
        if (wordsRange < 1) {
            throw new IllegalArgumentException("wordsRange не может быть меньше 1");
        }
        this.wordsRange = wordsRange;
    }
}

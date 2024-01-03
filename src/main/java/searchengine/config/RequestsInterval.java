package searchengine.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "search-bot-settings.requests-interval")
public class RequestsInterval {
    private int min;
    private Integer max; // Если требуется указать точное время, то верхняя граница будет отсутствовать

    public void setMin(int min) {
        if (min < 0) {
            throw new IllegalArgumentException("min не может быть меньше 0");
        }
        this.min = min;
    }

    public void setMax(Integer max) {
        if (max != null) {
            if (max < 0) {
                throw new IllegalArgumentException("max не может быть меньше 0");
            }
            if (max < min) {
                System.out.println("max меньше min, поэтому будет проигнорирован");
            }
        }
        this.max = max;
    }
}

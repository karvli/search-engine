package searchengine.config;

import org.apache.lucene.morphology.english.EnglishLuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class MorphologyConfig {

    @Bean
    public RussianLuceneMorphology getRussianLuceneMorphology() throws IOException {
        return new RussianLuceneMorphology();
    }

    @Bean
    public EnglishLuceneMorphology getEnglishLuceneMorphology() throws IOException {
        return new EnglishLuceneMorphology();
    }
}

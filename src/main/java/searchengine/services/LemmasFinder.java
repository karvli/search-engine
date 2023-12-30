package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.english.EnglishLuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

@RequiredArgsConstructor
@Component
public class LemmasFinder {

    private static final List<String> PARTICLES = Arrays.asList("МЕЖД", "СОЮЗ", "ПРЕДЛ", "ЧАСТ",
            "PREP", "VBE");

    private final RussianLuceneMorphology russianMorphology;
    private final EnglishLuceneMorphology englishMorphology;

    /**
     * Выполняет поиск русских и английских лемм, возвращает количество упоминаний каждой леммы в тексте
     *
     * @param text текст, в котором будут найдены русские и английские леммы
     * @return соответствие, где ключ - лемма, а значение - сколько раз она встречается в text
     */
    public HashMap<String, Integer> findLemmas(String text) {
        var lemmas = new HashMap<String, Integer>();

        var words = getWords(text);
        for (var word : words) {
            LuceneMorphology morphology;

            if (russianMorphology.checkString(word)) {
                morphology = russianMorphology;
            } else {
                morphology = englishMorphology;
            }

            // Некоторые слова могут иметь несколько нормальных форм.
            // Например, у test, tested одна форма - test, а у testing их две - testing и test (именно в таком порядке).
            // Для поискового запроса достаточно одного варианта. Выбирается первый найденный - самый приоритетный.
            var normalForms = morphology.getNormalForms(word);
//            for (var normalWord : normalForms) {
            var normalWord = normalForms.get(0);
            var count = lemmas.getOrDefault(normalWord, 0) + 1;
            lemmas.put(normalWord, count);
//            }
        }

        return lemmas;
    }

    public String htmlToText(String html) {
        return Jsoup.parse(html).text();
    }

    /**
     * Очищается текст HTML от тегов, а затем выполняет поиск русских и английских лемм, возвращает количество
     * упоминаний каждой леммы в тексте
     *
     * @param html текст html, в котором будут найдены русские и английские леммы
     * @return соответствие, где ключ - лемма, а значение - сколько раз она встречается в text
     */
    public HashMap<String, Integer> findLemmasInHtml(String html) {
        var text = htmlToText(html);
        return findLemmas(text);
    }

    /**
     * Получает из текста русские и английские слова в нижнем регистре
     *
     * @param text анализируемый текст
     * @return слова
     */
    private List<String> getWords(String text) {
        var words = text.strip().toLowerCase().split("\\s+");

        return Arrays.stream(words)
                .map(this::clearUnnecessarySymbols)
                .filter(this::isFittingWord)
                .toList();
    }

    /**
     * Удаляет ненужные символы в начале и конце слова. К ним относятся спецсимволы и символы не анализируемых языков.
     *
     * @param word обрабатываемое слово
     * @return слово, очищенное от ненужных символов
     */
    private String clearUnnecessarySymbols(String word) {
        // В русском языке могут использоваться дефисы. Например, в причастиях "кто-то", "что-то", "какой-то".
        var wordRegex = "^[^а-яёa-z]*(?<word>([а-яёa-z]+)|([а-яё]+[а-яё\\-]*[а-яё]+))[^а-яёa-z]*$";
        var matcher = Pattern.compile(wordRegex).matcher(word);
        if (matcher.find()) {
            word = matcher.group("word");
        }

        return word;
    }

    /**
     * Проверяет корректность слова: русское или английское слово в нижнем регистре, не являющееся междометием,
     * союзом, предлогом или частицей
     *
     * @param word проверяемое слово
     * @return слово удовлетворяет условиям
     */
    private boolean isFittingWord(String word) {
        if (word.isBlank()) {
            return false;
        }

        if (russianMorphology.checkString(word)) {
            return notParticle(word, russianMorphology);
        } else if (englishMorphology.checkString(word)) {
            return notParticle(word, englishMorphology);
        }

        return false;
    }

    private boolean notParticle(String word, LuceneMorphology morphology) {
        return morphology.getMorphInfo(word).stream()
                .filter(s -> !s.isBlank())
                .map(String::toUpperCase)
                .map(s -> s.split("\\s+"))
                .flatMap(Arrays::stream)
                .noneMatch(PARTICLES::contains);
    }
}

package searchengine.services;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.english.EnglishLuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.util.*;
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
    public HashMap<String, Integer> findLemmas(@NonNull String text) {
        var lemmas = new HashMap<String, Integer>();

        var words = getWords(text);
        for (var word : words) {
            var normalWord = getNormalWord(word);
            if (normalWord.isBlank()) {
                continue;
            }

            var count = lemmas.getOrDefault(normalWord, 0) + 1;
            lemmas.put(normalWord, count);
        }

        return lemmas;
    }

    public String htmlToText(@NonNull String html) {
        return Jsoup.parse(html).text();
    }

    /**
     * Очищается текст HTML от тегов, а затем выполняет поиск русских и английских лемм, возвращает количество
     * упоминаний каждой леммы в тексте
     *
     * @param html текст html, в котором будут найдены русские и английские леммы
     * @return соответствие, где ключ - лемма, а значение - сколько раз она встречается в text
     */
    public HashMap<String, Integer> findLemmasInHtml(@NonNull String html) {
        var text = htmlToText(html);
        return findLemmas(text);
    }


    /**
     * Выполняет поиск лемм, формирует сниппет результата поиска. Текст должен быть заранее очищено от html-тегов.
     * @param lemmas искомые леммы
     * @param text текст, очищенный от html-тегов
     * @return сниппет
     */
    public String getSnippet(@NonNull String text, @NonNull Set<String> lemmas) {
        var textLowerCase = text.toLowerCase(); // Для поиска без учёта регистра
        var words = getWords(textLowerCase);

        var snippet = new StringBuilder();
        var rangeSize = 2; // Количество значимых слов слева и справа от леммы
        var lastIndex = 0; // Последний проверенный символ текста
        var lastLemmaIndex = -1;
        var lastWordIndex = -1;
        var spoilerAdded = false; // Если слишком много совпадений

        for (var i = 0; i < words.size(); i++) {
            var word = words.get(i);
            var normalWord = getNormalWord(word);

            if (lemmas.contains(normalWord)) {

                if (i > 0 && lastLemmaIndex == -1) {
                    snippet.append("... ");
                }

                if (snippet.length() > 270 && !spoilerAdded) {
                    snippet.append("<details>");
                    spoilerAdded = true;
                }

                var startWordIndex = textLowerCase.indexOf(word, lastIndex);

                var previousIndex = Math.max(lastWordIndex, i - rangeSize);
                if (previousIndex != -1 && previousIndex < i - 1) {
                    var previousWord = words.get(previousIndex);
                    var start = textLowerCase.lastIndexOf(previousWord, startWordIndex);
                    var previousText = text.substring(start, startWordIndex);
                    snippet.append(previousText);
                }

                snippet.append("<b>");
                lastIndex = startWordIndex + word.length();
                var wordText = text.substring(startWordIndex, lastIndex);
                snippet.append(wordText);
                snippet.append("</b>");

                lastWordIndex = i;
                lastLemmaIndex = i;

                continue;
            }

            if (lastLemmaIndex == -1) {
                continue;
            }

            var endWordIndex = lastLemmaIndex + rangeSize; // Текст "до" текущего слова
            if (i <= endWordIndex) {
                // Добавление подсказки после леммы
                if (i == words.size() - 1) {
                    var addingText = text.substring(lastIndex);
                    snippet.append(addingText);
                    break;
                }

                var nextWord = words.get(i + 1);
                var end = textLowerCase.indexOf(nextWord, lastIndex);
                var addingText = text.substring(lastIndex, end);
                snippet.append(addingText);

                lastIndex = end;
                lastWordIndex = i;
            } else if (i == (endWordIndex + 1)) {
                var lastSymbol = snippet.substring(snippet.length() - 1);
                if (!Objects.equals(lastSymbol, " ")) {
                    snippet.append(" ");
                }
                snippet.append("... ");
            }
        }

        if (spoilerAdded) {
            snippet.append("</details>");
        }

        return snippet.toString().strip();
    }

    /**
     * Получает из текста русские и английские слова в нижнем регистре
     *
     * @param text анализируемый текст
     * @return слова
     */
    private List<String> getWords(@NonNull String text) {
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
    private boolean isFittingWord(@NonNull String word) {
        if (word.isBlank()) {
            return false;
        }

        var morphology = qualifyMorphology(word);
        if (morphology == null) {
            return false;
        }

        return morphology.getMorphInfo(word).stream()
                .filter(s -> !s.isBlank())
                .map(String::toUpperCase)
                .map(s -> s.split("\\s+"))
                .flatMap(Arrays::stream)
                .noneMatch(PARTICLES::contains);
    }

    /**
     * Возвращает нормальную форму слова для сохранения леммы в базу данных.
     * Некоторые слова могут иметь несколько нормальных форм. Для поискового запроса достаточно одного варианта.
     * В этом случае выбирается первый из списка нормальных форм - самый приоритетный.
     * Например, у test и tested одна форма - test, а у testing их две - testing и test (именно в таком порядке).
     *
     * @param word обрабатываемое слово
     * @return нормальная форма слова
     */
    private String getNormalWord(String word) {
        var morphology = qualifyMorphology(word);
        if (morphology == null) {
            return "";
        }

        var normalForms = morphology.getNormalForms(word);

        return normalForms.get(0);
    }

    /**
     * Определяет морфологию по переданному слову, возвращает нужную библиотеку для использования
     *
     * @param word проверяемое слово
     * @return библиотека морфологии, если морфология слова не поддерживается - null
     */
    private LuceneMorphology qualifyMorphology(String word) {
        if (russianMorphology.checkString(word)) {
            return russianMorphology;
        } else if(englishMorphology.checkString(word)) {
            return englishMorphology;
        }

        return null;
    }
}

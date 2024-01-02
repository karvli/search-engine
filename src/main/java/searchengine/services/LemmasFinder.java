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

    private static final List<String> PARTICLES = Arrays.asList("МЕЖД", "СОЮЗ", "ПРЕДЛ", "ЧАСТ", "PREP", "VBE");

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
     *
     * @param lemmas искомые леммы
     * @param text   текст, очищенный от html-тегов
     * @return сниппет
     */
    public String getSnippet(@NonNull String text, @NonNull Set<String> lemmas) {
        // Стандартизация разделителей для удобного отображения. Убираем ненужные переносы строк и т.п.
        var words = List.of(text.split("\\s+"));
        var wordsIndexes = new ArrayList<Integer>(); // Индексы значимых слов

        var snippet = new StringBuilder();
        var rangeSize = 2; // Количество значимых слов слева и справа от леммы
//        var lastIndex = 0; // Последний проверенный символ текста
        var lastLemmaIndex = -1; // Последняя добавленное слово леммы (для определения границ слов)
        var lastWordIndex = -1; // Последнее добавленное слово (не только леммы)
        var spoilerAdded = false; // Сокрытие части сниппета, если слишком много совпадений

        for (var i = 0; i < words.size(); i++) {
            var word = words.get(i);
            var searchWord = clearUnnecessarySymbols(word);

            if (searchWord.isBlank() || !isFittingWord(searchWord)) {
                continue;
            }

            var wordIndex = wordsIndexes.size(); // Для последующего поиска значимых слов
            wordsIndexes.add(i);

            var normalWord = getNormalWord(searchWord); // Нормальная форма всегда в нижнем регистре
            if (!lemmas.contains(normalWord)) {

                // Слово, не являющееся искомой леммой

                if (lastLemmaIndex == -1) {
                    continue;
                }

                var endWordIndex = lastLemmaIndex + rangeSize; // Текст "до" текущего слова
                if (wordIndex <= endWordIndex) {
                    // Подсказка (уточнение) после слова леммы
                    int startIndex = wordsIndexes.get(lastLemmaIndex);
                    startIndex = Math.max(startIndex, lastWordIndex) + 1; // После последнего добавленного
                    for (int j = startIndex; j <= i; j++) {
                        snippet.append(' ').append(words.get(j));
                    }

                    lastWordIndex = i;
                } else if (wordIndex == (endWordIndex + 1)) {
                    // Многоточие после окончание отрывка текста
                    snippet.append(" ...");
                }

                continue;
            }

            // Новое слово леммы

            if (snippet.length() > 270 && !spoilerAdded) {
                snippet.append("<details>");
                spoilerAdded = true;
            }

            if (i > 0) {
                if (lastLemmaIndex == -1) {
                    snippet.append("...");
                }

                if (lastWordIndex < i - 1) {
                    // Проверка и дополнение подсказки до слова леммы
                    int previousIndex = wordsIndexes.get(Math.max(wordIndex - rangeSize, 0));
                    if (lastWordIndex >= 0) {
                        previousIndex = Math.max(lastWordIndex + 1, previousIndex);
                    }
                    for (int j = previousIndex; j < i; j++) {
                        snippet.append(' ').append(words.get(j));
                    }
                }
            }

            snippet.append(' ');

            var endPrefixIndex = word.indexOf(searchWord);
            if (endPrefixIndex > 0) {
                snippet.append(word, 0, endPrefixIndex);
            }

            snippet.append("<b>").append(searchWord).append("</b>");

            var startPostfixIndex = endPrefixIndex + searchWord.length();
            if (startPostfixIndex < word.length()) {
                snippet.append(word, startPostfixIndex, word.length());
            }

            lastWordIndex = i;
            lastLemmaIndex = wordIndex;
        }

        if (lastWordIndex != words.size() - 1 && !snippet.substring(snippet.length() - 3).equals("...")) {
            // Частный случай: после последнего слова леммы в строке недостаточно значимых слов
            snippet.append(" ...");
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
        // Пример для строчных букв: "^[^а-яёa-z]*(?<word>([а-яёa-z]+)|([а-яё]+[а-яё\\-]*[а-яё]+))[^а-яёa-z]*$"
        var russianWordPattern = "а-яёА-ЯЁ";
        var wordPattern = russianWordPattern.concat("a-zA-Z");
        var wordRegex = "^[^".concat(wordPattern).concat("\\d]*(?<word>([").concat(wordPattern)
                .concat("]+)|([").concat(russianWordPattern).concat("]+[").concat(russianWordPattern)
                .concat("\\-]*[").concat(russianWordPattern).concat("]+))[^")
                .concat(wordPattern).concat("\\d]*$");
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

        word = word.toLowerCase(); // Библиотека работает только со словами в нижнем регистре

        var morphology = qualifyMorphology(word);
        if (morphology == null) {
            return false;
        }

        return morphology.getMorphInfo(word).stream().filter(s -> !s.isBlank()).map(String::toUpperCase).map(s -> s.split("\\s+")).flatMap(Arrays::stream).noneMatch(PARTICLES::contains);
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
        if (word.isBlank()) {
            return "";
        }

        word = word.toLowerCase();

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
        } else if (englishMorphology.checkString(word)) {
            return englishMorphology;
        }

        return null;
    }
}

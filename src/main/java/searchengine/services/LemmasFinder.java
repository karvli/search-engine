package searchengine.services;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.english.EnglishLuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import searchengine.config.SearchSettings;

import java.util.*;
import java.util.regex.Pattern;

@RequiredArgsConstructor
@Component
public class LemmasFinder {

    private static final List<String> PARTICLES = Arrays.asList("МЕЖД", "СОЮЗ", "ПРЕДЛ", "ЧАСТ", "PREP", "VBE");

    private final SearchSettings searchSettings;
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
        if (text.isBlank()) {
            return "";
        }

        var snippet = new StringBuilder();

        // Разделение на строки, чтобы убрать из пояснений слова соседних абзацев
        var lines = text.split("[\\r\\n]+");
        var spoilerAdded = false; // Сокрытие части сниппета, если слишком много совпадений

        for (var line : lines) {
            if (line.isBlank()) {
                continue;
            }

            spoilerAdded = addLineToSnippet(line.strip(), lemmas, snippet, spoilerAdded);
        }

        if (spoilerAdded) {
            snippet.append("</details>");
        }

        return snippet.toString().strip();
    }

    /**
     * Разбирает переданную строку и добавляет результат в StringBuilder сниппета
     *
     * @param line анализируемая строка
     * @param lemmas искомые леммы
     * @param snippet формируемые сниппет
     * @param spoilerAdded был ли уже добавлен тег спойлера
     * @return добавлен ли тег спойлера (частично повторяет spoilerAdded)
     */
    private boolean addLineToSnippet(String line, Set<String> lemmas, StringBuilder snippet, boolean spoilerAdded) {
        var words = line.split("[\u00a0\\s]+");
        var wordsIndexes = new ArrayList<Integer>(); // Индексы значимых слов

        var wordsRange = searchSettings.getWordsRange(); // Количество значимых слов слева и справа от леммы

        var lastLemmaIndex = -1; // Последняя добавленное слово леммы (для определения границ слов)
        var lastWordIndex = -1; // Последнее добавленное слово (не только леммы)

        for (var i = 0; i < words.length; i++) {
            var word = words[i];
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

                var endWordIndex = lastLemmaIndex + wordsRange; // Текст "до" текущего слова
                if (wordIndex <= endWordIndex) {
                    // Подсказка (уточнение) после слова леммы
                    int startIndex = wordsIndexes.get(lastLemmaIndex);
                    startIndex = Math.max(startIndex, lastWordIndex) + 1; // После последнего добавленного
                    for (int j = startIndex; j <= i; j++) {
                        snippet.append(' ').append(words[j]);
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

            var snippetLength = snippet.length(); // Нет смысла смотреть строки короче "<b></b>"
            var checkB = snippetLength >= 7 && lastLemmaIndex >= 0;

            if (i > 0) {
                if (lastLemmaIndex == -1
                        // Многоточие могло быть уже добавлена на предыдущей строке
                        && !(snippetLength >= 3 && snippet.substring(snippetLength - 3).equals("..."))) {
                    snippet.append("...");
                }

                if (lastWordIndex < i - 1) {
                    checkB = false;

                    // Проверка и дополнение подсказки до слова леммы
                    int previousIndex = wordsIndexes.get(Math.max(wordIndex - wordsRange, 0));
                    if (lastWordIndex >= 0) {
                        previousIndex = Math.max(lastWordIndex + 1, previousIndex);
                    }
                    for (int j = previousIndex; j < i; j++) {
                        snippet.append(' ').append(words[j]);
                    }
                }
            }

            snippet.append(' ');

            var endPrefixIndex = word.indexOf(searchWord);
            if (endPrefixIndex > 0) {
                snippet.append(word, 0, endPrefixIndex);
                checkB = false;
            }


            if (checkB && wordsIndexes.get(lastLemmaIndex) == i - 1) {
                // Продолжение блока <b>: надо удалить ранее добавленный закрывающий тег, но оставить пробел после него
                snippet.replace(snippetLength - 4, snippetLength, "");
            } else {
                // Новый блок <b>
                snippet.append("<b>");
            }

            snippet.append(searchWord).append("</b>");

            var startPostfixIndex = endPrefixIndex + searchWord.length();
            if (startPostfixIndex < word.length()) {
                snippet.append(word, startPostfixIndex, word.length());
            }

            lastWordIndex = i;
            lastLemmaIndex = wordIndex;
        }

        var startIndex = snippet.length() - 3;
        if (lastWordIndex != words.length - 1 && startIndex >= 0 && !snippet.substring(startIndex).equals("...")) {
            // Частный случай: после последнего слова леммы в строке недостаточно значимых слов
            snippet.append(" ...");
        }

        return spoilerAdded;
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

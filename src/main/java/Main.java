import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.english.EnglishLuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import searchengine.LemmasFinder;

import java.io.IOException;
import java.util.List;

public class Main {
    public static void main(String[] args) throws IOException {
        LuceneMorphology luceneMorph = new RussianLuceneMorphology();

        List<String> wordBaseForms = luceneMorph.getMorphInfo("что-то");
        wordBaseForms.forEach(System.out::println);

        System.out.println();
        wordBaseForms = luceneMorph.getMorphInfo("какой-то");
        wordBaseForms.forEach(System.out::println);

//        System.out.println();
//        wordBaseForms = luceneMorph.getMorphInfo("либо");
//        wordBaseForms.forEach(System.out::println);

//        System.out.println();
//        wordBaseForms = luceneMorph.getMorphInfo("некоторый");
//        wordBaseForms.forEach(System.out::println);
//
//        System.out.println();
//        wordBaseForms = luceneMorph.getNormalForms("предположить");
//        wordBaseForms.forEach(System.out::println);

//        var check = luceneMorph.checkString("что-то");
//        System.out.println(check);

//        luceneMorph = new EnglishLuceneMorphology();
//
//        System.out.println();
//        wordBaseForms = luceneMorph.getMorphInfo("is");
//        wordBaseForms.forEach(System.out::println);
//
//        System.out.println();
//        wordBaseForms = luceneMorph.getMorphInfo("as");
//        wordBaseForms.forEach(System.out::println);
//
//        System.out.println();
//        wordBaseForms = luceneMorph.getMorphInfo("union");
//        wordBaseForms.forEach(System.out::println);

        var text = """
                Повторное появление леопарда в Осетии позволяет предположить,
                что леопард постоянно обитает в некоторых районах Северного
                Кавказа.""";

        var lemmasFinder = new LemmasFinder();
        var lemmas = lemmasFinder.findLemmas(text);
        System.out.println(lemmas);

//        var matches = "ё".matches("(^[а-яё]+$)|(^[a-z]+$)");
//        System.out.println(matches);
    }
}

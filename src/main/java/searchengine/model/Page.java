package searchengine.model;

import jakarta.persistence.Index;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Data
@NoArgsConstructor
@Entity
@Table(name = "pages", indexes = {@Index(columnList = "path")})
public class Page {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(updatable = false)
    private int id;

    @ManyToOne(cascade = CascadeType.MERGE)
    @OnDelete(action = OnDeleteAction.CASCADE) // Удалять страницы при удалении сайта
    @JoinColumn(nullable = false)
    @NonNull
    private Site site;

    // В описании указано использование типа "TEXT"» и индексирования, но TEXT не индексируется.
    // Оставил стандартный VARCHAR(255). Этого должно хватить.
    @Column(nullable = false)
    @NonNull
    private String path;

    private int code;

    @Column(columnDefinition = "MEDIUMTEXT", nullable = false)
    @NonNull
    private String content = "";

    public String getUrl() {
        var rootPath = site.getUrl();
        var needSlash = !rootPath.endsWith("/");

        if (path.startsWith("/")) {
            if (needSlash) {
                needSlash = false;
            } else {
                rootPath = rootPath.substring(0, rootPath.length() - 1);
            }
        }
        if (needSlash) {
            rootPath += "/";
        }

        return rootPath + path;
    }

    public boolean isRoot() {
        return path.strip().equals("/");
    }

    public boolean canBeParsed() {
        return !content.isBlank();
    }

}

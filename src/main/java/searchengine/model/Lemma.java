package searchengine.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Data
@NoArgsConstructor
@Entity
@Table(name = "lemmas")
public class Lemma {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @ManyToOne(cascade = CascadeType.MERGE)
    @OnDelete(action = OnDeleteAction.CASCADE) // Удалять лемму при удалении сайта
    @JoinColumn(nullable = false)
    @NonNull
    private Site site;

    @Column(columnDefinition = "VARCHAR(255)", nullable = false)
    @NonNull
    private String lemma;

    private int frequency;
}

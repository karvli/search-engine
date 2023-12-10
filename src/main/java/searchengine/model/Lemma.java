package searchengine.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;

@Data
@NoArgsConstructor
@Entity
@Table(name = "lemmas")
public class Lemma {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @ManyToOne(cascade = CascadeType.MERGE)
    @JoinColumn(nullable = false)
    @NonNull
    private Site site;

    @Column(columnDefinition = "VARCHAR(255)", nullable = false)
    @NonNull
    private String lemma;

    private int frequency;
}

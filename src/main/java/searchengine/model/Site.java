package searchengine.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity
@Table(name = "sites")
public class Site {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @NonNull
    private IndexingStatus status;

    @Column(name = "status_time", columnDefinition = "DATETIME", nullable = false)
    @NonNull
    private LocalDateTime statusTime;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(columnDefinition = "VARCHAR(255)", nullable = false, unique = true)
    @NonNull
    private String url;

    // Имя уникально, т.к. индексировать один и тот же сайт более одного раза нет смысла. Также это ускоряет удаление.
    @Column(columnDefinition = "VARCHAR(255)", nullable = false)
    @NonNull
    private String name;

    public boolean indexingFailed() {
        return status == IndexingStatus.FAILED;
    }
}

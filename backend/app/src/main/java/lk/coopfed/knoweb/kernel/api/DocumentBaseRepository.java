package lk.coopfed.knoweb.kernel.api;

import java.util.Optional;
import java.util.UUID;

public interface DocumentBaseRepository {

    DocumentRecord save(DocumentRecord document);

    Optional<DocumentRecord> findById(UUID id);
}
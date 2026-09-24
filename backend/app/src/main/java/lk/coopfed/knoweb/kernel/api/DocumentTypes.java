package lk.coopfed.knoweb.kernel.api;

import java.util.List;
import java.util.Optional;

/** The document type registry, read-only: reference data every class may read. */
public interface DocumentTypes {

    Optional<DocumentType> find(String code);

    List<DocumentType> all();
}

package lk.coopfed.knoweb.kernel.internal.stub;

import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryDocumentBaseRepository
        implements DocumentBaseRepository {

    private final Map<UUID, DocumentRecord> documents = new ConcurrentHashMap<>();

    @Override
    public DocumentRecord save(
            DocumentRecord document) {
        documents.put(
                document.id(),
                document);

        return document;
    }

    @Override
    public Optional<DocumentRecord> findById(
            UUID id) {
        return Optional.ofNullable(
                documents.get(id));
    }
}
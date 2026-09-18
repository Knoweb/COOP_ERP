package lk.coopfed.knoweb.kernel.internal.stub;

import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentLinkRecord;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.DocumentStateHistoryRecord;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 17A stub. Keeps documents in memory but enforces the same rules the 19A trigger
 * will: an issued header changes only in its status, and lines, links and history
 * are append-only.
 */
@Component
public class InMemoryDocumentBaseRepository
        implements DocumentBaseRepository {

    private final Map<UUID, DocumentRecord> documents = new ConcurrentHashMap<>();
    private final Map<UUID, List<DocumentLineRecord>> lines = new ConcurrentHashMap<>();
    private final List<DocumentLinkRecord> links = new CopyOnWriteArrayList<>();
    private final Map<UUID, List<DocumentStateHistoryRecord>> history = new ConcurrentHashMap<>();

    @Override
    public DocumentRecord save(
            DocumentRecord document) {
        DocumentRecord existing = documents.get(document.id());

        if (existing != null
                && existing.isIssued()
                && !existing.withStatus(document.status()).equals(document)) {
            throw new ProblemException("document.immutable");
        }

        documents.put(
                document.id(),
                document);

        return document;
    }

    @Override
    public void saveLines(
            UUID documentId,
            List<DocumentLineRecord> newLines) {
        lines.computeIfAbsent(
                        documentId,
                        id -> new CopyOnWriteArrayList<>())
                .addAll(newLines);
    }

    @Override
    public void addLink(
            DocumentLinkRecord link) {
        links.add(link);
    }

    @Override
    public void addStateTransition(
            DocumentStateHistoryRecord transition) {
        history.computeIfAbsent(
                        transition.documentId(),
                        id -> new CopyOnWriteArrayList<>())
                .add(transition);

        DocumentRecord current = documents.get(transition.documentId());

        if (current != null) {
            documents.put(
                    current.id(),
                    current.withStatus(transition.toStatus()));
        }
    }

    @Override
    public Optional<DocumentRecord> findById(
            UUID id) {
        return Optional.ofNullable(
                documents.get(id));
    }

    @Override
    public List<DocumentLineRecord> findLines(
            UUID documentId) {
        return List.copyOf(
                lines.getOrDefault(documentId, List.of()));
    }

    @Override
    public List<DocumentLinkRecord> findLinks(
            UUID documentId) {
        List<DocumentLinkRecord> result = new ArrayList<>();

        for (DocumentLinkRecord link : links) {
            if (documentId.equals(link.fromDocumentId())
                    || documentId.equals(link.toDocumentId())) {
                result.add(link);
            }
        }

        return List.copyOf(result);
    }

    @Override
    public List<DocumentStateHistoryRecord> findHistory(
            UUID documentId) {
        return List.copyOf(
                history.getOrDefault(documentId, List.of()));
    }
}

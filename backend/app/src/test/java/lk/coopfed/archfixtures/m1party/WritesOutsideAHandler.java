package lk.coopfed.archfixtures.m1party;

/**
 * Violates the writers rule: an ordinary service that saves through a repository. Nothing
 * audits this change and no event announces it, because it never passed through a handler.
 */
public class WritesOutsideAHandler {

    private final FixtureRepository repository;

    public WritesOutsideAHandler(FixtureRepository repository) {
        this.repository = repository;
    }

    public void quietlyChange(Object thing) {
        repository.save(thing);
    }

    /** Reading is fine; only the save above is the violation. */
    public Object read(java.util.UUID id) {
        return repository.findById(id);
    }
}

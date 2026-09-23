package lk.coopfed.knoweb.kernel.api;

import java.util.UUID;

public record Subject(String type, UUID id) {

    public static Subject of(String type, UUID id) {
        return new Subject(type, id);
    }
}

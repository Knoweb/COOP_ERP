package lk.coopfed.archfixtures.m2catalogue.api;

import java.util.UUID;

/** An internal command as other modules call it (like M2's BatchRegistration): correct on its own. */
public interface StockRegistration {

    UUID register(RegisterStock command);
}

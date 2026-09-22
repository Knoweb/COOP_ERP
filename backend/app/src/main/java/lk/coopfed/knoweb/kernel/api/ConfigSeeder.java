package lk.coopfed.knoweb.kernel.api;

import java.util.Map;

/**
 * Allows seed loaders to register default configuration items.
 */
public interface ConfigSeeder {

    void addDefaults(Map<String, String> items);
}

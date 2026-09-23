package lk.coopfed.knoweb.kernel.api;

import java.util.Map;

/**
 * The write side of the configuration register for seed loaders: a module's seed files hand
 * their configuration defaults to the kernel through it when the application starts (21A
 * section 3.3; 19A section 11 owns the register itself).
 *
 * <p>Added by M1-02. It is the only way a module writes configuration, and only for defaults:
 * a value an entity has set is never touched by a seed. 19A K-11 replaces the stub behind it
 * with the persisted register and keeps this interface.
 */
public interface ConfigSeeder {

    /** Registers default values by configuration key; a key already set keeps its value. */
    void addDefaults(Map<String, String> items);
}

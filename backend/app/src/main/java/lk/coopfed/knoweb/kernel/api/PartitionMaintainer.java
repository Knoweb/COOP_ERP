package lk.coopfed.knoweb.kernel.api;

/**
 * A module's contribution to the kernel's daily partition-maintenance job (19A section 12).
 * The kernel keeps the partitions of its own tables ahead of the calendar; a module with a
 * partitioned table of its own (M2's {@code catalogue.batch}) registers a bean of this type
 * and the job calls it on every run, after the kernel's own tables. An implementation is
 * idempotent: it creates what is missing and touches nothing else.
 */
public interface PartitionMaintainer {

    /** The partitioned table this keeps ahead, schema-qualified, for the log: {@code catalogue.batch}. */
    String table();

    /** Creates the partitions that are missing and returns how many it created. */
    int ensurePartitions();
}

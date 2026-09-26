package lk.coopfed.knoweb.m2catalogue.api;

import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * RegisterBatch as other modules call it (22A section 4: "RegisterBatch (internal command,
 * exported for M4/M5)"). M4 calls it for each line when a GRN is confirmed, M5 for the output of
 * a repack, inside their own transaction and in the receiving entity's OWN scope; the batch's
 * {@code owner_entity_id} is that entity, the registering entity (AGENTS.md idea 2: ownership
 * transfers at the GRN). It carries no permission of its own: the caller's command was checked
 * (doc 22 section 4.1: "internal (caller module)").
 */
public interface BatchRegistration {

    RegisteredBatch register(RegisterBatch command, ScopeContext scope);
}

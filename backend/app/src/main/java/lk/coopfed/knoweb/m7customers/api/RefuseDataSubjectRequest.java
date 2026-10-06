package lk.coopfed.knoweb.m7customers.api;

import java.util.UUID;

/** RefuseRequest (27A section 6): the responsible officer refuses a request on a legal ground. */
public record RefuseDataSubjectRequest(UUID requestId, String ground) {}

// The calls of the party module (M1 society register), through the shell's API client and
// typed by the client generated from openapi/m1party.yaml. Never write a request or response
// type by hand: change the slice, run `make gen-clients`, and the compiler shows every place
// that has to follow.
//
// The token, the language, the scope and error handling are the shell's business
// (shell/api/client.ts). What is left for a module is what only it knows: which operations
// it has.

import { useMemo } from "react";
import { useApiClient } from "../../shell/api/client";
import type { components, paths } from "../../generated/m1party";

export type Society = components["schemas"]["EntityResponse"];
export type SocietyPage = components["schemas"]["EntityPage"];
export type SocietyStatus = NonNullable<Society["status"]>;
export type RegisterSocietyRequest = components["schemas"]["RegisterEntityRequest"];
export type ReasonRequest = components["schemas"]["EntityReasonRequest"];
export type BulkValidationReport = components["schemas"]["BulkValidationReport"];
export type Relationship = components["schemas"]["RelationshipResponse"];
export type AmendTermsRequest = components["schemas"]["AmendTermsRequest"];

export type SocietyFilter = {
  status?: SocietyStatus;
  district?: string;
  /** The text of the search box: a prefix of the code or a part of a name; the server filters. */
  query?: string;
};

/** The server's page size for the register list: enough that a district fits on one page. */
const PAGE_SIZE = 100;

export function usePartyApi() {
  const api = useApiClient<paths>();

  return useMemo(
    () => ({
      async listSocieties(filter: SocietyFilter, cursor?: string): Promise<SocietyPage> {
        const { data } = await api.GET("/v1/party/entities", {
          params: {
            query: {
              status: filter.status,
              district: filter.district || undefined,
              q: filter.query || undefined,
              cursor,
              limit: PAGE_SIZE
            }
          }
        });
        return data ?? { items: [], nextCursor: null };
      },

      async getSociety(entityId: string): Promise<Society> {
        const { data } = await api.GET("/v1/party/entities/{entityId}", { params: { path: { entityId } } });
        return data!;
      },

      /** `idempotencyKey` comes from useIdempotencyKey(): one key per user action, reused on a retry. */
      async registerSociety(request: RegisterSocietyRequest, idempotencyKey: string): Promise<Society> {
        const { data } = await api.POST("/v1/party/entities", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body: request
        });
        return data!;
      },

      async activateSociety(entityId: string, idempotencyKey: string): Promise<void> {
        await api.POST("/v1/party/entities/{entityId}/activate", {
          params: { header: { "Idempotency-Key": idempotencyKey }, path: { entityId } }
        });
      },

      async suspendSociety(entityId: string, reason: ReasonRequest, idempotencyKey: string): Promise<void> {
        await api.POST("/v1/party/entities/{entityId}/suspend", {
          params: { header: { "Idempotency-Key": idempotencyKey }, path: { entityId } },
          body: reason
        });
      },

      async reinstateSociety(entityId: string, reason: ReasonRequest, idempotencyKey: string): Promise<void> {
        await api.POST("/v1/party/entities/{entityId}/reinstate", {
          params: { header: { "Idempotency-Key": idempotencyKey }, path: { entityId } },
          body: reason
        });
      },

      /**
       * The CSV goes up as one multipart part named "file", which is what the slice says. The
       * generated type calls a binary part a string; the serializer below is what really goes
       * on the wire, and the browser sets the multipart boundary itself.
       */
      async bulkRegister(file: File, idempotencyKey: string): Promise<BulkValidationReport> {
        const { data } = await api.POST("/v1/party/bulk-register", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body: { file } as unknown as { file: string },
          bodySerializer: () => {
            const form = new FormData();
            form.append("file", file, file.name);
            return form;
          }
        });
        return data!;
      },

      /** Every row of the caller's relationships as seller (or buyer): a pair's history is its rows. */
      async listRelationships(side: "SELLER" | "BUYER"): Promise<Relationship[]> {
        const { data } = await api.GET("/v1/party/relationships", { params: { query: { side } } });
        return data ?? [];
      },

      async getRelationship(relationshipId: string): Promise<Relationship> {
        const { data } = await api.GET("/v1/party/relationships/{relationshipId}", {
          params: { path: { relationshipId } }
        });
        return data!;
      },

      /**
       * AmendRelationshipTerms (21A section 6.1): the answer is the new row. A credit-limit change
       * also needs bil.creditlimit.change and a fresh second factor; the shell's client takes the
       * user through the step-up on mfa.required and replays the command.
       */
      async amendRelationship(relationshipId: string, body: AmendTermsRequest, idempotencyKey: string): Promise<Relationship> {
        const { data } = await api.POST("/v1/party/relationships/{relationshipId}/amend", {
          params: { header: { "Idempotency-Key": idempotencyKey }, path: { relationshipId } },
          body
        });
        return data!;
      }
    }),
    [api]
  );
}

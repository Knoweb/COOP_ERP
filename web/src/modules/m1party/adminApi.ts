// The calls of the administration screens of M1 (users, role assignments, roles, external
// grants: 21A M1-07, M1-08, M1-09), through the shell's API client and typed by the client
// generated from openapi/m1party.yaml. The society register's calls are in partyApi.ts.

import { useMemo } from "react";
import { useApiClient } from "../../shell/api/client";
import type { components, paths } from "../../generated/m1party";

type Schemas = components["schemas"];

export type User = Schemas["UserResponse"];
export type UserStatus = User["status"];
export type UserKind = User["userKind"];
export type CreateUserRequest = Schemas["CreateUserRequest"];
export type CredentialKind = Schemas["ResetCredentialRequest"]["credential"];
export type CredentialReset = Schemas["CredentialResetResponse"];
export type UserReason = Schemas["UserReasonRequest"];
export type Role = Schemas["RoleResponse"];
export type Assignment = Schemas["AssignmentResponse"];
export type AssignRoleRequest = Schemas["AssignRoleRequest"];
export type RevokeRoleRequest = Schemas["RevokeRoleRequest"];
export type Location = Schemas["LocationResponse"];
export type ExternalGrant = Schemas["ExternalGrantResponse"];
export type GrantExternalViewRequest = Schemas["GrantExternalViewRequest"];

/** The largest page the users list serves (openapi: limit maximum 100). */
const USER_PAGE = 100;

export function useAdminApi() {
  const api = useApiClient<paths>();

  return useMemo(
    () => ({
      async listUsers(filter: { status?: UserStatus; userKind?: UserKind }, cursor?: string) {
        const { data } = await api.GET("/v1/security/users", {
          params: { query: { status: filter.status, userKind: filter.userKind, cursor, limit: USER_PAGE } }
        });
        return data ?? { items: [], nextCursor: null };
      },

      async getUser(userId: string): Promise<User> {
        const { data } = await api.GET("/v1/security/users/{userId}", { params: { path: { userId } } });
        return data!;
      },

      async createUser(request: CreateUserRequest, idempotencyKey: string): Promise<User> {
        const { data } = await api.POST("/v1/security/users", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body: request
        });
        return data!;
      },

      async resetCredential(userId: string, credential: CredentialKind, idempotencyKey: string): Promise<CredentialReset> {
        const { data } = await api.POST("/v1/security/users/{userId}/reset-credential", {
          params: { header: { "Idempotency-Key": idempotencyKey }, path: { userId } },
          body: { credential }
        });
        return data!;
      },

      async deactivateUser(userId: string, reason: UserReason, idempotencyKey: string): Promise<void> {
        await api.POST("/v1/security/users/{userId}/deactivate", {
          params: { header: { "Idempotency-Key": idempotencyKey }, path: { userId } },
          body: reason
        });
      },

      async listRoles(): Promise<Role[]> {
        const { data } = await api.GET("/v1/security/roles");
        return data?.items ?? [];
      },

      async listAssignments(userId?: string): Promise<Assignment[]> {
        const { data } = await api.GET("/v1/security/assignments", { params: { query: { userId } } });
        return data?.items ?? [];
      },

      async assignRole(request: AssignRoleRequest, idempotencyKey: string): Promise<void> {
        await api.POST("/v1/security/assignments", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body: request
        });
      },

      async revokeRole(request: RevokeRoleRequest, idempotencyKey: string): Promise<void> {
        await api.POST("/v1/security/assignments/revoke", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body: request
        });
      },

      async listLocations(): Promise<Location[]> {
        const { data } = await api.GET("/v1/party/locations", { params: { query: { limit: 100 } } });
        return data?.items ?? [];
      },

      async listGrants(): Promise<ExternalGrant[]> {
        const { data } = await api.GET("/v1/security/external-grants");
        return data?.items ?? [];
      },

      async grantExternalView(request: GrantExternalViewRequest, idempotencyKey: string): Promise<ExternalGrant> {
        const { data } = await api.POST("/v1/security/external-grants", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body: request
        });
        return data!;
      },

      async revokeExternalView(grantId: string, reason: string, idempotencyKey: string): Promise<void> {
        await api.POST("/v1/security/external-grants/{grantId}/revoke", {
          params: { header: { "Idempotency-Key": idempotencyKey }, path: { grantId } },
          body: { reason }
        });
      }
    }),
    [api]
  );
}

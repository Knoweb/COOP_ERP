// The calls of the device enrolment, quarantine and sync-status screens (ADM-01),
// through the shell's API client and typed by the clients generated from
// openapi/m1party.yaml and openapi/sync.yaml.

import { useMemo } from "react";
import { useApiClient } from "../../shell/api/client";
import type { components as PartyComponents, paths as PartyPaths } from "../../generated/m1party";
import type { components as SyncComponents, paths as SyncPaths } from "../../generated/sync";

export type Device = PartyComponents["schemas"]["DeviceResponse"];
export type DeviceStatus = NonNullable<Device["status"]>;
export type DeviceKind = Device["deviceKind"];

export type EnrolmentCode = SyncComponents["schemas"]["EnrolmentCode"];
export type DeviceSyncStatus = SyncComponents["schemas"]["DeviceSyncStatus"];
export type SequenceGap = SyncComponents["schemas"]["SequenceGap"];
export type SequenceResetRequest = SyncComponents["schemas"]["SequenceResetRequest"];
export type QuarantinedEvent = SyncComponents["schemas"]["QuarantinedEvent"];
export type QuarantineResolution = SyncComponents["schemas"]["QuarantineResolution"];
export type ResolvedQuarantine = SyncComponents["schemas"]["ResolvedQuarantine"];

export function useDeviceApi() {
  const partyApi = useApiClient<PartyPaths>();
  const syncApi = useApiClient<SyncPaths>();

  return useMemo(
    () => ({
      async listDevices(filter?: { locationId?: string; status?: DeviceStatus }): Promise<Device[]> {
        const { data } = await partyApi.GET("/v1/party/devices", {
          params: { query: { locationId: filter?.locationId, status: filter?.status } }
        });
        return data ?? [];
      },

      async getDevice(deviceId: string): Promise<Device> {
        const { data } = await partyApi.GET("/v1/party/devices/{deviceId}", {
          params: { path: { deviceId } }
        });
        return data!;
      },

      async issueEnrolmentCode(deviceId: string, idempotencyKey: string): Promise<EnrolmentCode> {
        const { data } = await syncApi.POST("/v1/sync/devices/{device_id}/enrolment-codes", {
          params: {
            path: { device_id: deviceId },
            header: { "Idempotency-Key": idempotencyKey }
          }
        });
        return data!;
      },

      async getDeviceSyncStatus(deviceId: string): Promise<DeviceSyncStatus> {
        const { data } = await syncApi.GET("/v1/sync/devices/{device_id}/sync-status", {
          params: { path: { device_id: deviceId } }
        });
        return data!;
      },

      async resetDeviceSequence(
        deviceId: string,
        request: SequenceResetRequest,
        idempotencyKey: string
      ): Promise<SequenceGap> {
        const { data } = await syncApi.POST("/v1/sync/devices/{device_id}/sequence-reset", {
          params: {
            path: { device_id: deviceId },
            header: { "Idempotency-Key": idempotencyKey }
          },
          body: request
        });
        return data!;
      },

      async listQuarantine(filter?: {
        device_id?: string;
        resolved?: boolean;
        limit?: number;
        offset?: number;
      }): Promise<QuarantinedEvent[]> {
        const { data } = await syncApi.GET("/v1/sync/quarantine", {
          params: {
            query: {
              device_id: filter?.device_id,
              resolved: filter?.resolved,
              limit: filter?.limit,
              offset: filter?.offset
            }
          }
        });
        return data ?? [];
      },

      async getQuarantine(quarantineId: string): Promise<QuarantinedEvent> {
        const { data } = await syncApi.GET("/v1/sync/quarantine/{quarantine_id}", {
          params: { path: { quarantine_id: quarantineId } }
        });
        return data!;
      },

      async resolveQuarantine(
        quarantineId: string,
        resolution: QuarantineResolution,
        idempotencyKey: string
      ): Promise<ResolvedQuarantine> {
        const { data } = await syncApi.POST("/v1/sync/quarantine/{quarantine_id}/resolve", {
          params: {
            path: { quarantine_id: quarantineId },
            header: { "Idempotency-Key": idempotencyKey }
          },
          body: resolution
        });
        return data!;
      }
    }),
    [partyApi, syncApi]
  );
}

// The calls of the hello module, through the shell's API client and typed by the client
// generated from openapi/hello.yaml. Never write a request or response type by hand: change
// the slice, run `make gen-clients`, and the compiler shows every place that has to follow.
//
// The token, the language, the scope and error handling are the shell's business
// (shell/api/client.ts). What is left for a module is what only it knows: which operations
// it has.

import { useMemo } from "react";
import { useApiClient } from "../../shell/api/client";
import type { components, paths } from "../../generated/hello";

export type Greeting = components["schemas"]["GreetingResponse"];
export type RegisterGreetingRequest = components["schemas"]["RegisterGreetingRequest"];

export function useHelloApi() {
  const api = useApiClient<paths>();

  return useMemo(
    () => ({
      async listGreetings(): Promise<Greeting[]> {
        const { data } = await api.GET("/v1/hello/greetings");
        return data ?? [];
      },

      /** `idempotencyKey` comes from useIdempotencyKey(): one key per user action, reused on a retry. */
      async registerGreeting(request: RegisterGreetingRequest, idempotencyKey: string): Promise<Greeting> {
        const { data } = await api.POST("/v1/hello/greetings", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body: request
        });
        return data!;
      }
    }),
    [api]
  );
}

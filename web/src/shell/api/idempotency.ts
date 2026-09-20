// The Idempotency-Key of a user action (AGENTS.md: every mutating operation carries one).
//
// One key belongs to one action of the user: "register this greeting". When the same action is
// sent again, because the network failed or the user pressed the button twice, it carries the
// same key, and the server returns the first result instead of doing the work twice. A new
// action needs a new key. So the key cannot be made inside the API client, which sees
// requests and not actions; the screen holds it:
//
//   const key = useIdempotencyKey();
//   const save = useMutation({
//     mutationFn: () => api.POST("/v1/...", { params: { header: { "Idempotency-Key": key.current() } }, body }),
//     onSuccess: key.next,                                   // done: the next action is a new one
//     onError: (e) => e instanceof ApiProblem && key.next()  // the server answered: finished too
//   });
//
// Not after a network error: nobody knows then whether the server did the work, and the retry
// must carry the same key to find out.

import { useMemo, useRef } from "react";

export function useIdempotencyKey(): { current: () => string; next: () => void } {
  const key = useRef<string>(crypto.randomUUID());

  return useMemo(
    () => ({
      current: () => key.current,
      next: () => {
        key.current = crypto.randomUUID();
      }
    }),
    []
  );
}

import { renderHook } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { useIdempotencyKey } from "./idempotency";

describe("the idempotency key of a user action", () => {
  it("stays the same across renders and retries, and changes only when the action is over", () => {
    const { result, rerender } = renderHook(() => useIdempotencyKey());
    const first = result.current.current();

    rerender();
    expect(result.current.current()).toBe(first);      // a retry carries the same key

    result.current.next();
    expect(result.current.current()).not.toBe(first);  // the next action is a new one
    expect(result.current.current()).toMatch(/^[0-9a-f-]{36}$/);
  });
});

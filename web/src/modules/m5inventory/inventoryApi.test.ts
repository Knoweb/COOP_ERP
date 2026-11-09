import { describe, expect, it, vi } from "vitest";
import { renderHook } from "@testing-library/react";
import { useInventoryApi } from "./inventoryApi";

const mockGet = vi.fn();
vi.mock("../../shell/api/client", () => ({
  useApiClient: () => ({
    GET: mockGet,
    POST: vi.fn(),
    PUT: vi.fn(),
    DELETE: vi.fn()
  })
}));

describe("inventoryApi", () => {
  it("downloadOpeningStockTemplate returns parsed Blob without consuming response twice", async () => {
    const mockBlob = new Blob(["excel-content"], {
      type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    });
    mockGet.mockResolvedValueOnce({
      data: mockBlob,
      response: new Response("excel-content")
    });

    const { result } = renderHook(() => useInventoryApi());
    const blob = await result.current.downloadOpeningStockTemplate();

    expect(mockGet).toHaveBeenCalledWith("/v1/inventory/opening-balances/template", {
      parseAs: "blob"
    });
    expect(blob).toBe(mockBlob);
    expect(blob.type).toBe("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
  });

  it("downloadOpeningStockTemplate creates Blob with correct Excel MIME type if data is not a Blob", async () => {
    mockGet.mockResolvedValueOnce({
      data: "binary-string-content",
      response: new Response("binary-string-content")
    });

    const { result } = renderHook(() => useInventoryApi());
    const blob = await result.current.downloadOpeningStockTemplate();

    expect(blob).toBeInstanceOf(Blob);
    expect(blob.type).toBe("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    expect(await blob.text()).toBe("binary-string-content");
  });
});

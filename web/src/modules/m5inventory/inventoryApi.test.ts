import { describe, expect, it, vi } from "vitest";
import { renderHook } from "@testing-library/react";
import { useInventoryApi } from "./inventoryApi";

const mockGet = vi.fn();
const mockPost = vi.fn();
vi.mock("../../shell/api/client", () => ({
  useApiClient: () => ({
    GET: mockGet,
    POST: mockPost,
    PUT: vi.fn(),
    DELETE: vi.fn()
  })
}));

describe("inventoryApi", () => {
  it("sends an explicit JSON body for a full transfer receipt", async () => {
    const request = {
      lines: [{ transferLineId: "0190f000-0000-7000-8000-0000000000a1", receivedQty: 20 }]
    };
    mockPost.mockResolvedValueOnce({ data: { transferId: "transfer-1" } });

    const { result } = renderHook(() => useInventoryApi());
    await result.current.receiveTransfer("transfer-1", "receipt-key-1", request);

    expect(mockPost).toHaveBeenCalledWith("/v1/inventory/transfers/{transferId}/receive", {
      params: {
        path: { transferId: "transfer-1" },
        header: { "Idempotency-Key": "receipt-key-1" }
      },
      body: request
    });
  });

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

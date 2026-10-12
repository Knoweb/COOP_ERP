import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import { Link, useParams } from "react-router-dom";
import { useIntl } from "react-intl";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatDate } from "../../shell/i18n/formats";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { StateChip } from "../../shell/components/StateChip";
import "./catalogue.css";
import { useCatalogueApi, type Symbology } from "./catalogueApi";
import { SkuFields } from "./SkuFields";
import { chipOf, EMPTY_FORM, errorText, formOf, keyForUpload, languageOf, nameIn, requestOf, type SkuForm } from "./skuView";
import type { AttachImageRequest } from "./catalogueApi";

const SYMBOLOGIES: Symbology[] = ["EAN13", "EAN8", "UPCA", "GS1_128", "GS1_DATAMATRIX", "GS1_QR", "INTERNAL"];

const forgetKeyOnProblem = (key: { next: () => void }) => (error: unknown) => {
  if (error instanceof ApiProblem) {
    key.next();
  }
};

/**
 * One item (doc 30 section 5.2; 22A section 8, "SKU editor", demo scope): its details, editable
 * while it is a DRAFT or LOCAL; for a draft, the two ways to put it in use (LOCAL for the
 * caller's own shelves, SHARED for every society, the Federation's path); its units and
 * conversions, its barcodes and its batches. The server decides every rule; a refusal shows its
 * own words.
 */
export function SkuPage() {
  const { skuId = "" } = useParams();
  const t = useT();
  const intl = useIntl();
  const api = useCatalogueApi();
  const queryClient = useQueryClient();
  const language = languageOf(intl.locale);
  const canEditLocal = useHasPermission("cat.sku.create_local");
  const canShare = useHasPermission("cat.sku.create");
  const canBarcode = useHasPermission("cat.barcode.manage");
  const canImage = useHasPermission("cat.image.manage");
  const saveKey = useIdempotencyKey();
  const activateKey = useIdempotencyKey();

  const sku = useQuery({ queryKey: ["catalogue", "sku", skuId], queryFn: () => api.getSku(skuId) });
  const [form, setForm] = useState<SkuForm>(EMPTY_FORM);
  useEffect(() => {
    if (sku.data) {
      setForm(formOf(sku.data));
    }
  }, [sku.data]);

  const refresh = () => queryClient.invalidateQueries({ queryKey: ["catalogue"] });

  const save = useMutation({
    mutationFn: () => api.updateSku(skuId, requestOf(form, sku.data), saveKey.current()),
    onSuccess: () => {
      saveKey.next();
      refresh();
    },
    onError: forgetKeyOnProblem(saveKey)
  });

  const activate = useMutation({
    mutationFn: (target: "LOCAL" | "SHARED") => api.activate(skuId, target, activateKey.current()),
    onSuccess: () => {
      activateKey.next();
      refresh();
    },
    onError: forgetKeyOnProblem(activateKey)
  });

  if (sku.isLoading) {
    return <main className="shell-page">{t("catalogue.loading").text}</main>;
  }
  if (sku.isError || !sku.data) {
    return (
      <main className="shell-page">
        <p role="alert">{errorText(sku.error, t("catalogue.error.not_found").text)}</p>
        <Link className="back-link" to="/catalogue">
        <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
        {t("catalogue.back").text}
      </Link>
      </main>
    );
  }

  const item = sku.data;
  const editable = canEditLocal && (item.status === "DRAFT" || item.status === "LOCAL");
  const submit = (event: FormEvent) => {
    event.preventDefault();
    save.mutate();
  };

  return (
    <main className="shell-page">
      <Link className="back-link" to="/catalogue">
        <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
        {t("catalogue.back").text}
      </Link>
      <h1>{nameIn(item, language)}</h1>
      <p className="catalogue-header-row">
        <span>{item.skuCode}</span>
        <StateChip state={chipOf(item.status)} label={t(`catalogue.status.${item.status}`).text} />
      </p>

      <form onSubmit={submit} className="catalogue-form-row">
        <SkuFields form={form} disabled={!editable} onChange={(change) => setForm((current) => ({ ...current, ...change }))} />
        {editable && (
          <div>
            <button type="submit" disabled={save.isPending || !form.nameEn.trim()}>
              {t("catalogue.save").text}
            </button>
            {save.isSuccess && <span role="status">{t("catalogue.saved").text}</span>}
          </div>
        )}
        {save.isError && <p role="alert">{errorText(save.error, t("catalogue.error.generic").text)}</p>}
      </form>

      {item.status === "DRAFT" && (canEditLocal || canShare) && (
        <section className="catalogue-action-bar">
          {canEditLocal && (
            <button type="button" disabled={activate.isPending} onClick={() => activate.mutate("LOCAL")}>
              {t("catalogue.activate.local").text}
            </button>
          )}
          {canShare && (
            <button type="button" disabled={activate.isPending} onClick={() => activate.mutate("SHARED")}>
              {t("catalogue.activate.shared").text}
            </button>
          )}
          {activate.isError && <p role="alert">{errorText(activate.error, t("catalogue.error.generic").text)}</p>}
        </section>
      )}

      <Conversions skuId={skuId} canEdit={canEditLocal && item.status !== "INACTIVE"} baseUom={item.baseUomCode} />
      <Barcodes skuId={skuId} canEdit={canBarcode && (item.status === "LOCAL" || item.status === "SHARED")} baseUom={item.baseUomCode} />
      <Images skuId={skuId} canEdit={canImage && (item.status === "LOCAL" || item.status === "SHARED")} />
      <Batches skuId={skuId} />
    </main>
  );
}

function Conversions({ skuId, canEdit, baseUom }: { skuId: string; canEdit: boolean; baseUom: string }) {
  const t = useT();
  const formatDate = useFormatDate();
  const api = useCatalogueApi();
  const queryClient = useQueryClient();
  const key = useIdempotencyKey();
  const [uomCode, setUomCode] = useState("");
  const [factor, setFactor] = useState("");
  const [from, setFrom] = useState("");
  const conversions = useQuery({ queryKey: ["catalogue", "conversions", skuId], queryFn: () => api.conversions(skuId) });
  const reference = useQuery({ queryKey: ["catalogue", "reference"], queryFn: () => api.reference(), staleTime: Infinity });

  const define = useMutation({
    mutationFn: () => api.defineConversion(skuId, { uomCode, factorToBase: Number(factor), effectiveFrom: from }, key.current()),
    onSuccess: () => {
      key.next();
      setFactor("");
      queryClient.invalidateQueries({ queryKey: ["catalogue", "conversions", skuId] });
    },
    onError: forgetKeyOnProblem(key)
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    define.mutate();
  };

  return (
    <section className="catalogue-section">
      <h2>{t("catalogue.conversions.title").text}</h2>
      {conversions.data?.length === 0 && <p>{t("catalogue.conversions.empty").text}</p>}
      {conversions.data && conversions.data.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>{t("catalogue.column.unit").text}</th>
              <th>{t("catalogue.column.factor").text}</th>
              <th>{t("catalogue.column.from").text}</th>
              <th>{t("catalogue.column.to").text}</th>
            </tr>
          </thead>
          <tbody>
            {conversions.data.map((row) => (
              <tr key={`${row.uomCode}-${row.effectiveFrom}`}>
                <td>{row.uomCode}</td>
                <td>{t("catalogue.conversion.factor", undefined, { factor: row.factorToBase, base: baseUom }).text}</td>
                <td>{formatDate(row.effectiveFrom)}</td>
                <td>{row.effectiveTo ? formatDate(row.effectiveTo) : ""}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {canEdit && (
        <form onSubmit={submit} className="catalogue-filter-bar">
          <label className="catalogue-form-field">
            {t("catalogue.field.unit").text}
            <select required value={uomCode} onChange={(event) => setUomCode(event.target.value)}>
              <option value="">{t("catalogue.field.choose").text}</option>
              {(reference.data?.units ?? [])
                .filter((unit) => unit.uomCode !== baseUom)
                .map((unit) => (
                  <option key={unit.uomCode} value={unit.uomCode}>
                    {unit.uomCode}
                  </option>
                ))}
            </select>
          </label>
          <label className="catalogue-form-field">
            {t("catalogue.field.factor").text}
            <input type="number" min="0" step="any" required value={factor} onChange={(event) => setFactor(event.target.value)} />
          </label>
          <label className="catalogue-form-field">
            {t("catalogue.field.effective_from").text}
            <input type="date" required value={from} onChange={(event) => setFrom(event.target.value)} />
          </label>
          <button type="submit" disabled={define.isPending || !uomCode || !factor || !from}>
            {t("catalogue.conversions.add").text}
          </button>
          {define.isError && <p role="alert">{errorText(define.error, t("catalogue.error.generic").text)}</p>}
        </form>
      )}
    </section>
  );
}

function Barcodes({ skuId, canEdit, baseUom }: { skuId: string; canEdit: boolean; baseUom: string }) {
  const t = useT();
  const api = useCatalogueApi();
  const queryClient = useQueryClient();
  const key = useIdempotencyKey();
  const [barcode, setBarcode] = useState("");
  const [symbology, setSymbology] = useState<Symbology>("EAN13");
  const [uomCode, setUomCode] = useState(baseUom);
  const barcodes = useQuery({ queryKey: ["catalogue", "barcodes", skuId], queryFn: () => api.barcodes(skuId) });
  const conversions = useQuery({ queryKey: ["catalogue", "conversions", skuId], queryFn: () => api.conversions(skuId) });
  const units = [baseUom, ...new Set((conversions.data ?? []).map((row) => row.uomCode))];

  const register = useMutation({
    mutationFn: () => api.registerBarcode(skuId, { barcode: barcode.trim(), symbology, uomCode }, key.current()),
    onSuccess: () => {
      key.next();
      setBarcode("");
      queryClient.invalidateQueries({ queryKey: ["catalogue", "barcodes", skuId] });
    },
    onError: forgetKeyOnProblem(key)
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    register.mutate();
  };

  return (
    <section className="catalogue-section">
      <h2>{t("catalogue.barcodes.title").text}</h2>
      {barcodes.data?.length === 0 && <p>{t("catalogue.barcodes.empty").text}</p>}
      {barcodes.data && barcodes.data.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>{t("catalogue.column.barcode").text}</th>
              <th>{t("catalogue.column.symbology").text}</th>
              <th>{t("catalogue.column.unit").text}</th>
              <th>{t("catalogue.column.status").text}</th>
            </tr>
          </thead>
          <tbody>
            {barcodes.data.map((row) => (
              <tr key={`${row.barcode}-${row.symbology}`}>
                <td>{row.barcode}</td>
                <td>{row.symbology}</td>
                <td>{row.uomCode}</td>
                <td>{t(`catalogue.barcode.status.${row.status}`).text}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {canEdit && (
        <form onSubmit={submit} className="catalogue-filter-bar">
          <label className="catalogue-form-field">
            {t("catalogue.field.barcode").text}
            <input type="text" required maxLength={48} value={barcode} onChange={(event) => setBarcode(event.target.value)} />
          </label>
          <label className="catalogue-form-field">
            {t("catalogue.field.symbology").text}
            <select value={symbology} onChange={(event) => setSymbology(event.target.value as Symbology)}>
              {SYMBOLOGIES.map((value) => (
                <option key={value} value={value}>
                  {value}
                </option>
              ))}
            </select>
          </label>
          <label className="catalogue-form-field">
            {t("catalogue.field.barcode_unit").text}
            <select value={uomCode} onChange={(event) => setUomCode(event.target.value)}>
              {units.map((unit) => (
                <option key={unit} value={unit}>
                  {unit}
                </option>
              ))}
            </select>
          </label>
          <button type="submit" disabled={register.isPending || !barcode.trim()}>
            {t("catalogue.barcodes.add").text}
          </button>
          {register.isError && <p role="alert">{errorText(register.error, t("catalogue.error.generic").text)}</p>}
        </form>
      )}
    </section>
  );
}

function Batches({ skuId }: { skuId: string }) {
  const t = useT();
  const formatDate = useFormatDate();
  const api = useCatalogueApi();
  const batches = useQuery({ queryKey: ["catalogue", "batches", skuId], queryFn: () => api.batches(skuId) });

  return (
    <section className="catalogue-section">
      <h2>{t("catalogue.batches.title").text}</h2>
      {batches.data?.length === 0 && <p>{t("catalogue.batches.empty").text}</p>}
      {batches.data && batches.data.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>{t("catalogue.column.batch_no").text}</th>
              <th>{t("catalogue.column.expiry").text}</th>
              <th>{t("catalogue.column.mrp").text}</th>
              <th>{t("catalogue.column.status").text}</th>
            </tr>
          </thead>
          <tbody>
            {batches.data.map((batch) => (
              <tr key={batch.batchId}>
                <td>{batch.batchNo}</td>
                <td>{batch.expiryDate ? formatDate(batch.expiryDate) : ""}</td>
                <td>{batch.printedMrp != null && <MoneyDisplay amount={batch.printedMrp} />}</td>
                <td>{t(`catalogue.batch.status.${batch.status}`).text}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

function Images({ skuId, canEdit }: { skuId: string; canEdit: boolean }) {
  const t = useT();
  const api = useCatalogueApi();
  const queryClient = useQueryClient();
  const key = useIdempotencyKey();
  // What the key was last sent with to attachImage: a different file gets a new key (keyForUpload).
  const uploadSent = useRef<string | null>(null);

  const [uploadStatus, setUploadStatus] = useState<"IDLE" | "HASHING" | "GETTING_URL" | "UPLOADING" | "DONE" | "ERROR">("IDLE");
  const [errorMsg, setErrorMsg] = useState("");
  const [barcode, setBarcode] = useState("");
  
  // Initialize from sessionStorage if available to persist preview across refreshes
  const [localPreviewUrl, setLocalPreviewUrl] = useState<string | null>(() => {
    return sessionStorage.getItem(`sku_preview_${skuId}`);
  });
  
  const barcodes = useQuery({ queryKey: ["catalogue", "barcodes", skuId], queryFn: () => api.barcodes(skuId) });
  const images = useQuery({ queryKey: ["catalogue", "images", skuId], queryFn: () => api.images(skuId) });

  const retire = useMutation({
    mutationFn: (imageId: string) => api.retireImage(skuId, imageId, key.current()),
    onSuccess: () => {
      key.next();
      queryClient.invalidateQueries({ queryKey: ["catalogue", "images", skuId] });
      setLocalPreviewUrl(null);
      sessionStorage.removeItem(`sku_preview_${skuId}`);
    },
    onError: (err) => {
      // The failure is shown inline under the image (role="alert"), not in a browser dialog.
      forgetKeyOnProblem(key)(err);
    }
  });

  const handleFileChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const selectedFile = e.target.files?.[0];
    if (!selectedFile) return;
    
    // Set immediate local preview and save to sessionStorage as data URL
    const reader = new FileReader();
    reader.onload = (event) => {
      const dataUrl = event.target?.result as string;
      setLocalPreviewUrl(dataUrl);
      try {
        sessionStorage.setItem(`sku_preview_${skuId}`, dataUrl);
      } catch {
        // Ignore quota exceeded errors
      }
    };
    reader.readAsDataURL(selectedFile);
    
    try {
      setUploadStatus("HASHING");
      setErrorMsg("");
      const buffer = await selectedFile.arrayBuffer();
      const hashBuffer = await crypto.subtle.digest("SHA-256", buffer);
      const hashArray = Array.from(new Uint8Array(hashBuffer));
      const sha256Hex = hashArray.map((b) => b.toString(16).padStart(2, "0")).join("");

      setUploadStatus("GETTING_URL");
      const req: AttachImageRequest = {
        contentType: selectedFile.type,
        contentLength: selectedFile.size,
        sha256Hex
      };
      
      if (barcode) {
        req.barcode = barcode;
      }
      
      const response = await api.attachImage(skuId, req, keyForUpload(key, uploadSent, `${sha256Hex}|${barcode}`));

      setUploadStatus("UPLOADING");
      const putResp = await fetch(response.uploadUrl, {
        method: "PUT",
        headers: {
          "Content-Type": selectedFile.type,
        },
        body: selectedFile
      });
      if (!putResp.ok) {
        throw new Error("Upload to storage failed");
      }

      setUploadStatus("DONE");
      key.next();
      queryClient.invalidateQueries({ queryKey: ["catalogue", "images", skuId] });
      
      setTimeout(() => {
        setUploadStatus("IDLE");
      }, 3000);
    } catch (err) {
      // The server answered: that action is finished, the next file is a new one. A failed
      // storage PUT is not an answer; its retry carries the same key and renews the PENDING row.
      forgetKeyOnProblem(key)(err);
      setUploadStatus("ERROR");
      setLocalPreviewUrl(null); // Clear preview on error
      sessionStorage.removeItem(`sku_preview_${skuId}`);
      setErrorMsg(err instanceof ApiProblem ? errorText(err, t("catalogue.error.generic").text) : t("catalogue.error.generic").text);
    } finally {
      e.target.value = "";
    }
  };

  const activeImage = images.data?.find(img => img.status === "ACTIVE" || img.status === "PENDING");
  
  // Clear sessionStorage if the image is actually active from the backend
  if (activeImage?.imageUrl || activeImage?.thumbUrl) {
    if (localPreviewUrl) {
      setLocalPreviewUrl(null);
      sessionStorage.removeItem(`sku_preview_${skuId}`);
    }
  }
  
  // Decide which URL to show: either local preview (highest priority right after upload) or the backend URL
  const displayUrl = localPreviewUrl || activeImage?.imageUrl || activeImage?.thumbUrl;
  
  return (
    <section className="catalogue-section">
      <h2 className="catalogue-section-title">
        {t("catalogue.item_details").text}
      </h2>
      
      <div className="catalogue-image-layout">
        {/* Left Card: The Image itself */}
        <div className="catalogue-image-card">
        <h3 className="catalogue-image-title">
          {t("catalogue.images.card_title").text}
        </h3>
        
        <div className="catalogue-image-box">
          {activeImage || localPreviewUrl ? (
            displayUrl ? (
              <img 
                src={displayUrl} 
                alt={t("catalogue.images.alt").text} 
                className="catalogue-image-img" 
              />
            ) : (
              <div className="catalogue-image-processing">
                <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="catalogue-image-processing-icon">
                  <circle cx="12" cy="12" r="10"></circle>
                  <polyline points="12 6 12 12 16 14"></polyline>
                </svg>
                <div className="catalogue-image-processing-text">{t("catalogue.images.processing").text}</div>
                <div className="catalogue-image-processing-subtext">{t("catalogue.images.processing_wait").text}</div>
              </div>
            )
          ) : (
            <div className="catalogue-image-no-image">{t("catalogue.images.none").text}</div>
          )}
        </div>
        
        {canEdit && (
          <div className="catalogue-image-actions">
            <label className={`catalogue-image-btn catalogue-image-btn-primary ${(uploadStatus !== "IDLE" && uploadStatus !== "DONE" && uploadStatus !== "ERROR") ? "catalogue-image-btn-primary-disabled" : ""}`}>
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <rect x="3" y="3" width="18" height="18" rx="2" ry="2"/>
                <circle cx="8.5" cy="8.5" r="1.5"/>
                <polyline points="21 15 16 10 5 21"/>
              </svg>
              {(activeImage ? t("catalogue.images.change") : t("catalogue.images.upload")).text}
              <input 
                type="file" 
                accept="image/jpeg, image/png" 
                style={{ display: 'none' }} 
                onChange={handleFileChange}
                disabled={uploadStatus !== "IDLE" && uploadStatus !== "DONE" && uploadStatus !== "ERROR"}
              />
            </label>
            
            {activeImage && (
              <button 
                type="button"
                onClick={() => retire.mutate(activeImage.imageId)}
                disabled={retire.isPending}
                className={`catalogue-image-btn catalogue-image-btn-error`}
              >
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <polyline points="3 6 5 6 21 6"/>
                  <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/>
                  <line x1="10" y1="11" x2="10" y2="17"/>
                  <line x1="14" y1="11" x2="14" y2="17"/>
                </svg>
                {t("catalogue.images.remove").text}
              </button>
            )}
          </div>
        )}

        {uploadStatus !== "IDLE" && uploadStatus !== "DONE" && (
          <div className="catalogue-image-status">
            {uploadStatus === "HASHING" && t("catalogue.images.status.hashing").text}
            {uploadStatus === "GETTING_URL" && t("catalogue.images.status.getting_url").text}
            {uploadStatus === "UPLOADING" && t("catalogue.images.status.uploading").text}
            {uploadStatus === "ERROR" && <span className="catalogue-image-status-error">{errorMsg}</span>}
          </div>
        )}
        
        {retire.isError && <p role="alert">{errorText(retire.error, t("catalogue.images.remove_failed").text)}</p>}

        <div className="catalogue-image-formats">
          {t("catalogue.images.formats").text}
        </div>
      </div>
      
      {/* Right panel: Upload Settings (Barcode) */}
      {canEdit && (
        <div className="catalogue-image-settings-panel">
            <h3 className="catalogue-image-settings-title">
              {t("catalogue.images.settings_title").text}
            </h3>
            <p className="catalogue-image-settings-desc">
              {t("catalogue.images.settings_hint").text}
            </p>
            
            <label className="catalogue-form-field">
              <span className="catalogue-image-settings-label">
                {t("catalogue.field.barcode").text}
              </span>
              <select 
                value={barcode} 
                onChange={(e) => setBarcode(e.target.value)}
                className="catalogue-image-settings-select"
              >
                <option value="">{t("catalogue.field.choose").text}</option>
                {(barcodes.data ?? []).filter(b => b.status === "ACTIVE").map((b) => (
                  <option key={b.barcode} value={b.barcode}>{b.barcode}</option>
                ))}
              </select>
            </label>
        </div>
      )}
      
      </div>
    </section>
  );
}

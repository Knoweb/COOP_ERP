package lk.coopfed.knoweb.m2catalogue.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.ObjectStorage;
import lk.coopfed.knoweb.m2catalogue.api.AttachImage;
import lk.coopfed.knoweb.m2catalogue.api.ImageUpload;
import lk.coopfed.knoweb.m2catalogue.api.RetireImage;
import lk.coopfed.knoweb.m2catalogue.internal.image.AttachImageHandler;
import lk.coopfed.knoweb.m2catalogue.internal.image.RetireImageHandler;
import lk.coopfed.knoweb.m2catalogue.internal.image.Thumbnailer;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.ImageView;
import lk.coopfed.knoweb.m2catalogue.web.generated.AttachImageRequest;
import lk.coopfed.knoweb.m2catalogue.web.generated.ImageApi;
import lk.coopfed.knoweb.m2catalogue.web.generated.ImageResponse;
import lk.coopfed.knoweb.m2catalogue.web.generated.ImageUploadResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** AttachImage and RetireImage (22A section 4, ImageController; section 5). */
@RestController
class ImageController implements ImageApi {

    private final AttachImageHandler attach;
    private final RetireImageHandler retire;
    private final CurrentScope currentScope;
    private final CatalogueQueries queries;
    private final ObjectStorage storage;

    ImageController(
            AttachImageHandler attach,
            RetireImageHandler retire,
            CurrentScope currentScope,
            CatalogueQueries queries,
            ObjectStorage storage) {
        this.attach = attach;
        this.retire = retire;
        this.currentScope = currentScope;
        this.queries = queries;
        this.storage = storage;
    }

    @Override
    public ResponseEntity<ImageUploadResponse> attachImage(
            UUID skuId, String idempotencyKey, AttachImageRequest request) {

        ImageUpload upload = attach.handle(
                new AttachImage(
                        skuId,
                        request.getBarcode(),
                        request.getContentType(),
                        request.getContentLength(),
                        request.getSha256Hex()),
                currentScope.get());

        ImageUploadResponse body = new ImageUploadResponse(upload.imageId(), upload.uploadUrl(), upload.expiresAt());

        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/v1/catalogue/skus/" + skuId + "/images/" + upload.imageId()))
                .body(body);
    }

    @Override
    public ResponseEntity<Void> retireImage(UUID skuId, UUID imageId, String idempotencyKey) {

        retire.handle(new RetireImage(skuId, imageId), currentScope.get());

        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<List<ImageResponse>> listImages(UUID skuId) {
        List<ImageView> views = queries.images(skuId, currentScope.get());
        List<ImageResponse> responses = views.stream()
                .map(view -> {
                    ImageResponse r =
                            new ImageResponse(view.imageId(), ImageResponse.StatusEnum.valueOf(view.status()));
                    r.setBarcode(view.barcode());
                    if (("ACTIVE".equals(view.status()) || "RETIRED".equals(view.status()))
                            && view.objectKeyFull() != null) {
                        try {
                            // The type it was uploaded as, a PNG as image/png (wave 3, M1M2M3M5-14).
                            r.setImageUrl(
                                    storage.presignGet(view.objectKeyFull(), view.contentType(), currentScope.get())
                                            .toString());
                        } catch (lk.coopfed.knoweb.kernel.api.ProblemException e) {
                            // Ignore if the kernel attachment is missing, unverified, or out of scope
                        }
                    }
                    if (("ACTIVE".equals(view.status()) || "RETIRED".equals(view.status()))
                            && view.objectKeyThumb() != null) {
                        try {
                            // The thumbnail job writes JPEG (Thumbnailer), never WebP.
                            r.setThumbUrl(storage.presignGet(
                                            view.objectKeyThumb(), Thumbnailer.CONTENT_TYPE, currentScope.get())
                                    .toString());
                        } catch (lk.coopfed.knoweb.kernel.api.ProblemException e) {
                            // Ignore if the kernel attachment is missing, unverified, or out of scope
                        }
                    }
                    return r;
                })
                .toList();
        return ResponseEntity.ok(responses);
    }
}

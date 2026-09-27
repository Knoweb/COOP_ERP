package lk.coopfed.knoweb.m2catalogue.web;

import java.net.URI;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.m2catalogue.api.AttachImage;
import lk.coopfed.knoweb.m2catalogue.api.ImageUpload;
import lk.coopfed.knoweb.m2catalogue.api.RetireImage;
import lk.coopfed.knoweb.m2catalogue.internal.image.AttachImageHandler;
import lk.coopfed.knoweb.m2catalogue.internal.image.RetireImageHandler;
import lk.coopfed.knoweb.m2catalogue.web.generated.AttachImageRequest;
import lk.coopfed.knoweb.m2catalogue.web.generated.ImageApi;
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

    ImageController(AttachImageHandler attach, RetireImageHandler retire, CurrentScope currentScope) {
        this.attach = attach;
        this.retire = retire;
        this.currentScope = currentScope;
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
}

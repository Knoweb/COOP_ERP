package lk.coopfed.archfixtures.m1party;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Violates the OpenAPI-first rule: a REST controller with a hand-written mapping, which
 * implements no interface generated from a slice. Nothing describes this endpoint.
 */
@RestController
public class HandWrittenController {

    @GetMapping("/v1/party/secret-endpoint")
    public String undocumented() {
        return "nobody knows about me";
    }
}

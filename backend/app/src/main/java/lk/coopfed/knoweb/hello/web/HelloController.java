package lk.coopfed.knoweb.hello.web;

import lk.coopfed.knoweb.hello.api.GreetingQueries;
import lk.coopfed.knoweb.hello.api.RegisterGreeting;
import lk.coopfed.knoweb.hello.internal.RegisterGreetingHandler;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/hello/greetings")
@CrossOrigin(origins = "*")
public class HelloController {

    private final RegisterGreetingHandler handler;
    private final GreetingQueries queries;

    public HelloController(RegisterGreetingHandler handler, GreetingQueries queries) {
        this.handler = handler;
        this.queries = queries;
    }

    @PostMapping
    public Map<String, Object> registerGreeting(@RequestBody RegisterGreeting cmd, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        UUID id = handler.handle(cmd, null);
        return Map.of(
            "id", id.toString(),
            "textEn", cmd.textEn(),
            "status", "REGISTERED"
        );
    }

    @GetMapping
    public List<Map<String, Object>> getGreetings() {
        return List.of();
    }
}

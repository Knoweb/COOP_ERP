package lk.coopfed.knoweb.kernel.internal.stub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lk.coopfed.knoweb.kernel.api.Messages;
import lk.coopfed.knoweb.kernel.internal.i18n.IcuMessages;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every constraint a slice can express comes out as a message id, by each of the three routes
 * Spring reports a violation. No database and no application context: the hello integration
 * test proves the same thing end to end for a generated interface.
 */
class RequestValidationHandlerTest {

    private final Messages messages = new IcuMessages(new ObjectMapper());
    private final RequestValidationHandler handler =
            new RequestValidationHandler(new ProblemResponses(messages), messages);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new OrdersController())
            .setControllerAdvice(handler)
            .build();

    @Test
    void everyKindOfConstraintInABodyBecomesItsMessageId() throws Exception {
        String body =
                """
                { "quantity": 0, "discount": 101, "price": "0.00", "code": "abc", "tags": ["one"],
                  "note": "%s" }"""
                        .formatted("x".repeat(11));

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("request.invalid"))
                .andExpect(jsonPath("$.title").value("Some of the information sent is missing or not valid"))
                .andExpect(jsonPath("$.errors.length()").value(7))
                // sorted by field, so a client and a test see a stable order
                .andExpect(jsonPath("$.errors[0].field").value("code"))
                .andExpect(jsonPath("$.errors[0].code").value("request.field.format"))
                .andExpect(jsonPath("$.errors[1].field").value("customer"))
                .andExpect(jsonPath("$.errors[1].code").value("request.field.required"))
                .andExpect(jsonPath("$.errors[2].field").value("discount"))
                .andExpect(jsonPath("$.errors[2].code").value("request.field.too_large"))
                .andExpect(jsonPath("$.errors[2].params.max").value(100))
                .andExpect(jsonPath("$.errors[3].field").value("note"))
                .andExpect(jsonPath("$.errors[3].code").value("request.field.too_long"))
                .andExpect(jsonPath("$.errors[3].message").value("Too long: the maximum is 10"))
                .andExpect(jsonPath("$.errors[4].field").value("price"))
                .andExpect(jsonPath("$.errors[4].code").value("request.field.too_small"))
                .andExpect(jsonPath("$.errors[5].field").value("quantity"))
                .andExpect(jsonPath("$.errors[5].code").value("request.field.too_small"))
                .andExpect(jsonPath("$.errors[5].params.min").value(1))
                .andExpect(jsonPath("$.errors[6].field").value("tags"))
                .andExpect(jsonPath("$.errors[6].code").value("request.field.too_short"))
                .andExpect(jsonPath("$.errors[6].params.min").value(2));
    }

    @Test
    void aQueryParameterIsReportedByTheNameTheCallerUsed() throws Exception {
        mvc.perform(get("/orders").param("page-size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("request.invalid"))
                .andExpect(jsonPath("$.errors[0].field").value("page-size"))
                .andExpect(jsonPath("$.errors[0].code").value("request.field.too_large"))
                .andExpect(jsonPath("$.errors[0].params.max").value(100));
    }

    @Test
    void theMessagesFollowTheCallersLanguage() throws Exception {
        mvc.perform(get("/orders").param("page-size", "500").header("Accept-Language", "si"))
                .andExpect(jsonPath("$.title").value("යවන ලද තොරතුරු සමහරක් අඩු හෝ වලංගු නැත"))
                .andExpect(jsonPath("$.errors[0].message").value("අවසර ඇති විශාලතම අගය 100"));
    }

    /** The route a generated, @Validated interface takes: the validator's own exception. */
    @Test
    void aViolationReportedByTheValidatorItselfGetsTheSameAnswer() throws Exception {
        OrdersController controller = new OrdersController();
        Set<ConstraintViolation<OrdersController>> violations = Validation.buildDefaultValidatorFactory()
                .getValidator()
                .forExecutables()
                .validateParameters(
                        controller, OrdersController.class.getMethod("list", int.class), new Object[] {500});

        ProblemDetail problem =
                handler.parameters(new ConstraintViolationException(violations), new MockHttpServletRequest());

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getProperties()).containsEntry("code", "request.invalid");
        assertThat(problem.getProperties().get("errors"))
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .singleElement()
                .isEqualTo(Map.of(
                        "field", "pageSize",
                        "code", "request.field.too_large",
                        "message", "The largest value allowed is 100",
                        "params", Map.of("max", 100L)));
    }

    @Test
    void aBodyThatIsNotJsonIsMalformed() throws Exception {
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content("{ \"quantity\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("request.malformed"))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void aValueOfTheWrongTypeIsInvalid() throws Exception {
        mvc.perform(get("/orders").param("page-size", "many"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("request.invalid"))
                .andExpect(jsonPath("$.errors[0].field").value("page-size"))
                .andExpect(jsonPath("$.errors[0].code").value("request.field.invalid"));
    }

    // What the generator writes for a slice, by hand: every keyword a schema can carry.
    record OrderRequest(
            @NotNull String customer, // required
            @Min(1) Integer quantity, // minimum
            @Max(100) Integer discount, // maximum
            @DecimalMin("0.01") BigDecimal price, // minimum on a number
            @Pattern(regexp = "[A-Z]{3}") String code, // pattern
            @Size(min = 2) List<String> tags, // minItems
            @Size(max = 10) String note) { // maxLength
    }

    @RestController
    static class OrdersController {

        @PostMapping("/orders")
        public void create(@Valid @RequestBody OrderRequest request) {}

        @GetMapping("/orders")
        public void list(@RequestParam("page-size") @Max(100) int pageSize) {}
    }
}

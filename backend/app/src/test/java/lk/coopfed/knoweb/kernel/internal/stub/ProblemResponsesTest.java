package lk.coopfed.knoweb.kernel.internal.stub;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.internal.i18n.IcuMessages;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.ProblemDetail;

class ProblemResponsesTest {

    private final ProblemResponses responses = new ProblemResponses(new IcuMessages(new ObjectMapper()));

    @ParameterizedTest
    @CsvSource({
        "en, There is no print template invoice-x",
        "si, invoice-x නමින් මුද්‍රණ අච්චුවක් නැත",
        "ta, invoice-x என்ற அச்சு வார்ப்புரு இல்லை"
    })
    void aTitleWithANamedPlaceholderIsFilledInEveryLanguage(String language, String title) {
        ProblemDetail problem = responses.toProblem(
                new ProblemException("report.template_unknown", Map.of("templateId", "invoice-x")),
                Locale.forLanguageTag(language));

        assertThat(problem.getStatus()).isEqualTo(422);
        assertThat(problem.getTitle()).isEqualTo(title);
        assertThat(problem.getProperties())
                .containsEntry("code", "report.template_unknown")
                .containsEntry("params", Map.of("templateId", "invoice-x"))
                .doesNotContainKey("fallback");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "en | The printout is too large (5,000 bytes, at most 4,000)",
        "si | මුද්‍රණය ඉතා විශාල ය (බයිට් 5,000, උපරිමය 4,000)",
        "ta | அச்சுப்பிரதி மிகப் பெரியது (5,000 பைட்டுகள், அதிகபட்சம் 4,000)"
    })
    void severalNamedPlaceholdersAreFilled(String language, String title) {
        ProblemDetail problem = responses.toProblem(
                new ProblemException("report.too_large", Map.of("size", 5000, "maxBytes", 4000)),
                Locale.forLanguageTag(language));

        assertThat(problem.getStatus()).isEqualTo(422);
        assertThat(problem.getTitle()).isEqualTo(title);
        assertThat(problem.getProperties()).containsEntry("code", "report.too_large");
    }

    @ParameterizedTest
    @CsvSource({"en", "si", "ta"})
    void aProblemWithoutParametersStillAnswers(String language) {
        ProblemDetail problem = responses.toProblem(
                new ProblemException("scope.required"), Locale.forLanguageTag(language));

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getTitle()).isNotBlank();
        assertThat(problem.getProperties()).containsEntry("code", "scope.required");
    }
}

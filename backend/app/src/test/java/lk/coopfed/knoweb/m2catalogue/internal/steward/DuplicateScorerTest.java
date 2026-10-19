package lk.coopfed.knoweb.m2catalogue.internal.steward;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.m2catalogue.internal.steward.DuplicateScorer.ItemProfile;
import lk.coopfed.knoweb.m2catalogue.internal.steward.DuplicateScorer.ScoreResult;
import lk.coopfed.knoweb.m2catalogue.internal.steward.DuplicateScorer.SuspectMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DuplicateScorerTest {

    private DuplicateScorer scorer;
    private Transliterator transliterator;

    @BeforeEach
    void setUp() {
        transliterator = new Transliterator();
        scorer = new DuplicateScorer(transliterator);
    }

    @Test
    void identicalItemScoresNearOne() {
        ItemProfile item1 = new ItemProfile(
                UUID.randomUUID(),
                "SKU-001",
                "Anchor Milk Powder 400g",
                "ඇන්කර් කිරි පිටි",
                "ஆங்கர் பால் மா",
                "EA",
                "anchor",
                List.of("4792022001234"));

        ItemProfile item2 = new ItemProfile(
                UUID.randomUUID(),
                "SKU-002",
                "Anchor Milk Powder 400g",
                "ඇන්කර් කිරි පිටි",
                "ஆங்கர் பால் மா",
                "EA",
                "anchor",
                List.of("4792022001234"));

        ScoreResult score = scorer.score(item1, item2);
        assertThat(score.score().doubleValue()).isGreaterThanOrEqualTo(0.95);
        assertThat(score.reasons())
                .contains(
                        DuplicateScorer.REASON_NAME_SIMILARITY,
                        DuplicateScorer.REASON_BRAND_MATCH,
                        DuplicateScorer.REASON_PACK_MATCH,
                        DuplicateScorer.REASON_SAME_UOM,
                        DuplicateScorer.REASON_BARCODE_MATCH);
    }

    @Test
    void completelyDifferentItemsScoreLow() {
        ItemProfile milk = new ItemProfile(
                UUID.randomUUID(),
                "SKU-001",
                "Anchor Full Cream Milk Powder 400g",
                null,
                null,
                "EA",
                "anchor",
                List.of("4792022001234"));

        ItemProfile soap = new ItemProfile(
                UUID.randomUUID(),
                "SKU-002",
                "Sunlight Washing Soap 115g",
                null,
                null,
                "EA",
                "sunlight",
                List.of("4792022009999"));

        ScoreResult score = scorer.score(milk, soap);
        assertThat(score.score().doubleValue()).isLessThan(0.40);
    }

    @Test
    void barcodeBoostPushesSuspectPastThreshold() {
        // Items with slightly different text but shared barcode
        ItemProfile item1 = new ItemProfile(
                UUID.randomUUID(),
                "SKU-001",
                "Munchee Super Cream Cracker 490g",
                null,
                null,
                "EA",
                "munchee",
                List.of("4791023456789"));

        ItemProfile item2 = new ItemProfile(
                UUID.randomUUID(),
                "SKU-002",
                "Munchee Cream Cracker 490g Pkt",
                null,
                null,
                "EA",
                "munchee",
                List.of("4791023456789"));

        ScoreResult score = scorer.score(item1, item2);
        assertThat(score.score().doubleValue()).isGreaterThanOrEqualTo(DuplicateScorer.DEFAULT_THRESHOLD);
        assertThat(score.reasons()).contains(DuplicateScorer.REASON_BARCODE_MATCH);
    }

    @Test
    void detectsDuplicatesWithinBatch() {
        ItemProfile item1 = new ItemProfile(
                null, "SKU-100", "Maliban Marie Biscuits 300g", null, null, "EA", "maliban", List.of("4791011122334"));

        ItemProfile item2 = new ItemProfile(
                null, "SKU-101", "Maliban Gold Marie 300g", null, null, "EA", "maliban", List.of("4791011122334"));

        List<SuspectMatch> suspects = scorer.scoreWithinBatch(List.of(item1, item2), 0.85);
        assertThat(suspects).hasSize(1);
        assertThat(suspects.get(0).score().doubleValue()).isGreaterThanOrEqualTo(0.85);
    }
}

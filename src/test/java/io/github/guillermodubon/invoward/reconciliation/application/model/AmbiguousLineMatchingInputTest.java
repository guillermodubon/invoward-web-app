package io.github.guillermodubon.invoward.reconciliation.application.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmbiguousLineMatchingInputTest {

    @Test
    void storesOnlyMinimalIdentityFieldsAndDefensivelyCopiesCandidates() {
        List<AmbiguousLineMatchingInput.Line> candidates = new ArrayList<>(List.of(
                new AmbiguousLineMatchingInput.Line("C1", null, "Travel mug", "each"),
                new AmbiguousLineMatchingInput.Line("C2", "MUG-XL", "Large travel mug", null)));
        AmbiguousLineMatchingInput input = new AmbiguousLineMatchingInput(
                new AmbiguousLineMatchingInput.Line("REF", "MUG", "Reusable mug", "each"), candidates);

        candidates.clear();

        assertEquals(2, input.invoiceCandidates().size());
        assertEquals(List.of("label", "itemCode", "description", "unit"),
                java.util.Arrays.stream(AmbiguousLineMatchingInput.Line.class.getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
        assertTrue(input.toString().contains("invoiceCandidateCount=2"));
        assertFalse(input.toString().contains("Reusable mug"));
        assertFalse(input.invoiceCandidates().getFirst().toString().contains("Travel mug"));
    }

    @Test
    void rejectsDatabaseIdentifiersInvalidLabelsDuplicateLabelsAndOversizedShortlists() {
        AmbiguousLineMatchingInput.Line reference = line("REF", "reference");

        assertThrows(IllegalArgumentException.class,
                () -> new AmbiguousLineMatchingInput(line("C1", "not reference"), List.of(line("C1", "candidate"))));
        assertThrows(IllegalArgumentException.class,
                () -> new AmbiguousLineMatchingInput(reference, List.of(line("invoice-uuid", "candidate"))));
        assertThrows(IllegalArgumentException.class,
                () -> new AmbiguousLineMatchingInput(reference, List.of(line("C1", "one"), line("C1", "two"))));

        List<AmbiguousLineMatchingInput.Line> tooMany = java.util.stream.IntStream.rangeClosed(1, 11)
                .mapToObj(index -> line("C" + index, "candidate"))
                .toList();
        assertThrows(IllegalArgumentException.class, () -> new AmbiguousLineMatchingInput(reference, tooMany));
    }

    @Test
    void rejectsBlankDescriptions() {
        assertThrows(IllegalArgumentException.class,
                () -> new AmbiguousLineMatchingInput.Line("REF", null, "  ", null));
    }

    private static AmbiguousLineMatchingInput.Line line(String label, String description) {
        return new AmbiguousLineMatchingInput.Line(label, null, description, null);
    }
}

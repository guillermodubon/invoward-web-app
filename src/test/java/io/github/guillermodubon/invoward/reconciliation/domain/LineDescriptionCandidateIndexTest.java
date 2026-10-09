package io.github.guillermodubon.invoward.reconciliation.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LineDescriptionCandidateIndexTest {

    private final LineDescriptionSimilarity similarity = new LineDescriptionSimilarity();

    @Test
    void limitsScoringToCandidatesWithSharedFeaturesAndCapsByOverlapRanking() {
        LineDescriptionCandidateIndex index = new LineDescriptionCandidateIndex(List.of(
                candidate(1, 0, "red carton apples"),
                candidate(2, 1, "red carton"),
                candidate(3, 2, "apples"),
                candidate(4, 3, "unrelated subscription")), similarity, 2);

        List<LineDescriptionCandidateIndex.RankedCandidate> ranked = index.rank("red carton apples");

        assertEquals(List.of(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        UUID.fromString("00000000-0000-0000-0000-000000000002")),
                ranked.stream().map(result -> result.candidate().id()).toList());
        assertEquals(2, ranked.size());
        assertTrue(ranked.getFirst().overlapCount() > ranked.getLast().overlapCount());
    }

    @Test
    void returnsDeterministicTiesByPositionThenUuidRegardlessOfInputOrder() {
        LineDescriptionCandidateIndex.Candidate laterId = candidate(2, 1, "paper clips box");
        LineDescriptionCandidateIndex.Candidate earlierId = candidate(1, 1, "paper clips box");
        LineDescriptionCandidateIndex.Candidate earlierPosition = candidate(3, 0, "paper clips box");

        List<LineDescriptionCandidateIndex.Candidate> expected = List.of(earlierPosition, earlierId, laterId);
        List<UUID> first = new LineDescriptionCandidateIndex(
                List.of(laterId, earlierPosition, earlierId), similarity, 20)
                .rank("paper clips box").stream().map(result -> result.candidate().id()).toList();
        List<UUID> second = new LineDescriptionCandidateIndex(
                List.of(earlierId, laterId, earlierPosition), similarity, 20)
                .rank("paper clips box").stream().map(result -> result.candidate().id()).toList();

        assertEquals(expected.stream().map(LineDescriptionCandidateIndex.Candidate::id).toList(), first);
        assertEquals(first, second);
    }

    @Test
    void rejectsDuplicateCandidateIdsAndInvalidCandidateCap() {
        LineDescriptionCandidateIndex.Candidate duplicate = candidate(1, 0, "same line");
        assertThrows(IllegalArgumentException.class, () -> new LineDescriptionCandidateIndex(
                List.of(duplicate, duplicate), similarity, 20));
        assertThrows(IllegalArgumentException.class,
                () -> new LineDescriptionCandidateIndex(List.of(), similarity, 0));
    }

    private static LineDescriptionCandidateIndex.Candidate candidate(int id, int position, String text) {
        return new LineDescriptionCandidateIndex.Candidate(
                UUID.fromString("00000000-0000-0000-0000-%012d".formatted(id)), position, text);
    }
}

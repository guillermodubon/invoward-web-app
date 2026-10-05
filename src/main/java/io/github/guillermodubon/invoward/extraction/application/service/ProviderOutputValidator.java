package io.github.guillermodubon.invoward.extraction.application.service;

import io.github.guillermodubon.invoward.extraction.application.exception.InvalidExtractionOutputException;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionDraft;
import io.github.guillermodubon.invoward.extraction.domain.BoundingBox;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedLineItem;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionSource;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

public final class ProviderOutputValidator {

    private static final int ABSOLUTE_MAX_LINES = 500;
    private static final int MIN_SOURCE_TEXT_CODE_POINTS = 200;
    private static final int ABSOLUTE_MAX_SOURCE_TEXT_CODE_POINTS = 4000;

    private final int maxLineItems;
    private final int maxSourceTextCodePoints;

    public ProviderOutputValidator(int maxLineItems, int maxSourceTextCodePoints) {
        if (maxLineItems < 1 || maxLineItems > ABSOLUTE_MAX_LINES) {
            throw new IllegalArgumentException("maxLineItems must be between 1 and 500");
        }
        if (maxSourceTextCodePoints < MIN_SOURCE_TEXT_CODE_POINTS
                || maxSourceTextCodePoints > ABSOLUTE_MAX_SOURCE_TEXT_CODE_POINTS) {
            throw new IllegalArgumentException("maxSourceTextCodePoints must be between 200 and 4000");
        }
        this.maxLineItems = maxLineItems;
        this.maxSourceTextCodePoints = maxSourceTextCodePoints;
    }

    public ExtractedDocument validate(ExtractionDraft draft, int sourcePageCount) {
        return validate(draft, sourcePageCount, ExtractionSource.AI);
    }

    public ExtractedDocument validate(
            ExtractionDraft draft,
            int sourcePageCount,
            ExtractionSource extractionSource) {
        if (draft == null || sourcePageCount < 1 || extractionSource == null
                || draft.lines() == null || draft.lines().size() > maxLineItems) {
            throw new InvalidExtractionOutputException();
        }

        try {
            List<ExtractedLineItem> lines = new ArrayList<>(draft.lines().size());
            for (int index = 0; index < draft.lines().size(); index++) {
                ExtractionDraft.Line line = draft.lines().get(index);
                if (line == null) {
                    throw new InvalidExtractionOutputException();
                }
                validatePage(line.pageNumber(), sourcePageCount);
                validateSourceText(line.sourceText());
                lines.add(new ExtractedLineItem(
                        index,
                        line.itemCode(),
                        line.description(),
                        line.quantity(),
                        line.unit(),
                        line.unitPrice(),
                        line.discountAmount(),
                        line.taxAmount(),
                        line.lineTotal(),
                        line.pageNumber(),
                        line.sourceText(),
                        boundingBox(line.boundingBox())));
            }

            return ExtractedDocument.draft(
                    extractionSource,
                    draft.vendorName(),
                    draft.documentNumber(),
                    parseDate(draft.documentDate()),
                    draft.currency(),
                    draft.subtotal(),
                    draft.discountTotal(),
                    draft.taxTotal(),
                    draft.total(),
                    lines);
        } catch (InvalidExtractionOutputException exception) {
            throw exception;
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw new InvalidExtractionOutputException();
        }
    }

    private static void validatePage(Integer pageNumber, int sourcePageCount) {
        if (pageNumber != null && (pageNumber < 1 || pageNumber > sourcePageCount)) {
            throw new InvalidExtractionOutputException();
        }
    }

    private void validateSourceText(String sourceText) {
        if (sourceText != null && sourceText.codePointCount(0, sourceText.length()) > maxSourceTextCodePoints) {
            throw new InvalidExtractionOutputException();
        }
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException exception) {
            throw new InvalidExtractionOutputException();
        }
    }

    private static BoundingBox boundingBox(ExtractionDraft.RawBoundingBox raw) {
        if (raw == null) {
            return null;
        }
        int providedCoordinates = (raw.xMin() == null ? 0 : 1)
                + (raw.yMin() == null ? 0 : 1)
                + (raw.xMax() == null ? 0 : 1)
                + (raw.yMax() == null ? 0 : 1);
        if (providedCoordinates == 0) {
            return null;
        }
        if (providedCoordinates != 4) {
            throw new InvalidExtractionOutputException();
        }
        return new BoundingBox(raw.xMin(), raw.yMin(), raw.xMax(), raw.yMax());
    }
}

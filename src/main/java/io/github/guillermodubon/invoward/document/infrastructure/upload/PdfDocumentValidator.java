package io.github.guillermodubon.invoward.document.infrastructure.upload;

import io.github.guillermodubon.invoward.document.application.exception.DocumentUploadValidationException;
import io.github.guillermodubon.invoward.document.application.exception.DocumentUploadValidationException.Failure;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/** Performs parser-backed PDF validation without exposing PDFBox errors to callers. */
@Component
public final class PdfDocumentValidator {

    private static final byte[] PDF_HEADER = "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    public int validate(Path stagedFile, int maximumPages) {
        Objects.requireNonNull(stagedFile, "stagedFile must not be null");
        if (maximumPages <= 0) {
            throw new IllegalArgumentException("maximumPages must be greater than 0");
        }
        if (!hasPdfHeader(stagedFile)) {
            throw invalidDocument();
        }

        try (PDDocument document = load(stagedFile)) {
            if (document.isEncrypted()) {
                throw new DocumentUploadValidationException(Failure.PDF_PASSWORD_PROTECTED);
            }
            int pageCount = document.getNumberOfPages();
            if (pageCount < 1) {
                throw invalidDocument();
            }
            if (pageCount > maximumPages) {
                throw new DocumentUploadValidationException(Failure.PDF_PAGE_LIMIT_EXCEEDED);
            }
            return pageCount;
        } catch (DocumentUploadValidationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalidDocument();
        }
    }

    boolean hasPdfHeader(Path stagedFile) {
        try (InputStream input = Files.newInputStream(stagedFile)) {
            return Arrays.equals(input.readNBytes(PDF_HEADER.length), PDF_HEADER);
        } catch (IOException exception) {
            throw invalidDocument();
        }
    }

    private static PDDocument load(Path stagedFile) {
        try {
            return Loader.loadPDF(stagedFile.toFile());
        } catch (InvalidPasswordException exception) {
            throw new DocumentUploadValidationException(Failure.PDF_PASSWORD_PROTECTED);
        } catch (IOException | RuntimeException exception) {
            throw invalidDocument();
        }
    }

    private static DocumentUploadValidationException invalidDocument() {
        return new DocumentUploadValidationException(Failure.DOCUMENT_INVALID);
    }
}

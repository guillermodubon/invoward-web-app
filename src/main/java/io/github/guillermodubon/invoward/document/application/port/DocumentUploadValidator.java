package io.github.guillermodubon.invoward.document.application.port;

import io.github.guillermodubon.invoward.document.application.model.IncomingDocumentUpload;
import io.github.guillermodubon.invoward.document.application.model.ValidatedDocumentUpload;

/** Converts an untrusted stream into a bounded, temporary-file-backed upload. */
public interface DocumentUploadValidator {

    /** Consumes and closes {@code incoming}'s stream; the returned upload must be closed by its caller. */
    ValidatedDocumentUpload validate(IncomingDocumentUpload incoming);
}

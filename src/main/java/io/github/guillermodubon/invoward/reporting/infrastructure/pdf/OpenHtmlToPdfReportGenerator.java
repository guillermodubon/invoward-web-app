package io.github.guillermodubon.invoward.reporting.infrastructure.pdf;

import com.openhtmltopdf.outputdevice.helper.ExternalResourceControlPriority;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.util.Diagnostic;
import com.openhtmltopdf.util.XRLog;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportGenerationException;
import io.github.guillermodubon.invoward.reporting.application.model.ReportSnapshot;
import io.github.guillermodubon.invoward.reporting.application.port.ReportGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.logging.Level;
import java.util.Objects;

/** OpenHTMLtoPDF adapter; it renders only and never stores or persists the report. */
@Component
public final class OpenHtmlToPdfReportGenerator implements ReportGenerator {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenHtmlToPdfReportGenerator.class);

    static {
        // The default load logger includes rejected resource URIs in warning messages.
        XRLog.setLevel(XRLog.LOAD, Level.OFF);
    }

    private final ReconciliationReportHtmlRenderer htmlRenderer;

    public OpenHtmlToPdfReportGenerator(ReconciliationReportHtmlRenderer htmlRenderer) {
        this.htmlRenderer = Objects.requireNonNull(htmlRenderer, "htmlRenderer must not be null");
    }

    @Override
    public void generate(ReportSnapshot snapshot, Path destination) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(destination, "destination must not be null");

        try {
            renderHtmlToPdf(htmlRenderer.render(snapshot), destination);
        } catch (IOException | RuntimeException exception) {
            throw new ReportGenerationException(exception);
        }
    }

    void renderHtmlToPdf(String html, Path destination) throws IOException {
        Objects.requireNonNull(html, "html must not be null");
        Objects.requireNonNull(destination, "destination must not be null");

        try (OutputStream output = Files.newOutputStream(
                destination, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            new PdfRendererBuilder()
                    .withHtmlContent(html, null)
                    .useExternalResourceAccessControl(
                            (uri, resourceType) -> false,
                            ExternalResourceControlPriority.RUN_BEFORE_RESOLVING_URI)
                    .withDiagnosticConsumer(OpenHtmlToPdfReportGenerator::logSafeDiagnostic)
                    .toStream(output)
                    .run();
        }
    }

    private static void logSafeDiagnostic(Diagnostic diagnostic) {
        if (diagnostic.getLevel().intValue() >= Level.WARNING.intValue()) {
            LOGGER.warn("operation=report_pdf_render result=warning diagnostic={}",
                    diagnostic.getLogMessageId());
        }
    }
}

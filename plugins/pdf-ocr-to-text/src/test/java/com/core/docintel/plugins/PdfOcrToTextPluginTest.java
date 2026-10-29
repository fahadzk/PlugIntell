package com.core.docintel.plugins;

import com.core.docintel.pipeline.api.TextDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PdfOcrToTextPluginTest {
    @TempDir Path temp;

    @Test void extractsTextAndRetainsPageCitationWithoutNeedingOcrForTextPdf() throws Exception {
        File pdf = temp.resolve("two-pages.pdf").toFile();
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < 2; i++) {
                PDPage page = new PDPage(); document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(50, 700); stream.showText("Page " + (i + 1) + " content"); stream.endText();
                }
            }
            document.save(pdf);
        }
        PdfOcrToTextPlugin plugin = new PdfOcrToTextPlugin(new FakeOcr(), 200);
        TextDocument document = (TextDocument) plugin.execute(pdf);
        assertEquals(2, document.pages().size());
        assertEquals(1, document.pages().getFirst().pageNumber());
        assertTrue(document.pages().get(1).text().contains("Page 2 content"));
        assertEquals(64, document.documentId().length());
    }

    @Test void rejectsMissingPdf() {
        var plugin = new PdfOcrToTextPlugin(new FakeOcr(), 200);
        assertThrows(IllegalArgumentException.class, () -> plugin.execute(temp.resolve("missing.pdf").toFile()));
    }

    @Test void OCRsBlankPagesThroughTheConfiguredEngine() throws Exception {
        File pdf = temp.resolve("scan.pdf").toFile();
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(pdf);
        }
        var plugin = new PdfOcrToTextPlugin(new FakeOcr(), 200);
        TextDocument document = (TextDocument) plugin.execute(pdf);
        assertEquals("recognized scan", document.pages().getFirst().text());
        assertEquals(1, document.pages().getFirst().pageNumber());
    }

    @Test void reportsUnavailableOcrEngineDuringPreflight() {
        var missingOcr = new FakeOcr() {
            @Override public void preflight() { throw new IllegalStateException("Tesseract was not found"); }
        };
        var error = assertThrows(IllegalStateException.class, () -> new PdfOcrToTextPlugin(missingOcr, 200).preflight());
        assertTrue(error.getMessage().contains("Tesseract was not found"));
    }

    private static class FakeOcr implements PdfOcrToTextPlugin.OcrEngine {
        public void preflight() {}
        public String recognize(BufferedImage image) { return "recognized scan"; }
    }
}

package com.core.docintel.plugins;

import com.core.docintel.pipeline.api.PluginManifest;
import com.core.docintel.pipeline.api.PreflightPlugin;
import com.core.docintel.pipeline.api.TextDocument;
import com.core.docintel.pipeline.api.TextPage;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class PdfOcrToTextPlugin implements PreflightPlugin {
    private static final PluginManifest MANIFEST = new PluginManifest(
            "pdf-ocr-to-text", "PDF OCR to Text", "1.0.0", "1.0",
            "com.core.docintel.plugins.PdfOcrToTextPlugin", "File", "TextDocument");

    private final OcrEngine ocrEngine;
    private final int ocrDpi;

    public PdfOcrToTextPlugin() {
        this(new TikaTesseractOcrEngine(), Integer.getInteger("plugin.pdf-ocr.dpi", 300));
    }

    PdfOcrToTextPlugin(OcrEngine ocrEngine, int ocrDpi) {
        if (ocrDpi < 72 || ocrDpi > 600) throw new IllegalArgumentException("OCR DPI must be between 72 and 600.");
        this.ocrEngine = ocrEngine;
        this.ocrDpi = ocrDpi;
    }

    @Override public PluginManifest manifest() { return MANIFEST; }

    @Override
    public void preflight() {
        ocrEngine.preflight();
    }

    @Override
    public Object execute(Object input) {
        if (!(input instanceof File pdf) || !pdf.isFile() || !pdf.canRead()) {
            throw new IllegalArgumentException("PDF OCR plugin expects an existing readable PDF file.");
        }
        if (!pdf.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
            throw new IllegalArgumentException("PDF OCR plugin only accepts .pdf files: " + pdf.getName());
        }
        try {
            List<TextPage> pages = new ArrayList<>();
            try (PDDocument document = Loader.loadPDF(pdf)) {
                if (document.isEncrypted()) throw new IllegalArgumentException("Encrypted PDFs are not supported: " + pdf.getName());
                PDFRenderer renderer = new PDFRenderer(document);
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                for (int page = 1; page <= document.getNumberOfPages(); page++) {
                    stripper.setStartPage(page);
                    stripper.setEndPage(page);
                    String text = stripper.getText(document).strip();
                    if (text.isBlank()) {
                        text = ocrEngine.recognize(renderer.renderImageWithDPI(page - 1, ocrDpi)).strip();
                    }
                    if (!text.isBlank()) pages.add(new TextPage(page, text));
                }
            }
            if (pages.isEmpty()) throw new IllegalStateException("No text could be extracted or OCRed from " + pdf.getName());
            return new TextDocument(sha256(pdf), pdf.getName(), pages);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read PDF " + pdf.getName() + ": " + e.getMessage(), e);
        }
    }

    private static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file.toPath())) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }

    interface OcrEngine {
        void preflight();
        String recognize(java.awt.image.BufferedImage image) throws IOException;
    }
}

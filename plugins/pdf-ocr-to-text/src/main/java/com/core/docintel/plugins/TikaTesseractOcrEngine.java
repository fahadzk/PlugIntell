package com.core.docintel.plugins;

import org.apache.tika.exception.TikaConfigException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.parser.ocr.TesseractOCRParser;
import org.apache.tika.sax.BodyContentHandler;

import java.awt.image.BufferedImage;
import java.io.IOException;

final class TikaTesseractOcrEngine implements PdfOcrToTextPlugin.OcrEngine {
    private final TesseractOCRParser parser = new TesseractOCRParser();
    private final TesseractOCRConfig config = new TesseractOCRConfig();

    TikaTesseractOcrEngine() {
        this(java.util.Map.of());
    }

    TikaTesseractOcrEngine(java.util.Map<String,Object> options) {
        String tessPath = options.getOrDefault("PDF_OCR_TESSERACT_PATH",System.getenv().getOrDefault("PDF_OCR_TESSERACT_PATH","")).toString();
        if (tessPath != null && !tessPath.isBlank()) parser.setTesseractPath(tessPath);
        String tessdata = options.getOrDefault("PDF_OCR_TESSDATA_PATH",System.getenv().getOrDefault("PDF_OCR_TESSDATA_PATH","")).toString();
        if (tessdata != null && !tessdata.isBlank()) parser.setTessdataPath(tessdata);
        config.setLanguage(options.getOrDefault("PDF_OCR_LANGUAGE",System.getenv().getOrDefault("PDF_OCR_LANGUAGE", "eng")).toString());
        config.setTimeoutSeconds(Integer.parseInt(options.getOrDefault("PLUGIN_PDF_OCR_TIMEOUT_SECONDS",System.getenv().getOrDefault("PLUGIN_PDF_OCR_TIMEOUT_SECONDS", System.getProperty("plugin.pdf-ocr.timeout-seconds", "120"))).toString()));
    }

    @Override
    public void preflight() {
        try {
            if (!parser.hasTesseract()) {
                throw new IllegalStateException("Tesseract OCR was not found. Install Tesseract, add it to PATH, or set PDF_OCR_TESSERACT_PATH to its installation folder.");
            }
        } catch (TikaConfigException e) {
            throw new IllegalStateException("Could not verify Tesseract OCR: " + e.getMessage(), e);
        }
    }

    @Override
    public String recognize(BufferedImage image) throws IOException {
        try {
            BodyContentHandler handler = new BodyContentHandler(-1);
            ParseContext context = new ParseContext();
            context.set(TesseractOCRConfig.class, config);
            parser.parse(image, handler, new Metadata(), context);
            return handler.toString();
        } catch (Exception e) {
            throw new IOException("Tesseract OCR failed: " + e.getMessage(), e);
        }
    }
}

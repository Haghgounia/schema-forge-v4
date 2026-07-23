package com.behsazan.schemaforge.api;

import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.specification.adapter.docx.DocxSpecificationParser;
import com.behsazan.schemaforge.specification.spi.SpecificationSource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/schema/import")
public final class WordImportController {
    private final DocxSpecificationParser parser;

    public WordImportController(DocxSpecificationParser parser) {
        this.parser = parser;
    }

    @PostMapping(value = "/word", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DatabaseSchema importWord(@RequestPart("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("DOCX file must not be empty");
        }
        if (!parser.supports(file.getOriginalFilename())) {
            throw new IllegalArgumentException("Only .docx files are supported");
        }
        return parser.parse(new SpecificationSource(file.getOriginalFilename(), file.getInputStream()));
    }
}

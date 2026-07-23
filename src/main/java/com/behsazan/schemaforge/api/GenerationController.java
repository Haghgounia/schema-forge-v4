package com.behsazan.schemaforge.api;

import com.behsazan.schemaforge.dialect.DialectRegistry;
import com.behsazan.schemaforge.generation.GenerationEngine;
import com.behsazan.schemaforge.model.DatabaseProduct;
import com.behsazan.schemaforge.model.SchemaDefinition;
import com.behsazan.schemaforge.parser.SchemaNormalizer;
import com.behsazan.schemaforge.validation.ValidationEngine;
import com.behsazan.schemaforge.validation.ValidationMessage;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/generation")
public class GenerationController {
    private final SchemaNormalizer normalizer;
    private final DialectRegistry dialectRegistry;
    private final ValidationEngine validationEngine;
    private final GenerationEngine generationEngine;

    public GenerationController(
            SchemaNormalizer normalizer,
            DialectRegistry dialectRegistry,
            ValidationEngine validationEngine,
            GenerationEngine generationEngine
    ) {
        this.normalizer = normalizer;
        this.dialectRegistry = dialectRegistry;
        this.validationEngine = validationEngine;
        this.generationEngine = generationEngine;
    }

    @PostMapping("/{databaseProduct}/ddl")
    public String generate(
            @PathVariable DatabaseProduct databaseProduct,
            @RequestBody SchemaDefinition input
    ) {
        var dialect = dialectRegistry.require(databaseProduct);
        var normalized = normalizer.normalize(input);
        List<ValidationMessage> messages = validationEngine.validate(normalized, dialect);
        if (!messages.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, messages.toString());
        }
        return generationEngine.generateDdl(normalized, dialect);
    }
}

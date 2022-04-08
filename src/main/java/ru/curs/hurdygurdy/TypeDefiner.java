package ru.curs.hurdygurdy;

import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Discriminator;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class TypeDefiner<T> {
    private static final Pattern CLASS_NAME_PATTERN = Pattern.compile("/([^/$]+)$");
    private static final Pattern FILE_NAME_PATTERN = Pattern.compile("^([^#]*)#");

    final BiConsumer<ClassCategory, T> typeSpecBiConsumer;
    final String rootPackage;
    final Map<String, String> externalPackages = new HashMap<>();
    private Path sourceFile;

    TypeDefiner(String rootPackage, BiConsumer<ClassCategory, T> typeSpecBiConsumer) {
        this.rootPackage = rootPackage;
        this.typeSpecBiConsumer = typeSpecBiConsumer;
    }

    final T getDTO(String name, Schema<?> schema, OpenAPI openAPI) {
        if (schema.getEnum() != null) {
            return getEnum(name, schema, openAPI);
        } else {
            return getDTOClass(name, schema, openAPI);
        }
    }

    @SuppressWarnings("unchecked")
    final List<String> getExtendsList(Schema<?> schema) {
        List<String> extendsList = new ArrayList<>();
        Optional.ofNullable(schema.getExtensions()).map(e -> e.get("x-extends"))
                .ifPresent(e -> {
                            if (e instanceof String) {
                                extendsList.add((String) e);
                            } else if (e instanceof List) {
                                extendsList.addAll((List<String>) e);
                            }
                        }
                );
        return extendsList;
    }

    final Map<String, String> getSubclassMapping(Schema<?> schema) {
        return Optional.ofNullable(schema.getDiscriminator())
                .map(Discriminator::getMapping).orElse(Collections.emptyMap());
    }

    abstract T getEnum(String name, Schema<?> schema, OpenAPI openAPI);

    abstract T getDTOClass(String name, Schema<?> schema, OpenAPI openAPI);

    com.squareup.javapoet.TypeName defineJavaType(Schema<?> schema, OpenAPI openAPI,
                                                  com.squareup.javapoet.TypeSpec.Builder parent) {
        throw new IllegalStateException();
    }

    com.squareup.kotlinpoet.TypeName defineKotlinType(Schema<?> schema, OpenAPI openAPI,
                                                      com.squareup.kotlinpoet.TypeSpec.Builder parent) {
        throw new IllegalStateException();
    }

    void init(Path currentSourceFile) {
        this.sourceFile = currentSourceFile;
        externalPackages.clear();
    }

    String getPackage(String ref) {
        String fileName = getClassFile(ref);
        if (fileName.isBlank()) {
            return rootPackage;
        } else {
            return externalPackages.computeIfAbsent(fileName, f -> {
                ParseOptions parseOptions = new ParseOptions();
                Path externalFile = sourceFile.resolveSibling(fileName);
                try {
                    final SwaggerParseResult parseResult = new OpenAPIParser()
                            .readContents(Files.readString(externalFile), null, parseOptions);
                    OpenAPI openAPI = parseResult.getOpenAPI();
                    return Optional.ofNullable(openAPI.getExtensions())
                            .map(e -> e.get("x-package"))
                            .map(String.class::cast)
                            .orElseThrow(() -> new IllegalStateException(
                                    String.format(
                                            "x-package not defined for externally linked file %s ", externalFile)));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }

    String getClassName(String ref) {
        return extractGroup(ref, CLASS_NAME_PATTERN);
    }

    private String getClassFile(String ref) {
        return extractGroup(ref, FILE_NAME_PATTERN);
    }

    private String extractGroup(String ref, Pattern pattern) {
        Matcher matcher = pattern.matcher(ref);
        if (matcher.find()) {
            return matcher.group(1);
        } else {
            throw new IllegalStateException("Illegal ref:" + ref);
        }
    }
}

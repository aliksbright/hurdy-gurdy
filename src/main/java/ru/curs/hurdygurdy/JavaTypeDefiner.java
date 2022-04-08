package ru.curs.hurdygurdy;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import lombok.Data;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

public final class JavaTypeDefiner extends TypeDefiner<TypeSpec> {
    private boolean hasJsonZonedDateTimeDeserializer;

    public JavaTypeDefiner(String rootPackage, BiConsumer<ClassCategory, TypeSpec> typeSpecBiConsumer) {
        super(rootPackage, typeSpecBiConsumer);
    }

    @Override
    public TypeName defineJavaType(Schema<?> schema, OpenAPI openAPI, TypeSpec.Builder parent) {
        @SuppressWarnings("LocalVariableName")
        String $ref = schema.get$ref();
        if ($ref == null) {
            String internalType = schema.getType();
            switch (internalType) {
                case "string":
                    if ("date".equals(schema.getFormat())) {
                        return TypeName.get(LocalDate.class);
                    } else if ("date-time".equals(schema.getFormat())) {
                        return TypeName.get(ZonedDateTime.class);
                    } else if ("uuid".equals(schema.getFormat())) {
                        return ClassName.get(UUID.class);
                    } else if (schema.getEnum() != null) {
                        //internal enum
                        String simpleName = schema.getTitle();
                        if (simpleName == null) {
                            throw new IllegalStateException("Inline enum schema must have a title");
                        }
                        TypeSpec.Builder enumBuilder = TypeSpec.enumBuilder(simpleName).addModifiers(Modifier.PUBLIC);
                        for (Object e : schema.getEnum()) {
                            enumBuilder.addEnumConstant(e.toString());
                        }
                        TypeSpec internalEnum = enumBuilder.build();
                        parent.addType(internalEnum);

                        return ClassName.get("", simpleName);
                    } else return ClassName.get(String.class);
                case "number":
                    if ("float".equals(schema.getFormat())) {
                        return TypeName.FLOAT.box();
                    } else {
                        return TypeName.DOUBLE.box();
                    }
                case "integer":
                    if ("int64".equals(schema.getFormat())) {
                        return TypeName.LONG.box();
                    } else {
                        return TypeName.INT.box();
                    }
                case "boolean":
                    return TypeName.BOOLEAN;
                case "array":
                    Schema<?> itemsSchema = ((ArraySchema) schema).getItems();
                    return ParameterizedTypeName.get(ClassName.get(List.class),
                            defineJavaType(itemsSchema, openAPI, parent));
                case "object":
                default:
                    String simpleName = schema.getTitle();
                    if (simpleName != null) {
                        typeSpecBiConsumer.accept(ClassCategory.DTO, getDTO(simpleName, schema, openAPI));
                        return ClassName.get(String.join(".", rootPackage, "dto"),
                                simpleName);
                    } else {
                        //This means failure, in fact.
                        return ClassName.OBJECT;
                    }
            }
        } else {
            return referencedClassName($ref);
        }
    }

    private ClassName referencedClassName(String ref) {
        String className = getClassName(ref);
        String packageName = getPackage(ref);
        return ClassName.get(String.join(".", packageName, "dto"), className);
    }

    private void ensureJsonZonedDateTimeDeserializer() {
        if (!hasJsonZonedDateTimeDeserializer) {
            TypeSpec typeSpec =
                    TypeSpec.classBuilder("ZonedDateTimeDeserializer")
                            .superclass(ParameterizedTypeName.get(
                                    ClassName.get(JsonDeserializer.class),
                                    ClassName.get(ZonedDateTime.class)
                            ))
                            .addModifiers(Modifier.PUBLIC)
                            .addField(FieldSpec.builder(ClassName.get(DateTimeFormatter.class),
                                    "formatter")
                                    .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                                    .initializer("$T.ISO_OFFSET_DATE_TIME", DateTimeFormatter.class)
                                    .build())
                            .addMethod(MethodSpec.methodBuilder(
                                            "deserialize")
                                    .returns(ClassName.get(ZonedDateTime.class))
                                    .addAnnotation(Override.class)
                                    .addModifiers(Modifier.PUBLIC)
                                    .addParameter(ParameterSpec.builder(JsonParser.class, "jsonParser").build())
                                    .addParameter(ParameterSpec.builder(DeserializationContext.class,
                                            "deserializationContext").build())
                                    .addException(IOException.class)

                                    .addStatement("String date = jsonParser.getText()")
                                    .beginControlFlow("try ")
                                    .addStatement(
                                            "return $T.parse(date, formatter)", ZonedDateTime.class)
                                    .endControlFlow()
                                    .beginControlFlow("catch ($T e)", DateTimeException.class)
                                    .addStatement("throw new $T(jsonParser, e.getMessage())", JsonParseException.class)
                                    .endControlFlow()
                                    .build())
                            .build();
            typeSpecBiConsumer.accept(ClassCategory.DTO, typeSpec);
            typeSpec =
                    TypeSpec.classBuilder("ZonedDateTimeSerializer")
                            .superclass(ParameterizedTypeName.get(
                                    ClassName.get(JsonSerializer.class),
                                    ClassName.get(ZonedDateTime.class)
                            ))
                            .addModifiers(Modifier.PUBLIC)
                            .addField(FieldSpec.builder(ClassName.get(DateTimeFormatter.class),
                                            "formatter")
                                    .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                                    .initializer("$T.ISO_OFFSET_DATE_TIME", DateTimeFormatter.class)
                                    .build())
                            .addMethod(MethodSpec.methodBuilder(
                                            "serialize")
                                    .addAnnotation(Override.class)
                                    .addModifiers(Modifier.PUBLIC)
                                    .addParameter(ParameterSpec.builder(ZonedDateTime.class, "value").build())
                                    .addParameter(ParameterSpec.builder(JsonGenerator.class,
                                            "gen").build())
                                    .addParameter(ParameterSpec.builder(SerializerProvider.class,
                                            "serializers").build())
                                    .addException(IOException.class)

                                    .addStatement("gen.writeString(formatter.format(value))")
                                    .build())
                            .build();
            typeSpecBiConsumer.accept(ClassCategory.DTO, typeSpec);
            hasJsonZonedDateTimeDeserializer = true;
        }
    }

    @Override
    TypeSpec getDTOClass(String name, Schema<?> schema, OpenAPI openAPI) {
        if (schema instanceof ComposedSchema) {
            var cs = (ComposedSchema) schema;
            ClassName baseClass = ClassName.get(Object.class);
            Schema<?> currentSchema = schema;
            for (Schema<?> s : cs.getAllOf()) {
                if (s.get$ref() != null) {
                    baseClass = referencedClassName(s.get$ref());
                } else {
                    currentSchema = s;
                }
            }
            return getDTOClass(name, currentSchema, openAPI, baseClass);
        } else {
            return getDTOClass(name, schema, openAPI, ClassName.get(Object.class));
        }
    }

    private TypeSpec getDTOClass(String name, Schema<?> schema, OpenAPI openAPI, ClassName baseClass) {
        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(name)
                .superclass(baseClass)
                .addAnnotation(Data.class)
                .addAnnotation(AnnotationSpec.builder(JsonNaming.class).addMember("value",
                        "$T.class", ClassName.get(PropertyNamingStrategies.SnakeCaseStrategy.class)).build())
                .addModifiers(Modifier.PUBLIC);
        //This class is a superclass
        if (schema.getDiscriminator() != null) {
            classBuilder.addAnnotation(AnnotationSpec
                    .builder(JsonTypeInfo.class)
                    .addMember("use", "$T.$L", JsonTypeInfo.Id.class, JsonTypeInfo.Id.NAME.name())
                    .addMember("include", "$T.$L", JsonTypeInfo.As.class, JsonTypeInfo.As.PROPERTY.name())
                    .addMember("property", "$S", schema.getDiscriminator().getPropertyName())
                    .build());
        }
        var subclassMapping = getSubclassMapping(schema);
        if (!subclassMapping.isEmpty()) {
            CodeBlock collect = subclassMapping.entrySet().stream()
                    .map(e ->
                            AnnotationSpec.builder(JsonSubTypes.Type.class)
                                    .addMember("value", "$T.class", referencedClassName(e.getValue()))
                                    .addMember("name", "$S", e.getKey()).build())
                    .map(a -> CodeBlock.of("$L", a))
                    .collect(CodeBlock.joining(",\n", "{\n", "}"));
            classBuilder.addAnnotation(AnnotationSpec.builder(JsonSubTypes.class)
                    .addMember("value", "$L", collect)
                    .build());
        }

        //This class extends interfaces
        getExtendsList(schema).stream().map(ClassName::bestGuess).forEach(classBuilder::addSuperinterface);

        //Add properties
        Map<String, Schema> schemaMap = schema.getProperties();
        if (schemaMap != null) {
            for (Map.Entry<String, Schema> entry : schemaMap.entrySet()) {
                if (!entry.getKey().matches("[a-z][a-z_0-9]*")) throw new IllegalStateException(
                        String.format("Property '%s' of schema '%s' is not in snake case",
                                entry.getKey(), name)
                );
                if (schema.getDiscriminator() != null
                        && entry.getKey().equals(schema.getDiscriminator().getPropertyName())) {
                    //Skip the descriminator property
                    continue;
                }
                TypeName typeName = defineJavaType(entry.getValue(), openAPI, classBuilder);
                FieldSpec.Builder fieldBuilder = FieldSpec.builder(
                        typeName,
                        CaseUtils.snakeToCamel(entry.getKey()), Modifier.PRIVATE);
                if (typeName instanceof ClassName && "ZonedDateTime"
                        .equals(((ClassName) typeName).simpleName())) {
                    fieldBuilder.addAnnotation(AnnotationSpec.builder(
                                            ClassName.get(JsonDeserialize.class))
                                    .addMember("using", "ZonedDateTimeDeserializer.class").build())
                            .addAnnotation(AnnotationSpec.builder(
                                            ClassName.get(JsonSerialize.class))
                                    .addMember("using", "ZonedDateTimeSerializer.class").build());
                    ensureJsonZonedDateTimeDeserializer();
                }
                FieldSpec fieldSpec = fieldBuilder.build();
                classBuilder.addField(fieldSpec);
            }
        }
        return classBuilder.build();
    }

    @Override
    TypeSpec getEnum(String name, Schema<?> schema, OpenAPI openAPI) {
        TypeSpec.Builder classBuilder = TypeSpec.enumBuilder(name).addModifiers(Modifier.PUBLIC);
        for (Object val : schema.getEnum()) {
            classBuilder.addEnumConstant(val.toString());
        }
        return classBuilder.build();
    }
}

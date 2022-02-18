/*
 *
 *  Copyright 2020 Netflix, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.netflix.graphql.dgs.codegen.generators.java

import com.netflix.graphql.dgs.client.codegen.BaseProjectionNode
import com.netflix.graphql.dgs.client.codegen.BaseSubProjectionNode
import com.netflix.graphql.dgs.client.codegen.GraphQLQuery
import com.netflix.graphql.dgs.codegen.*
import com.netflix.graphql.dgs.codegen.generators.shared.ClassnameShortener
import com.netflix.graphql.dgs.codegen.generators.shared.CodeGeneratorUtils.capitalized
import com.netflix.graphql.dgs.codegen.generators.shared.SchemaExtensionsUtils
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.CodeBlock
import com.squareup.javapoet.FieldSpec
import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.ParameterSpec
import com.squareup.javapoet.ParameterizedTypeName
import com.squareup.javapoet.TypeSpec
import graphql.introspection.Introspection.TypeNameMetaFieldDef
import graphql.language.*
import graphql.language.Field
import javax.lang.model.element.Modifier
import kotlin.collections.ArrayList
import kotlin.collections.HashSet
import kotlin.streams.toList

class ClientApiGenerator(private val config: CodeGenConfig, private val document: Document) {
    private val generatedClasses = mutableSetOf<String>()
    private val typeUtils = TypeUtils(getDatatypesPackageName(), config, document)

    fun generate(definition: ObjectTypeDefinition): CodeGenResult {

        val operationDefinition = getOperationDefinition(document, definition.name)
        var selectedFields = if (operationDefinition != null) { definition.fieldDefinitions().filterSelectedFields(operationDefinition, config) } else definition.fieldDefinitions()
        return selectedFields.filterIncludedInConfig(definition.name, config).filterSkipped().map {
            val javaFile = createQueryClass(it, definition.name)
            val selectedField = if (operationDefinition != null) { getSelectionSetField(operationDefinition.selectionSet?.getSelectionsOfType(Field::class.java)!!, it.name)} else null
            val rootProjection =
                it.type.findTypeDefinition(document, true)?.let { typeDefinition -> createRootProjection(typeDefinition, it.name.capitalized(), selectedField, operationDefinition?.name) }
                    ?: CodeGenResult()
            CodeGenResult(javaQueryTypes = listOf(javaFile)).merge(rootProjection)
        }.fold(CodeGenResult()) { total, current -> total.merge(current) }
    }

    fun generateEntities(definitions: List<ObjectTypeDefinition>): CodeGenResult {
        if (config.skipEntityQueries) {
            return CodeGenResult()
        }

        var entitiesRootProjection = CodeGenResult()
        // generate for federation types, if present
        val federatedTypes = definitions.filter { it.hasDirective("key") }
        if (federatedTypes.isNotEmpty()) {
            // create entities root projection
            entitiesRootProjection = createEntitiesRootProjection(federatedTypes)
        }
        return CodeGenResult().merge(entitiesRootProjection)
    }

    private fun createQueryClass(it: FieldDefinition, operation: String): JavaFile {
        val javaType = TypeSpec.classBuilder("${it.name.capitalized()}GraphQLQuery")
            .addModifiers(Modifier.PUBLIC).superclass(ClassName.get(GraphQLQuery::class.java))

        if (it.description != null) {
            javaType.addJavadoc(it.description.sanitizeJavaDoc())
        }
        javaType.addMethod(
            MethodSpec.methodBuilder("getOperationName")
                .addModifiers(Modifier.PUBLIC)
                .returns(String::class.java)
                .addAnnotation(Override::class.java)
                .addCode(
                    """
                    | return "${it.name}";
                    |                
                    """.trimMargin()
                ).build()
        )

        val setType = ClassName.get(Set::class.java)
        val setOfStringType = ParameterizedTypeName.get(setType, ClassName.get(String::class.java))

        val builderClass = TypeSpec.classBuilder("Builder").addModifiers(Modifier.STATIC, Modifier.PUBLIC)
            .addMethod(
                MethodSpec.methodBuilder("build")
                    .addModifiers(Modifier.PUBLIC)
                    .returns(ClassName.get("", "${it.name.capitalized()}GraphQLQuery"))
                    .addCode(
                        if (it.inputValueDefinitions.isNotEmpty())
                            """
                            |return new ${it.name.capitalized()}GraphQLQuery(${it.inputValueDefinitions.joinToString(", ") { ReservedKeywordSanitizer.sanitize(it.name) }}, fieldsSet);
                            |         
                            """.trimMargin() else
                            """
                            |return new ${it.name.capitalized()}GraphQLQuery();                                     
                            """.trimMargin()
                    )
                    .build()
            ).addField(FieldSpec.builder(setOfStringType, "fieldsSet", Modifier.PRIVATE).initializer("new \$T<>()", ClassName.get(HashSet::class.java)).build())

        val constructorBuilder = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
        constructorBuilder.addCode(
            """
            |super("${operation.lowercase()}");
            |
            """.trimMargin()
        )

        it.inputValueDefinitions.forEach { inputValue ->
            val findReturnType = TypeUtils(getDatatypesPackageName(), config, document).findReturnType(inputValue.type)
            val methodBuilder = MethodSpec.methodBuilder(ReservedKeywordSanitizer.sanitize(inputValue.name))
                .addParameter(findReturnType, ReservedKeywordSanitizer.sanitize(inputValue.name))
                .returns(ClassName.get("", "Builder"))
                .addModifiers(Modifier.PUBLIC)
                .addCode(
                    """
                    |this.${ReservedKeywordSanitizer.sanitize(inputValue.name)} = ${ReservedKeywordSanitizer.sanitize(inputValue.name)};
                    |this.fieldsSet.add("${inputValue.name}");
                    |return this;
                    """.trimMargin()
                )

            if (inputValue.description != null) {
                methodBuilder.addJavadoc(inputValue.description.sanitizeJavaDoc())
            }
            builderClass.addMethod(methodBuilder.build())
                .addField(findReturnType, ReservedKeywordSanitizer.sanitize(inputValue.name), Modifier.PRIVATE)

            constructorBuilder.addParameter(findReturnType, ReservedKeywordSanitizer.sanitize(inputValue.name))

            if (findReturnType.isPrimitive) {
                constructorBuilder.addCode(
                    """
                    |getInput().put("${inputValue.name}", ${ReservedKeywordSanitizer.sanitize(inputValue.name)});                   
                    """.trimMargin()
                )
            } else {
                constructorBuilder.addCode(
                    """
                    |if (${inputValue.name} != null || fieldsSet.contains("${inputValue.name}")) {
                    |    getInput().put("${inputValue.name}", ${ReservedKeywordSanitizer.sanitize(inputValue.name)});
                    |}
                    """.trimMargin()
                )
            }
        }

        if (it.inputValueDefinitions.size > 0) {
            constructorBuilder.addParameter(setOfStringType, "fieldsSet")
        }

        javaType.addMethod(constructorBuilder.build())

        // No-arg constructor
        if (it.inputValueDefinitions.size > 0) {
            javaType.addMethod(
                MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC)
                    .addStatement("super(\"${operation.lowercase()}\")")
                    .build()
            )
        }

        javaType.addMethod(
            MethodSpec.methodBuilder("newRequest")
                .addModifiers(Modifier.STATIC, Modifier.PUBLIC)
                .returns(ClassName.get("", "Builder"))
                .addCode("return new Builder();\n")
                .build()
        )
        javaType.addType(builderClass.build())
        return JavaFile.builder(getPackageName(), javaType.build()).build()
    }

    private fun createRootProjection(type: TypeDefinition<*>, prefix: String, selectedParent: Field?, operationName: String?): CodeGenResult {
        val clazzName = "${prefix}ProjectionRoot"
        val javaType = TypeSpec.classBuilder(clazzName)
            .addModifiers(Modifier.PUBLIC).superclass(ClassName.get(BaseProjectionNode::class.java))

        if (generatedClasses.contains(clazzName)) return CodeGenResult() else generatedClasses.add(clazzName)

        val fieldDefinitions = type.fieldDefinitions() + document.definitions.filterIsInstance<ObjectTypeExtensionDefinition>().filter { it.name == type.name }.flatMap { it.fieldDefinitions }

        val codeGenResult = fieldDefinitions
            .filterSelectedFields(selectedParent, config)
            .filterSkipped()
            .mapNotNull {
                val typeDefinition = it.type.findTypeDefinition(
                    document,
                    excludeExtensions = true,
                    includeBaseTypes = it.inputValueDefinitions.isNotEmpty(),
                    includeScalarTypes = it.inputValueDefinitions.isNotEmpty()
                )
                if (typeDefinition != null) it to typeDefinition else null
            }
            .map { (fieldDef, typeDef) ->
                val projectionName = "${prefix}_${fieldDef.name.capitalized()}Projection"

                if (typeDef !is ScalarTypeDefinition) {
                    val noArgMethodBuilder = MethodSpec.methodBuilder(ReservedKeywordSanitizer.sanitize(fieldDef.name))
                        .returns(ClassName.get(getPackageName(), projectionName))
                        .addCode(
                            """
                            |$projectionName projection = new $projectionName(this, this);    
                            |getFields().put("${fieldDef.name}", projection);
                            |return projection;
                            """.trimMargin()
                        )
                        .addModifiers(Modifier.PUBLIC)
                    javaType.addMethod(noArgMethodBuilder.build())
                }

                if (fieldDef.inputValueDefinitions.isNotEmpty()) {
                    addFieldSelectionMethodWithArguments(fieldDef, projectionName, javaType, projectionRoot = "this")
                }

                val processedEdges = mutableSetOf<Pair<String, String>>()
                processedEdges.add(typeDef.name to type.name)
                val selectedField = selectedParent?.selectionSet?.getSelectionsOfType(Field::class.java)?.find {it.name == fieldDef.name}
                createSubProjection(typeDef, javaType.build(), javaType.build(), "${prefix}_${fieldDef.name.capitalized()}", selectedField, processedEdges, 1, operationName)
            }
            .fold(CodeGenResult()) { total, current -> total.merge(current) }

        fieldDefinitions.filterSelectedFields(selectedParent, config).filterSkipped().forEach {

            val objectTypeDefinition = it.type.findTypeDefinition(document)
            if (objectTypeDefinition == null) {
                javaType.addMethod(
                    MethodSpec.methodBuilder(ReservedKeywordSanitizer.sanitize(it.name))
                        .returns(ClassName.get(getPackageName(), javaType.build().name))
                        .addCode(
                            """
                            |getFields().put("${it.name}", null);
                            |return this;
                            """.trimMargin()
                        )
                        .addModifiers(Modifier.PUBLIC)
                        .build()
                )
            }
        }

        val concreteTypesResult = createConcreteTypes(type, javaType.build(), javaType, prefix, selectedParent, mutableSetOf(), 0, operationName)
        val unionTypesResult = createUnionTypes(type, javaType, javaType.build(), prefix, selectedParent, mutableSetOf(), 0, operationName)
        val javaFile = JavaFile.builder(getPackageName(), javaType.build()).build()

        var projectionDataTypesResult =
            when (type) {
                is ObjectTypeDefinition -> ClientProjectionDataTypeGenerator(config, document, operationName)
                    .generate(type, SchemaExtensionsUtils.findTypeExtensions(type.name, document.definitions), selectedParent)
                is InterfaceTypeDefinition -> InterfaceGenerator(config, document, "${config.packageNameTypes}.${operationName?.lowercase()?: "operation"}")
                    .generate(type, SchemaExtensionsUtils.findInterfaceExtensions(type.name, document.definitions), selectedParent)
                else -> CodeGenResult()
            }

        return CodeGenResult(clientProjections = listOf(javaFile)).merge(codeGenResult).merge(concreteTypesResult).merge(unionTypesResult).merge(projectionDataTypesResult)
    }

    private fun addFieldSelectionMethodWithArguments(
        fieldDefinition: FieldDefinition,
        projectionName: String,
        javaType: TypeSpec.Builder,
        projectionRoot: String
    ): TypeSpec.Builder? {
        val methodBuilder = MethodSpec.methodBuilder(ReservedKeywordSanitizer.sanitize(fieldDefinition.name))
            .returns(ClassName.get(getPackageName(), projectionName))
            .addCode(
                """
                |$projectionName projection = new $projectionName(this, $projectionRoot);    
                |getFields().put("${fieldDefinition.name}", projection);
                |getInputArguments().computeIfAbsent("${fieldDefinition.name}", k -> new ${'$'}T<>());                      
                |${
                fieldDefinition.inputValueDefinitions.joinToString("\n") { input ->
                    """
                     |InputArgument ${input.name}Arg = new InputArgument("${input.name}", ${input.name});
                     |getInputArguments().get("${fieldDefinition.name}").add(${input.name}Arg);
                     """.trimMargin()
                }
                }
                |return projection;
                """.trimMargin(),
                ArrayList::class.java
            )
            .addModifiers(Modifier.PUBLIC)

        fieldDefinition.inputValueDefinitions.forEach { input ->
            methodBuilder.addParameter(ParameterSpec.builder(typeUtils.findReturnType(input.type), input.name).build())
        }
        return javaType.addMethod(methodBuilder.build())
    }

    private fun createEntitiesRootProjection(federatedTypes: List<ObjectTypeDefinition>): CodeGenResult {
        val clazzName = "EntitiesProjectionRoot"
        val javaType = TypeSpec.classBuilder(clazzName)
            .addModifiers(Modifier.PUBLIC).superclass(ClassName.get(BaseProjectionNode::class.java))

        if (generatedClasses.contains(clazzName)) return CodeGenResult() else generatedClasses.add(clazzName)
        val codeGenResult = federatedTypes.map { objTypeDef ->
            javaType.addMethod(
                MethodSpec.methodBuilder("on${objTypeDef.name}")
                    .addModifiers(Modifier.PUBLIC)
                    .returns(ClassName.get(getPackageName(), "Entities${objTypeDef.name.capitalized()}KeyProjection"))
                    .addCode(
                        """
                        | Entities${objTypeDef.name.capitalized()}KeyProjection fragment = new Entities${objTypeDef.name.capitalized()}KeyProjection(this, this);
                        | getFragments().add(fragment);
                        | return fragment;
                        """.trimMargin()
                    )
                    .build()
            )
            val processedEdges = mutableSetOf<Pair<String, String>>()
            createFragment(objTypeDef, javaType.build(), javaType.build(), "Entities${objTypeDef.name.capitalized()}Key", null, processedEdges, 0, "")
        }.fold(CodeGenResult()) { total, current -> total.merge(current) }

        val javaFile = JavaFile.builder(getPackageName(), javaType.build()).build()
        return CodeGenResult(clientProjections = listOf(javaFile)).merge(codeGenResult)
    }

    private fun createConcreteTypes(type: TypeDefinition<*>, root: TypeSpec, javaType: TypeSpec.Builder, prefix: String, selectedParent: Field?, processedEdges: Set<Pair<String, String>>, queryDepth: Int, operationName: String?): CodeGenResult {
        return if (type is InterfaceTypeDefinition) {

            val concreteTypes = document.getDefinitionsOfType(ObjectTypeDefinition::class.java).filter {
                it.implements.filterIsInstance<NamedNode<*>>().any { iface -> iface.name == type.name }
            }

            var projectionDataTypesResult = CodeGenResult()
            concreteTypes.filterSelectedConcreteTypes(selectedParent, config).map {
                projectionDataTypesResult = ClientProjectionDataTypeGenerator(config, document, operationName)
                    .generate(it, SchemaExtensionsUtils.findTypeExtensions(it.name, document.definitions), selectedParent)
                addFragmentProjectionMethod(javaType, root, prefix, selectedParent, it, processedEdges, queryDepth, operationName)
            }.fold(CodeGenResult()) { total, current -> total.merge(current) }.merge(projectionDataTypesResult)
        } else {
            CodeGenResult()
        }
    }

    private fun createUnionTypes(type: TypeDefinition<*>, javaType: TypeSpec.Builder, rootType: TypeSpec, prefix: String, selectedParent: Field?, processedEdges: Set<Pair<String, String>>, queryDepth: Int, operationName: String?): CodeGenResult {
        return if (type is UnionTypeDefinition) {
            val memberTypes = type.memberTypes.mapNotNull { it.findTypeDefinition(document, true) }.toList()
            memberTypes.filterSelectedUnionTypes(selectedParent, config).map {
                addFragmentProjectionMethod(javaType, rootType, prefix, selectedParent, it, processedEdges, queryDepth, operationName)
            }.fold(CodeGenResult()) { total, current -> total.merge(current) }
        } else {
            CodeGenResult()
        }
    }

    private fun addFragmentProjectionMethod(javaType: TypeSpec.Builder, rootType: TypeSpec, prefix: String, selectedParent: Field?, it: TypeDefinition<*>, processedEdges: Set<Pair<String, String>>, queryDepth: Int, operationName: String?): CodeGenResult {
        val rootRef = if (javaType.build().name == rootType.name) "this" else "getRoot()"

        val projectionName = "${prefix}_${it.name.capitalized()}Projection"
        javaType.addMethod(
            MethodSpec.methodBuilder("on${it.name}")
                .addModifiers(Modifier.PUBLIC)
                .returns(ClassName.get(getPackageName(), projectionName))
                .addCode(
                    """
                    |$projectionName fragment = new $projectionName(this, $rootRef);
                    |getFragments().add(fragment);
                    |return fragment;
                    """.trimMargin()
                )
                .build()
        )

        val selectedField = selectedParent?.selectionSet?.getSelectionsOfType(Field::class.java)?.find {iter -> iter.name == it.name}
        return createFragment(it as ObjectTypeDefinition, javaType.build(), rootType, "${prefix}_${it.name.capitalized()}", selectedField, processedEdges, queryDepth, operationName)
    }

    private fun createFragment(type: ObjectTypeDefinition, parent: TypeSpec, root: TypeSpec, prefix: String, selectedParent: Field?, processedEdges: Set<Pair<String, String>>, queryDepth: Int, operationName: String?): CodeGenResult {
        val subProjection = createSubProjectionType(type, parent, root, prefix, selectedParent, processedEdges, queryDepth, operationName)
            ?: return CodeGenResult()
        val javaType = subProjection.first
        val codeGenResult = subProjection.second

        // We don't need the typename added for fragments in the entities' projection.
        // This affects deserialization when use directly with generated classes
        if (prefix != "Entities${type.name.capitalized()}Key") {
            javaType.addInitializerBlock(
                CodeBlock.builder()
                    .addStatement("getFields().put(\$S, null)", TypeNameMetaFieldDef.name)
                    .build()
            )
        }

        javaType.addMethod(
            MethodSpec.methodBuilder("toString")
                .returns(ClassName.get(String::class.java))
                .addAnnotation(Override::class.java)
                .addModifiers(Modifier.PUBLIC)
                .addCode(
                    """
                    |StringBuilder builder = new StringBuilder();
                    |builder.append("... on ${type.name} {");
                    |getFields().forEach((k, v) -> {
                    |    builder.append(" ").append(k);
                    |    if(v != null) {
                    |        builder.append(" ").append(v.toString());
                    |    }
                    |});
                    |builder.append("}");
                    | 
                    |return builder.toString();
                    """.trimMargin()
                )
                .build()
        )

        val javaFile = JavaFile.builder(getPackageName(), javaType.build()).build()
        return CodeGenResult(clientProjections = listOf(javaFile)).merge(codeGenResult)
    }

    private fun createSubProjection(type: TypeDefinition<*>, parent: TypeSpec, root: TypeSpec, prefix: String, selectedParent: Field?, processedEdges: Set<Pair<String, String>>, queryDepth: Int, operationName: String?): CodeGenResult {
        val subProjection = createSubProjectionType(type, parent, root, prefix, selectedParent, processedEdges, queryDepth, operationName)
            ?: return CodeGenResult()
        val javaType = subProjection.first
        val codeGenResult = subProjection.second

        val javaFile = JavaFile.builder(getPackageName(), javaType.build()).build()
        return CodeGenResult(clientProjections = listOf(javaFile)).merge(codeGenResult)
    }

    private fun createSubProjectionType(type: TypeDefinition<*>, parent: TypeSpec, root: TypeSpec, prefix: String, selectedParent: Field?, processedEdges: Set<Pair<String, String>>, queryDepth: Int, operationName: String?): Pair<TypeSpec.Builder, CodeGenResult>? {
        val className = ClassName.get(BaseSubProjectionNode::class.java)
        val clazzName = "${prefix}Projection"
        if (generatedClasses.contains(clazzName)) return null else generatedClasses.add(clazzName)
        val javaType = TypeSpec.classBuilder(clazzName)
            .addModifiers(Modifier.PUBLIC)
            .superclass(ParameterizedTypeName.get(className, ClassName.get(getPackageName(), parent.name), ClassName.get(getPackageName(), root.name)))
            .addMethod(
                MethodSpec.constructorBuilder()
                    .addModifiers(Modifier.PUBLIC)
                    .addParameter(ParameterSpec.builder(ClassName.get(getPackageName(), parent.name), "parent").build())
                    .addParameter(ParameterSpec.builder(ClassName.get(getPackageName(), root.name), "root").build())
                    .addCode("""super(parent, root, java.util.Optional.of("${type.name}"));""")
                    .build()
            )

        val fieldDefinitions = type.fieldDefinitions() + document.definitions
            .filterIsInstance<ObjectTypeExtensionDefinition>()
            .filter { it.name == type.name }
            .flatMap { it.fieldDefinitions }

        var projectionDataTypesResult =
            when (type) {
                is ObjectTypeDefinition -> ClientProjectionDataTypeGenerator(config, document, operationName)
                    .generate(type, SchemaExtensionsUtils.findTypeExtensions(type.name, document.definitions), selectedParent)
                is InterfaceTypeDefinition -> InterfaceGenerator(config, document, "${config.packageNameTypes}.${operationName?.lowercase()?: "operation"}")
                    .generate(type, SchemaExtensionsUtils.findInterfaceExtensions(type.name, document.definitions), selectedParent)
                else -> CodeGenResult()
            }

        val codeGenResult = if (queryDepth < config.maxProjectionDepth || config.maxProjectionDepth == -1) {
            fieldDefinitions
                .filterSelectedFields(selectedParent, config)
                .filterSkipped()
                .mapNotNull {
                    val typeDefinition = it.type.findTypeDefinition(document, true)
                    if (typeDefinition != null) it to typeDefinition else null
                }
                .filter { (_, typeDef) -> (typeDef.name to type.name) !in processedEdges }
                .map { (fieldDef, typeDef) ->
                    val projectionName = "${truncatePrefix(prefix)}_${fieldDef.name.capitalized()}Projection"
                    val methodName = ReservedKeywordSanitizer.sanitize(fieldDef.name)
                    javaType.addMethod(
                        MethodSpec.methodBuilder(methodName)
                            .returns(ClassName.get(getPackageName(), projectionName))
                            .addCode(
                                """
                                    | $projectionName projection = new $projectionName(this, getRoot());
                                    | getFields().put("${fieldDef.name}", projection);
                                    | return projection;
                                    """.trimMargin()
                            )
                            .addModifiers(Modifier.PUBLIC)
                            .build()
                    )

                    if (fieldDef.inputValueDefinitions.isNotEmpty()) {
                        addFieldSelectionMethodWithArguments(fieldDef, projectionName, javaType, projectionRoot = "getRoot()")
                    }

                    val updatedProcessedEdges = processedEdges.toMutableSet()
                    updatedProcessedEdges.add(typeDef.name to type.name)
                    val selectedField = selectedParent?.selectionSet?.getSelectionsOfType(Field::class.java)?.find {it.name == fieldDef.name}
                    createSubProjection(typeDef, javaType.build(), root, "${truncatePrefix(prefix)}_${fieldDef.name.capitalized()}", selectedField, updatedProcessedEdges, queryDepth + 1, operationName)
                }
                .fold(CodeGenResult()) { total, current -> total.merge(current) }
        } else CodeGenResult()

        fieldDefinitions
            .filterSelectedFields(selectedParent, config)
            .filterSkipped()
            .forEach {
                val objectTypeDefinition = it.type.findTypeDefinition(document)
                if (objectTypeDefinition == null) {
                    javaType.addMethod(
                        MethodSpec.methodBuilder(ReservedKeywordSanitizer.sanitize(it.name))
                            .returns(ClassName.get(getPackageName(), javaType.build().name))
                            .addCode(
                                """
                                |getFields().put("${it.name}", null);
                                |return this;
                                """.trimMargin()
                            )
                            .addModifiers(Modifier.PUBLIC)
                            .build()
                    )

                    if (it.inputValueDefinitions.isNotEmpty()) {
                        val methodWithInputArgumentsBuilder = MethodSpec.methodBuilder(ReservedKeywordSanitizer.sanitize(it.name))
                            .returns(ClassName.get(getPackageName(), javaType.build().name))
                            .addCode(
                                """
                                |getFields().put("${it.name}", null);
                                |getInputArguments().computeIfAbsent("${it.name}", k -> new ${'$'}T<>());
                                |${
                                it.inputValueDefinitions.joinToString("\n") { input ->
                                    """
                                     |InputArgument ${input.name}Arg = new InputArgument("${input.name}", ${input.name});
                                     |getInputArguments().get("${it.name}").add(${input.name}Arg);
                                     """.trimMargin()
                                }}
                                |return this;
                                """.trimMargin(),
                                ArrayList::class.java
                            )
                            .addModifiers(Modifier.PUBLIC)

                        it.inputValueDefinitions.forEach { input ->
                            methodWithInputArgumentsBuilder.addParameter(ParameterSpec.builder(typeUtils.findReturnType(input.type), input.name).build())
                        }

                        javaType.addMethod(methodWithInputArgumentsBuilder.build())
                    }
                }
            }

        val concreteTypesResult = createConcreteTypes(type, root, javaType, prefix, selectedParent, processedEdges, queryDepth, operationName)
        val unionTypesResult = createUnionTypes(type, javaType, root, prefix, selectedParent, processedEdges, queryDepth, operationName)

        return javaType to codeGenResult.merge(concreteTypesResult).merge(unionTypesResult).merge(projectionDataTypesResult)
    }



    private fun truncatePrefix(prefix: String): String {
        return if (config.shortProjectionNames) ClassnameShortener.shorten(prefix) else prefix
    }

    private fun getPackageName(): String {
        return config.packageNameClient
    }

    private fun getDatatypesPackageName(): String {
        return config.packageNameTypes
    }
}

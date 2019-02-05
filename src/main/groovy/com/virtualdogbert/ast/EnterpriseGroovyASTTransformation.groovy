/**
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */

package com.virtualdogbert.ast

import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import org.codehaus.groovy.ast.*
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.transform.AbstractASTTransformation
import org.codehaus.groovy.transform.GroovyASTTransformation

/**
 * A Global AST transform for applying static compilation and enforcing, based on config.
 */
@CompileStatic
@GroovyASTTransformation(phase = CompilePhase.SEMANTIC_ANALYSIS)
class EnterpriseGroovyASTTransformation extends AbstractASTTransformation {
    static final String       conventionsFile     = "conventions.groovy"
    static final String       extensions          = 'extensions'
    static final List<String> excludedAnnotations = [CompileStatic.name, CompileDynamic.name]
    static final List<String> dynamicAnnotation   = [CompileDynamic.name]

    static boolean        setupConfig                  = true
    static boolean        disableDynamicCompile        = false
    static boolean        limitCompileStaticExtensions = false
    static boolean        defAllowed                   = true
    static boolean        skipDefaultPackage           = false
    static List<String>   dynamicCompileWhiteList      = []
    static List<String>   compileStaticExtensionsList  = []
    static ListExpression compileStaticExtensions


    @Override
    void visit(ASTNode[] nodes, SourceUnit sourceUnit) {

        if (sourceUnit.name == 'embedded_script_in_groovy_Ant_task' ||
            sourceUnit.name.startsWith('Script') ||
            sourceUnit.name.startsWith('script') ||
            sourceUnit.name.startsWith('GStringTemplateScript')
        ) {
            return
        }

        this.sourceUnit = sourceUnit

        if (setupConfig) {
            setupConfig = false
            if (!setupConfiguration()) {
                return
            }
        }

        List<ClassNode> classes = sourceUnit.getAST().getClasses()

        for (ClassNode classNode : classes) {

            if (!inWhiteList(classNode) && (!getSkipDefaultPackage() || classNode.packageName)) {

                for (FieldNode fieldNode : classNode.fields) {
                    if (fieldNode.isDynamicTyped() && !getDefAllowed()) {
                        addError("def is not allowed for variables.", fieldNode)
                    }
                }

                addCompileStatic(classNode)

                if (getDisableDynamicCompile() || getLimitCompileStaticExtensions()) {
                    if (getDisableDynamicCompile() && !inWhiteList(classNode) & hasAnnotation(classNode, getDynamicAnnotation())) {
                        addError('Dynamic Compilation is not allowed for this class.', classNode)
                    }

                    if (getLimitCompileStaticExtensions() && hasOtherExtensions(classNode)) {
                        addError("Compile Static extensions are limited to: ${getCompileStaticExtensionsList()}", classNode)
                    }

                    for (MethodNode methodNode : classNode.methods) {

                        if (methodNode.isDynamicReturnType() && !getDefAllowed()) {
                            addError("def is not allowed for methods.", methodNode)
                        }

                        if (getDisableDynamicCompile() && !inWhiteList(classNode) && hasAnnotation(methodNode, getDynamicAnnotation())) {
                            addError('Dynamic Compilation is not allowed for this method.', methodNode)
                        }

                        if (getLimitCompileStaticExtensions() && hasOtherExtensions(methodNode)) {
                            addError("Compile Static extensions are limited to: ${getCompileStaticExtensionsList()}", methodNode)
                        }

                        for (Parameter parameter : methodNode.parameters) {
                            if (parameter.isDynamicTyped() && !getDefAllowed()) {
                                addError('Dynamically types parameters are not allowed.', parameter)
                            }
                        }
                    }
                }
            }
        }

    }

    /**
     * Sets up the conventions configurations for static compilation.
     */
    static boolean setupConfiguration() {
        ConfigSlurper configSlurper = new ConfigSlurper()
        File configFile = new File(conventionsFile)

        if (!configFile.exists()) {
            return false
        }

        ConfigObject config = (ConfigObject) configSlurper.parse(configFile?.toURI()?.toURL())?.conventions

        if (!config) {
            return false
        }

        disableDynamicCompile = config.disableDynamicCompile != null ? config.disableDynamicCompile : false
        dynamicCompileWhiteList = (List<String>) config.dynamicCompileWhiteList

        compileStaticExtensionsList = (List<String>) config.compileStaticExtensions
        limitCompileStaticExtensions = config.limitCompileStaticExtensions != null ? config.limitCompileStaticExtensions : false

        defAllowed = config.defAllowed != null ? config.defAllowed : true
        skipDefaultPackage = config.skipDefaultPackage != null ? config.skipDefaultPackage : false

        ListExpression extensions = new ListExpression()

        for (String extension : compileStaticExtensionsList) {
            extensions.addExpression(new ConstantExpression(extension))
        }

        compileStaticExtensions = extensions

        return true
    }

    /**
     * Checks a class node to see if it has an annotation from a list of excludedAnnotations.
     *
     * @param classNode The method node to check.
     * @param annotations The list of excludedAnnotations to check against.
     *
     * @return true if the class node as an annotation is the list to check, else false
     */
    static boolean hasAnnotation(ClassNode classNode, List<String> annotations) {
        classNode.annotations*.classNode.name.any { String annotation -> annotation in annotations }
    }

    /**
     * Checks a method node to see if it has an annotation from a list of excludedAnnotations.
     *
     * @param methodNode The method node to check.
     * @param annotations The list of excludedAnnotations to check against.
     *
     * @return true if the method node as an annotation is the list to check, else false
     */
    static boolean hasAnnotation(MethodNode methodNode, List<String> annotations) {
        methodNode.annotations*.classNode.name.any { String annotation -> annotation in annotations }
    }

    /**
     * Checks to see if a class is on the white list for being exempt for static compilation.
     *
     * @param classNode the class node of the class to check.
     *
     * @return true if the class node or part of its package is contained in the white list, false otherwise.
     */
    static boolean inWhiteList(ClassNode classNode) {
        getDynamicCompileWhiteList().any { String path -> classNode.name.contains(path) }
    }


    /**
     * Checks classes for the use of CompileStatic, and checks to see if it uses extensions that are not on the
     * extensions that are whitelisted.
     *
     * @param classNode the class node to check.
     *
     * @return true if the class node has @CompileStatic, and has extensions not on the whitelist, false otherwise.
     */
    static boolean hasOtherExtensions(ClassNode classNode) {
        for (AnnotationNode annotation : classNode.annotations) {

            if (annotation.classNode.name == CompileStatic.name) {
                ListExpression extensionExpressions = (ListExpression) annotation.members[getExtensions()]

                for (Expression expression : extensionExpressions.expressions) {
                    if (!getCompileStaticExtensionsList().contains(((ConstantExpression) expression).text)) {
                        return true
                    }
                }
            }
        }

        return false
    }

    /**
     * Checks methods for the use of CompileStatic, and checks to see if it uses extensions that are not on the
     * extensions that are whitelisted.
     *
     * @param methodNode the method node to check.
     *
     * @return true if the method node has @CompileStatic, and has extensions not on the whitelist, false otherwise.
     */
    static boolean hasOtherExtensions(MethodNode methodNode) {
        for (AnnotationNode annotation : methodNode.annotations) {

            if (annotation.classNode.name == CompileStatic.name) {
                ListExpression extensionExpressions = (ListExpression) annotation.members[getExtensions()]

                for (Expression expression : extensionExpressions.expressions) {
                    if (!compileStaticExtensionsList.contains(((ConstantExpression) expression).text)) {
                        return true
                    }
                }
            }
        }

        return false
    }


    /**
     * Adds @CompileStatic to the class node with the extensions from the white list.
     *
     * @param classNode The class node to add @CompileStatic to.
     */
    static void addCompileStatic(ClassNode classNode) {
        if (!hasAnnotation(classNode, getExcludedAnnotations())) {
            AnnotationNode classAnnotation = new AnnotationNode(new ClassNode(CompileStatic))

            if(getExtensions()) {
                classAnnotation.addMember(getExtensions(), getCompileStaticExtensions())
            }

            classNode.addAnnotation(classAnnotation)
        }
    }

}

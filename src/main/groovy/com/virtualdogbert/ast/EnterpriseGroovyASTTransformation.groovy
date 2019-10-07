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
import groovy.transform.TypeCheckingMode
import org.codehaus.groovy.ast.*
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
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
    static final String       extensions          = 'extensions'
    static final List<String> excludedAnnotations = [CompileStatic.name, CompileDynamic.name]
    static final List<String> dynamicAnnotation   = [CompileDynamic.name]

    static boolean        setupConfig                  = true

    static boolean        disable                      = false
    static boolean        whiteListScripts             = true
    static boolean        disableDynamicCompile        = false
    static boolean        limitCompileStaticExtensions = false
    static boolean        defAllowed                   = true
    static boolean        skipDefaultPackage           = false
    static List<String>   dynamicCompileWhiteList      = []
    static List<String>   compileStaticExtensionsList  = []
    static ListExpression compileStaticExtensions      = null


    @Override
    void visit(ASTNode[] nodes, SourceUnit sourceUnit) {
        //This is required by AbstractASTTransformation, or else when calling addError you'll get a NPE.
        this.sourceUnit = sourceUnit

        if (setupConfig) {
            try {
                setupConfiguration()
                setupConfig = false
            } catch (Exception e) {
                println "WARN - Enterprise Groovy config can't be parsed, falling back to defaults"
                e.printStackTrace()
            }
        }

        //Break out if disabled or the source is a dynamic script, and whitelisting of scripts is true.
        if (disable || (whiteListScripts && !sourceUnit?.getConfiguration()?.getTargetDirectory())) {
            return
        }

        List<ClassNode> classes = sourceUnit.getAST().getClasses()

        for (ClassNode classNode : classes) {

            if (!inWhiteList(classNode) && (!getSkipDefaultPackage() || classNode.packageName)) {


                addCompileStatic(classNode)

                enforcementChecks(classNode)
            }
        }
    }

    /**
     * Checks the class node if dynamic compilation is disabled, or limit compile static extensions, is enabled, or if def is not allowed,
     * by the configuration.
     *
     * @param classNode the class node to check.
     */
    void enforcementChecks(ClassNode classNode) {
        //If none of the flags for enforcement are set then there is no reason to do the checks
        if (getDisableDynamicCompile() || getLimitCompileStaticExtensions() || !getDefAllowed()) {

            if (getDisableDynamicCompile() && !inWhiteList(classNode) &&
                (hasAnnotation(classNode, getDynamicAnnotation()) || hasCompileStaticSkip(classNode))) {
                addError('Dynamic Compilation is not allowed for this class.', classNode)
            }

            if (getLimitCompileStaticExtensions() && hasOtherExtensions(classNode)) {
                addError("Compile Static extensions are limited to: ${getCompileStaticExtensionsList()}", classNode)
            }

            checkFieldNodes(classNode.fields)
            checkMethodNodes(classNode)
        }
    }

    /**
     * Checks the field nodes to see if they are dynamically types, i.e. have def.
     *
     * @param fields the field nodes to check, for dynamic typing.
     */
    void checkFieldNodes(List<FieldNode> fields) {
        for (FieldNode fieldNode : fields) {
            if (fieldNode.isDynamicTyped() && !getDefAllowed()) {
                addError("def is not allowed for variables.", fieldNode)
            }
        }
    }

    /**
     * Check the  method nodes for dynamic typing.
     *
     * @param classNode the class node to check the methods, for.dynamic typing.
     */
    void checkMethodNodes(ClassNode classNode) {
        for (MethodNode methodNode : classNode.methods) {

            if (methodNode.isDynamicReturnType() && !getDefAllowed()) {
                addError("def is not allowed for methods.", methodNode)
            }

            if (getDisableDynamicCompile() && !inWhiteList(classNode) &&
                (hasAnnotation(methodNode, getDynamicAnnotation()) || hasCompileStaticSkip(methodNode))) {
                addError('Dynamic Compilation is not allowed for this method.', methodNode)
            }

            if (getLimitCompileStaticExtensions() && hasOtherExtensions(methodNode)) {
                addError("Compile Static extensions are limited to: ${getCompileStaticExtensionsList()}", methodNode)
            }

            checkParameters(methodNode.parameters)
        }
    }

    /**
     * The parameters of a method to check for dynamic typing.
     *
     * @param parameters an array of parameters to check, for dynamic typing.
     */
    void checkParameters(Parameter[] parameters) {
        for (Parameter parameter : parameters) {
            if (parameter.isDynamicTyped() && !getDefAllowed()) {
                addError('Dynamically types parameters are not allowed.', parameter)
            }
        }
    }

    /**
     * Sets up the conventions configurations for static compilation.
     *
     * @param sourceUnit the source unit used to find the configuration path.
     */
    static void setupConfiguration() {
        String console = System.getProperty('enterprise.groovy.console')

        if(console){
            disable = System.getProperty('enterprise.groovy.disable') == 'true'
            whiteListScripts = false
            disableDynamicCompile = System.getProperty('enterprise.groovy.disableDynamicCompile') == 'true'
            defAllowed = System.getProperty('enterprise.groovy.defAllowed') == 'true'
            return
        }

        String conventions = System.getProperty('enterprise.groovy.conventions')
        ConfigSlurper configSlurper = new ConfigSlurper()
        Map config = [:]

        try {
            if (conventions) {
                config = (ConfigObject) configSlurper.parse(conventions)?.conventions
            }
        } catch (Exception e) {
            println "WARN - Enterprise Groovy config can't be parsed, falling back to defaults"
        }

        disable = config.disable != null ? config.disable : false
        whiteListScripts = config.whiteListScripts != null ? config.whiteListScripts : true
        disableDynamicCompile = config.disableDynamicCompile != null ? config.disableDynamicCompile : false
        dynamicCompileWhiteList = (List<String>) config.dynamicCompileWhiteList ?: (List<String>) []

        compileStaticExtensionsList = (List<String>) config.compileStaticExtensions ?: (List<String>) []
        limitCompileStaticExtensions = config.limitCompileStaticExtensions != null ? config.limitCompileStaticExtensions : false

        defAllowed = config.defAllowed != null ? config.defAllowed : true
        skipDefaultPackage = config.skipDefaultPackage != null ? config.skipDefaultPackage : false

        if (compileStaticExtensionsList) {
            ListExpression extensions = new ListExpression()

            for (String extension : compileStaticExtensionsList) {
                extensions.addExpression(new ConstantExpression(extension))
            }

            compileStaticExtensions = extensions
        }
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
     * Checks the class nodes annotations, to see if it has @CompileStatic(TypeCheckingMode.SKIP)
     *
     * @param classNode The class node to check for TypeCheckingMode.SKIP on the annotations
     *
     * @return true if there is  @CompileStatic(TypeCheckingMode.SKIP), and false otherwise
     */
    static boolean hasCompileStaticSkip(ClassNode classNode) {
        AnnotationNode compileStatic = classNode.annotations.find { AnnotationNode annotationNode ->
            annotationNode.classNode.name == CompileStatic.class.name
        }

        if (!compileStatic) {
            return false
        }

        PropertyExpression propertyExpression = (PropertyExpression) compileStatic.members['value'] ?: null

        if (propertyExpression){
            ConstantExpression value = (ConstantExpression)propertyExpression.property
            return value.value == TypeCheckingMode.SKIP.toString()
        }

        return false
    }

    /**
     * Checks the method nodes annotations, to see if it has @CompileStatic(TypeCheckingMode.SKIP)
     *
     * @param methodNode The class node to check for TypeCheckingMode.SKIP on the annotations
     *
     * @return true if there is  @CompileStatic(TypeCheckingMode.SKIP), and false otherwise
     */
    static boolean hasCompileStaticSkip(MethodNode methodNode) {
        AnnotationNode compileStatic = methodNode.annotations.find { AnnotationNode annotationNode ->
            annotationNode.classNode.name == CompileStatic.class.name
        }

        if(!compileStatic){
            return false
        }

        PropertyExpression propertyExpression = (PropertyExpression) compileStatic.members['value'] ?: null

        if (propertyExpression) {
            ConstantExpression value = (ConstantExpression) propertyExpression.property
            return value.value == TypeCheckingMode.SKIP.toString()
        }

        return false
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

            if (getCompileStaticExtensions()) {
                classAnnotation.addMember(getExtensions(), getCompileStaticExtensions())
            }

            classNode.addAnnotation(classAnnotation)
        }
    }

}

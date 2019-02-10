package com.virtualgodbert.ast.egp

class TemplateCompilationTest extends GroovyTestCase {

    void test() {
        assertScript("""
import groovy.text.Template
import groovy.text.SimpleTemplateEngine
class TemplateCompilationTestImpl {

    SimpleTemplateEngine simpleTemplateEngine = new SimpleTemplateEngine()
    
    def test() {
        File templateFile = new File(this.getClass().getResource("TestTemplate").toURI())
        Template template = simpleTemplateEngine.createTemplate(templateFile)
        return template.make(["thisShouldWork": "but it does not"])
    }

}

new TemplateCompilationTestImpl().test()
""")
    }

}
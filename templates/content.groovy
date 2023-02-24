import groovy.xml.slurpersupport.GPathResult
import groovy.xml.MarkupBuilder

void renderPage(Object pageData, GPathResult inXml, MarkupBuilder outXml, Map replacements){

    GroovyShell shell = new GroovyShell()
    def commons = shell.parse(new File('templates/.commons.groovy').text)

    def pageProperties = commons.pageProperties(pageData, inXml, '/apps/panduit/templates/basedetailpageblog','panduit/components/page/basedetailpageblog', replacements)
    pageProperties['cq:tags'] = ''
    pageProperties['blogAuthor'] = ''
    pageProperties['blogCategories'] = ''
    pageProperties['blogCreationDateOverride'] = ''
    
    outXml.'jcr:root'(commons.rootProperties()) {
        'jcr:content'(pageProperties) {
            'blogcontent'(commons.component('foundation/components/parsys')){
                'text'(commons.component('sample/components/content/text', ['textIsRich': true, 'text': inXml.encoded.toString()]))
            }
        }
    }
}
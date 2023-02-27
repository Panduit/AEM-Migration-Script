import groovy.xml.slurpersupport.GPathResult
import groovy.xml.MarkupBuilder

void renderPage(Object pageData, GPathResult inXml, MarkupBuilder outXml, Map replacements, Map idMap){

    GroovyShell shell = new GroovyShell()
    def commons = shell.parse(new File('templates/.commons.groovy').text)

    def pageProperties = commons.pageProperties(pageData, inXml, '/apps/panduit/templates/basedetailpageblog','panduit/components/page/basedetailpageblog', replacements)
    // pageProperties['cq:tags'] = ''
    // pageProperties['blogAuthor'] = ''
    // pageProperties['blogCategories'] = ''

    Date blogCreationDateOverride = new Date(inXml.pubDate.toString())
    pageProperties['blogCreationDateOverride'] = '{Date}' + blogCreationDateOverride.format("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

    def thumbnail_idElement = inXml.postmeta.find {it.meta_key == "_thumbnail_id"}
    def blogdetailsimageId = thumbnail_idElement.meta_value
    def blogdetailsimagePath = idMap.get(blogdetailsimageId) == null ? '' : idMap.get(blogdetailsimageId)

    outXml.'jcr:root'(commons.rootProperties()) {
        'jcr:content'(pageProperties) {
            'par'(commons.component('wcm/foundation/components/parsys'))
            'blogdetailsimage'(commons.component('')){
                'image'(commons.component('foundation/components/image', ['fileReference': blogdetailsimagePath]))
            }
            'blogcontent'(commons.component('foundation/components/parsys')){
                'text'(commons.component('panduit/components/content/general/text', ['textIsRich': true, 'text': inXml.encoded]))
            }
        }
    }
}
import groovy.xml.slurpersupport.GPathResult
import groovy.xml.MarkupBuilder

void renderPage(Object pageData, GPathResult inXml, MarkupBuilder outXml, Map replacements, Map idMap, GPathResult categories, GPathResult tags){

    GroovyShell shell = new GroovyShell()
    def commons = shell.parse(new File('templates/.commons.groovy').text)

    def pageProperties = commons.pageProperties(pageData, inXml, '/apps/panduit/templates/basedetailpageblog','panduit/components/page/basedetailpageblog', replacements)

    // pageProperties['blogAuthor'] = ''

    Date blogCreationDateOverride = new Date(inXml.pubDate.toString())
    pageProperties['blogCreationDateOverride'] = '{Date}' + blogCreationDateOverride.format("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

    def thumbnail_idElement = inXml.postmeta.find {it.meta_key == "_thumbnail_id"}
    def blogdetailsimageId = thumbnail_idElement.meta_value
    def blogdetailsimagePath = idMap.get(blogdetailsimageId) == null ? '' : idMap.get(blogdetailsimageId)

    def blogCategoriesString = '['
    def blogCategoriesCount = 0
    for (category in categories) {
        if (blogCategoriesCount > 0) {
            blogCategoriesString += ','
        }
        blogCategoriesString += 'panduit-blog-categories:' + category.@nicename
        blogCategoriesCount++
    }
    pageProperties['blogCategories'] = blogCategoriesString + ']'

    def blogTagsString = '['
    def blogTagsCount = 0
    for (tag in tags) {
        if (blogTagsCount > 0) {
            blogTagsString += ','
        }
        blogTagsString += 'panduit:blog-tags/' + tag.@nicename
        blogTagsCount++
    }
    pageProperties['cq:tags'] = blogTagsString + ']'

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
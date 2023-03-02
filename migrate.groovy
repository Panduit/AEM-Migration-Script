#!/usr/bin/env groovy
/*
*  This program is free software: you can redistribute it and/or modify
*  it under the terms of the GNU General Public License as published by
*  the Free Software Foundation, either version 3 of the License, or
*  (at your option) any later version.
*
*  This program is distributed in the hope that it will be useful,
*  but WITHOUT ANY WARRANTY; without even the implied warranty of
*  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
*  GNU General Public License for more details.
*
*  You should have received a copy of the GNU General Public License
*  along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

/**
*  Migration script for migrating legacy content using an AEM Content Package
*  See: https://github.com/PerficientDigital/AEM-Migration-Script
*/
@Grab('com.xlson.groovycsv:groovycsv:1.3')
import static com.xlson.groovycsv.CsvParser.parseCsv

@Grab('org.apache.tika:tika-core:1.14')
import org.apache.tika.Tika;

import groovy.io.FileType
import groovy.xml.MarkupBuilder
import groovy.json.JsonSlurper
import groovy.time.TimeCategory
import groovy.time.TimeDuration
import groovy.transform.Field
import groovy.ant.AntBuilder
import groovy.xml.XmlSlurper

import java.io.Writer
import java.io.OutputStreamWriter
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

// configure settings here
@Field 
final SEPARATOR = ","
@Field 
final ENCODING = "UTF-8"

start = new Date()

if(args.length < 1) {
    println 'groovy migrate.groovy [configdir] [batch (Optional)]'
    System.exit(1)
}

batch = ""
if(args.length == 2) {
    batch = args[1]
    println "Using batch ${batch}"
}

idMap = new HashMap<String, String>()
authorsMap = new HashMap<String, String>()
imageList = []

Map loadReplacements() {
    def count = 0

    def cf = new File("work${File.separator}config${File.separator}replacement-config.json")
    assert cf.exists()
    def slurper = new JsonSlurper()
    def config = slurper.parseText(cf.text)

    def replacements = [:]

    config.each{file,cfgs->
        println "Processing replacements for ${file}..."

        def sf = new File("work${File.separator}config${File.separator}${file}")
        assert sf.exists()

        cfgs.each{cfg ->
            println "Processing configuration: ${cfg}"
            def source = parseCsv(sf.getText(ENCODING), separator: SEPARATOR)
            for (line in source) {
                if(cfg.mode == 'mapping'){
                    replacements[line[cfg.sourceKey]] = line[cfg.targetKey]
                } else {
                    def key = line[cfg.sourceKey]
                    if(cfg.targetKey){
                        replacements[key.replaceAll(cfg.extractionPattern, cfg.sourceReplacement)] = line[cfg.targetKey]
                    } else {
                        replacements[key.replaceAll(cfg.extractionPattern, cfg.sourceReplacement)] = key.replaceAll(cfg.extractionPattern, cfg.targetReplacement)
                    }
                }
                count++
            }
        }
    }

    println "Generated ${count} replacements in ${TimeCategory.minus(new Date(), start)}"
    
    return replacements
}

Map loadTemplates() {
    println 'Loading templates...'
    def count = 0
    def templates = [:]
    GroovyShell shell = new GroovyShell()
    new File('templates').eachFile (FileType.FILES) { template ->
        if(!template.getName().startsWith('.') && template.getName().endsWith('groovy')){
            def name = template.getName().replace('.groovy','')
            println "Loading template $template as $name"
            templates[name] = shell.parse(template)
            count++
        }
    }
    println "Loaded ${count} templates in ${TimeCategory.minus(new Date(), start)}"
    return templates
}

void processPages(File source, File jcrRoot) {
    
    def templates = loadTemplates()
    def replacements = loadReplacements()
    
    def pageFile = new File("work${File.separator}config${File.separator}page-mappings.csv")
    def count = 0
    def migrated = 0

    def categoriesMap = new HashMap<String, String>()
    def tagsMap = new HashMap<String, String>()

    println 'Processing pages...'
    for (pageData in parseCsv(pageFile.getText(ENCODING), separator: SEPARATOR)) {

        println "Processing page ${count + 1}"

        def process = true
        if(batch?.trim() && !batch.equals(pageData['Batch'])){
            process = false
        }
        if('Remove' == pageData[0] || 'Missing' == pageData[0]) {
            process = false
        }
        if(process){

            def template = templates[pageData['Template']]
            assert template : "Template ${pageData['Template']} not found!"

            def sourceFile = new File(pageData['Source Path'],source)
            assert sourceFile.exists() : "Source file ${sourceFile} not found!"

            println "Using template ${pageData['Template']} for ${sourceFile}"

            def inXml = new XmlSlurper().parseText(sourceFile.getText(ENCODING))

            def writer = new StringWriter()
            def outXml = new MarkupBuilder(writer)

            def categories = inXml.category.findAll {it.@domain == "category"}
            def tags = inXml.category.findAll {it.@domain == "post_tag"}

            def author = authorsMap.get(inXml.creator)

            // if src file does not exist in file-mappings.csv, download file and add to jcr
            def imgSrcs = inXml.encoded.toString().findAll('img.+src="https://panduitblog.com/wp-content/uploads.*?[ "\\r\\n]')
            for (imgSrc in imgSrcs) {
                imageUrl = imgSrc.replaceAll('img.+src="(https://panduitblog.com/wp-content/uploads.*?)[ "\\r\\n]', '$1')
                def imagePath = imageUrl.replace('https://panduitblog.com/','')
                println "path ${imagePath} exists: ${imageList.contains(imagePath)}"
                if (!imageList.contains(imagePath)) {
                    def imageFilePath = imagePath.replaceAll("[/]", "\\\\")
                    def imageFile = new File("work${File.separator}source${File.separator}${imageFilePath}")
                    
                    println "file at ${imageFilePath} exists: ${imageFile.exists()}"
                    if (!imageFile.exists()) {
                        // download
                        imageFile.getParentFile().mkdirs()
                        println "imgSrc: Writing to file: ${imageFile}"
                        
                        imageFile.withOutputStream { stream ->
                            imageFile << fetch(imageUrl)
                        }
                    }

                    // add to jcr
                    def contentXml = '''<?xml version="1.0" encoding="UTF-8"?>
<jcr:root xmlns:exif="http://ns.adobe.com/exif/1.0/" xmlns:photoshop="http://ns.adobe.com/photoshop/1.0/" xmlns:tiff="http://ns.adobe.com/tiff/1.0/" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:xmpMM="http://ns.adobe.com/xap/1.0/mm/" xmlns:stEvt="http://ns.adobe.com/xap/1.0/sType/ResourceEvent#" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dam="http://www.day.com/dam/1.0" xmlns:cq="http://www.day.com/jcr/cq/1.0" xmlns:jcr="http://www.jcp.org/jcr/1.0" xmlns:mix="http://www.jcp.org/jcr/mix/1.0" xmlns:nt="http://www.jcp.org/jcr/nt/1.0"
    jcr:primaryType="dam:Asset">
    <jcr:content
        jcr:primaryType="dam:AssetContent">
        <metadata
            jcr:primaryType="nt:unstructured"/>
        <related jcr:primaryType="nt:unstructured"/>
    </jcr:content>
</jcr:root>
'''
                    def assetRoot = new File(imagePath.replace('wp-content/uploads', '/content/dam/panduit/en/blogs'), jcrRoot)

                    def tika = new Tika()
                    def mimeType = tika.detect(imageFile)
                    
                    println 'Creating original.dir XML...'
                    def jcrwriter = new StringWriter()
                    def originalDirXml = new MarkupBuilder(jcrwriter)
                    originalDirXml.'jcr:root'('xmlns:jcr':'http://www.jcp.org/jcr/1.0','xmlns:nt':'http://www.jcp.org/jcr/nt/1.0','jcr:primaryType':'nt:file'){
                        'jcr:content'('jcr:mimeType': mimeType, 'jcr:primaryType': 'nt:resource')
                    }
                    def originalDir = new File("_jcr_content${File.separator}renditions${File.separator}original.dir${File.separator}.content.xml",assetRoot)
                    originalDir.getParentFile().mkdirs()
                    Writer dirwriter = new OutputStreamWriter(new FileOutputStream(originalDir), StandardCharsets.UTF_8)
                    dirwriter.withWriter { w ->
                        w << jcrwriter.toString()
                    }

                    println 'Copying original file...'
                    Files.copy(imageFile.toPath(), new File("_jcr_content${File.separator}renditions${File.separator}original",assetRoot).toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.COPY_ATTRIBUTES)
                    
                    println 'Writing .content.xml...'
                    Writer contentwriter = new OutputStreamWriter(new FileOutputStream(new File('.content.xml',assetRoot)), StandardCharsets.UTF_8)
                    contentwriter.withWriter { w ->
                        w << contentXml
                    }
                }
            }

            println 'Rendering page...'
            template.renderPage(pageData, inXml, outXml, replacements, idMap, categories, tags, author)

            println 'Creating parent folder...'
            def targetFile = new File("${pageData['New Path']}${File.separator}.content.xml",jcrRoot)
            targetFile.getParentFile().mkdirs()

            println "Writing results to $targetFile"
            targetFile.write(writer.toString(),ENCODING)
            migrated++

            for (category in categories) {
                categoriesMap.put(category.@nicename, category)
            }

            for (tag in tags) {
                tagsMap.put(tag.@nicename, tag)
            }
        } else {
            println 'No action required...'
        }

        count++
    }
    println "${count} pages processed and ${migrated} migrated in ${TimeCategory.minus(new Date(), start)}"

    // Category parent tag
    def categoryParentXml = '''<?xml version="1.0" encoding="UTF-8"?>
<jcr:root xmlns:sling="http://sling.apache.org/jcr/sling/1.0" xmlns:cq="http://www.day.com/jcr/cq/1.0" xmlns:jcr="http://www.jcp.org/jcr/1.0"
    jcr:description=""
    jcr:primaryType="cq:Tag"
    jcr:title="Panduit Blog Categories"
    sling:resourceType="cq/tagging/components/tag" />
'''
    def categoryParentFile = new File("\\content\\_cq_tags\\panduit-blog-categories\\.content.xml",jcrRoot)
    categoryParentFile.getParentFile().mkdirs()
    Writer categoryParentwriter = new OutputStreamWriter(new FileOutputStream(categoryParentFile), StandardCharsets.UTF_8)
    categoryParentwriter.withWriter { w ->
        w << categoryParentXml
    }

    for (Map.Entry<String,String> category : categoriesMap.entrySet()) {
        def categoryXml = """<?xml version="1.0" encoding="UTF-8"?>
<jcr:root xmlns:sling="http://sling.apache.org/jcr/sling/1.0" xmlns:cq="http://www.day.com/jcr/cq/1.0" xmlns:jcr="http://www.jcp.org/jcr/1.0"
    jcr:description=""
    jcr:primaryType="cq:Tag"
    jcr:title="${category.getValue()}"
    sling:resourceType="cq/tagging/components/tag"/>
"""

        def targetFile = new File("\\content\\_cq_tags\\panduit-blog-categories\\${category.getKey()}${File.separator}.content.xml",jcrRoot)
        targetFile.getParentFile().mkdirs()
        Writer filewriter = new OutputStreamWriter(new FileOutputStream(targetFile), StandardCharsets.UTF_8)
        filewriter.withWriter { w ->
            w << categoryXml
        }
    }

    // Tags parent tag
    def tagsParentXml = '''<?xml version="1.0" encoding="UTF-8"?>
<jcr:root xmlns:sling="http://sling.apache.org/jcr/sling/1.0" xmlns:cq="http://www.day.com/jcr/cq/1.0" xmlns:jcr="http://www.jcp.org/jcr/1.0"
    jcr:description=""
    jcr:primaryType="cq:Tag"
    jcr:title="Panduit Blog Tags"
    sling:resourceType="cq/tagging/components/tag" />
'''
    def tagsParentFile = new File("\\content\\_cq_tags\\panduit\\blog-tags\\.content.xml",jcrRoot)
    tagsParentFile.getParentFile().mkdirs()
    Writer tagsParentwriter = new OutputStreamWriter(new FileOutputStream(tagsParentFile), StandardCharsets.UTF_8)
    tagsParentwriter.withWriter { w ->
        w << tagsParentXml
    }

    for (Map.Entry<String,String> tag : tagsMap.entrySet()) {
        def tagXml = """<?xml version="1.0" encoding="UTF-8"?>
<jcr:root xmlns:sling="http://sling.apache.org/jcr/sling/1.0" xmlns:cq="http://www.day.com/jcr/cq/1.0" xmlns:jcr="http://www.jcp.org/jcr/1.0"
    jcr:description=""
    jcr:primaryType="cq:Tag"
    jcr:title="${tag.getValue()}"
    sling:resourceType="cq/tagging/components/tag"/>
"""

        def targetFile = new File("\\content\\_cq_tags\\panduit\\blog-tags\\${tag.getKey()}${File.separator}.content.xml",jcrRoot)
        targetFile.getParentFile().mkdirs()
        Writer filewriter = new OutputStreamWriter(new FileOutputStream(targetFile), StandardCharsets.UTF_8)
        filewriter.withWriter { w ->
            w << tagXml
        }
    }
}

InputStream fetch(String url){
    def get = new URL(url).openConnection()
    get.setRequestProperty('User-Agent', 'curl/7.35.0')
    def rc = get.getResponseCode()
    if(rc == 200){
        return get.getInputStream()
    }
    println "Retrieved invalid response code ${rc} from ${url}"
    return null
}

void processAuthors(File source, File jcrRoot){
    def files = new File("work${File.separator}config${File.separator}authors.csv")
    def count = 0
    def migrated = 0
    println 'Processing authors...'

    for (fileData in parseCsv(files.getText(ENCODING), separator: SEPARATOR)) {
        def userLogin = fileData[0]
        def niceName = fileData['user_nicename']
        def name = fileData['display_name']
        def description = fileData['description']
        def userAvatar = fileData['user_avatar'].replace('https://panduitblog.com/wp-content/uploads','/content/dam/panduit/en/blogs')
        println "Processing author: ${name}"
        
        def authorXml = """<?xml version="1.0" encoding="UTF-8"?>
<jcr:root xmlns:dam="http://www.day.com/dam/1.0" xmlns:cq="http://www.day.com/jcr/cq/1.0" xmlns:jcr="http://www.jcp.org/jcr/1.0" xmlns:mix="http://www.jcp.org/jcr/mix/1.0" xmlns:nt="http://www.jcp.org/jcr/nt/1.0"
    jcr:primaryType="dam:Asset">
    <jcr:content
        cq:name="${niceName}"
        cq:parentPath="/content/dam/panduit/content-fragments"
        jcr:description=""
        jcr:primaryType="dam:AssetContent"
        jcr:title="${name}"
        contentFragment="{Boolean}true">
        <data
            cq:model="/conf/global/settings/dam/cfm/models/blog-author"
            jcr:primaryType="nt:unstructured">
            <master
                jcr:primaryType="nt:unstructured"
                authorBio="${description}"
                authorBio_x0040_ContentType="text/html"
                authorName="${name}"
                authorPhoto="${userAvatar}" />
        </data>
        <metadata
            jcr:primaryType="nt:unstructured"/>
        <related jcr:primaryType="nt:unstructured"/>
    </jcr:content>
</jcr:root>
"""

        def targetFile = new File("\\content\\dam\\panduit\\content-fragments\\${niceName}${File.separator}.content.xml",jcrRoot)
        targetFile.getParentFile().mkdirs()
        Writer writer = new OutputStreamWriter(new FileOutputStream(targetFile), StandardCharsets.UTF_8)
        writer.withWriter { w ->
            w << authorXml
        }

        authorsMap.put(userLogin, niceName)
    }
}

void processFiles(File source, File jcrRoot){
    
    def contentXml = '''<?xml version="1.0" encoding="UTF-8"?>
<jcr:root xmlns:exif="http://ns.adobe.com/exif/1.0/" xmlns:photoshop="http://ns.adobe.com/photoshop/1.0/" xmlns:tiff="http://ns.adobe.com/tiff/1.0/" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:xmpMM="http://ns.adobe.com/xap/1.0/mm/" xmlns:stEvt="http://ns.adobe.com/xap/1.0/sType/ResourceEvent#" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dam="http://www.day.com/dam/1.0" xmlns:cq="http://www.day.com/jcr/cq/1.0" xmlns:jcr="http://www.jcp.org/jcr/1.0" xmlns:mix="http://www.jcp.org/jcr/mix/1.0" xmlns:nt="http://www.jcp.org/jcr/nt/1.0"
    jcr:primaryType="dam:Asset">
    <jcr:content
        jcr:primaryType="dam:AssetContent">
        <metadata
            jcr:primaryType="nt:unstructured"/>
        <related jcr:primaryType="nt:unstructured"/>
    </jcr:content>
</jcr:root>
'''
    def tika = new Tika()
    def files = new File("work${File.separator}config${File.separator}file-mappings.csv")
    def count = 0
    def migrated = 0
    println 'Processing files...'

    for (fileData in parseCsv(files.getText(ENCODING), separator: SEPARATOR)) {
        def assetRoot = new File(fileData['Target'], jcrRoot)
        def sourceFile = new File(fileData['Source'], source)
        idMap.put(fileData['Id'], fileData['Target'])
        imageList.add(fileData['Source'])
        def mimeType = tika.detect(sourceFile)
        
        println "Processing Source: ${sourceFile} Target: ${assetRoot}"
        
        println 'Creating original.dir XML...'
        def writer = new StringWriter()
        def originalDirXml = new MarkupBuilder(writer)
        originalDirXml.'jcr:root'('xmlns:jcr':'http://www.jcp.org/jcr/1.0','xmlns:nt':'http://www.jcp.org/jcr/nt/1.0','jcr:primaryType':'nt:file'){
            'jcr:content'('jcr:mimeType': mimeType, 'jcr:primaryType': 'nt:resource')
        }
        def originalDir = new File("_jcr_content${File.separator}renditions${File.separator}original.dir${File.separator}.content.xml",assetRoot)
        originalDir.getParentFile().mkdirs()
        Writer dirwriter = new OutputStreamWriter(new FileOutputStream(originalDir), StandardCharsets.UTF_8)
        dirwriter.withWriter { w ->
            w << writer.toString()
        }
        
        println 'Copying original file...'
        Files.copy(sourceFile.toPath(), new File("_jcr_content${File.separator}renditions${File.separator}original",assetRoot).toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.COPY_ATTRIBUTES)
        
        println 'Writing .content.xml...'
        Writer contentwriter = new OutputStreamWriter(new FileOutputStream(new File('.content.xml',assetRoot)), StandardCharsets.UTF_8)
        contentwriter.withWriter { w ->
            w << contentXml
        }
    }
}

def configDir = new File(args[0])
assert configDir.exists()
println 'Copying configuration to work dir...'
def workConfig = new File("work${File.separator}config")
if(!workConfig.exists()){
    workConfig.mkdirs()
}
configDir.eachFile (FileType.FILES) { file ->
    Files.copy(file.toPath(), new File(workConfig.getAbsolutePath()+File.separator+file.getName()).toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.COPY_ATTRIBUTES)
}

def base = new File('work')
def source = new File('source',base)
println "Using source: ${source}"
def target = new File('target',base)
def jcrRoot = new File('jcr_root',target)
println "Using target: ${target}"

println 'Clearing target...'
target.deleteDir();
target.mkdir();

processFiles(source,jcrRoot)
processAuthors(source,jcrRoot)
processPages(source,jcrRoot)

println 'Updating filter.xml...'
def vlt = new File("META-INF${File.separator}vault",target)
vlt.mkdirs()
if(batch?.trim()){
    def writer = new StringWriter()
    def filterXml = new MarkupBuilder(writer)
    
    def pageFile = new File("work${File.separator}config${File.separator}page-mappings.csv")
    
    filterXml.'workspaceFilter'('version':'1.0'){
        for (pageData in parseCsv(pageFile.getText(ENCODING), separator: SEPARATOR)) {
            if(batch.equals(pageData['Batch']) && 'Remove' != pageData[0] && 'Missing' != pageData[0]){
                'filter'('root':pageData['New Path']){
                    'include'('pattern':pageData['New Path'])
                    'include'('pattern':"${pageData['New Path']}/jcr:content.*")
                }
            }
        }
        for (fileData in parseCsv(new File("work${File.separator}config${File.separator}file-mappings.csv").getText(ENCODING), separator: SEPARATOR)) {
            if(batch.equals(fileData['Batch']) && 'Remove' != fileData[0] && 'Missing' != fileData[0]){
                'filter'('root':fileData['Target']){
                    'include'('pattern':fileData['Target'])
                    'include'('pattern':"${fileData['Target']}/jcr:content.*")
                }
            }
        }
    }
    new File('filter.xml',vlt) << writer.toString()
} else {
    def filter = new File('filter.xml',workConfig)
    assert filter.exists()
    Files.copy(filter.toPath(), new File('filter.xml',vlt).toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.COPY_ATTRIBUTES)
}

def now = new Date().format("yyyy-MM-dd-HH-mm-ss")
println 'Updating properties.xml...'
def propertiesXml = new File('properties.xml',workConfig)
assert propertiesXml.exists()
new File('properties.xml',vlt) << propertiesXml.getText().replace('${version}',now).replace('${name}',"migrated-content-${configDir.getName()}")

// println 'Creating package...'
// def ant = new AntBuilder()
// ant.zip(destfile: "${base.getAbsolutePath()}${File.separator}migrated-content-${configDir.getName()}-${now}.zip", basedir: target)

// println "Package saved to: work${File.separator}migrated-content-${configDir.getName()}-${now}.zip"

println "Content migrated in ${TimeCategory.minus(new Date(), start)}"

println "Package created successfully!!!"

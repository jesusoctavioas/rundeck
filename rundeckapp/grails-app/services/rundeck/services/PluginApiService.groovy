package rundeck.services

import com.dtolabs.rundeck.core.common.IFramework
import com.dtolabs.rundeck.core.plugins.PluginUtils
import com.dtolabs.rundeck.core.plugins.configuration.PropertyScope
import com.dtolabs.rundeck.core.resources.ResourceModelSourceFactory
import com.dtolabs.rundeck.plugins.file.FileUploadPlugin
import com.dtolabs.rundeck.plugins.logging.LogFilterPlugin
import com.dtolabs.rundeck.plugins.logs.ContentConverterPlugin
import com.dtolabs.rundeck.plugins.storage.StorageConverterPlugin
import com.dtolabs.rundeck.plugins.storage.StoragePlugin
import com.dtolabs.rundeck.server.plugins.services.StorageConverterPluginProviderService
import com.dtolabs.rundeck.server.plugins.services.StoragePluginProviderService
import org.grails.web.util.WebUtils
import org.springframework.context.NoSuchMessageException

import java.text.SimpleDateFormat

class PluginApiService {

    def servletContext
    def grailsApplication
    def frameworkService
    def messageSource
    UiPluginService uiPluginService
    NotificationService notificationService
    LoggingService loggingService
    PluginService pluginService
    ScmService scmService
    LogFileStorageService logFileStorageService
    StoragePluginProviderService storagePluginProviderService
    StorageConverterPluginProviderService storageConverterPluginProviderService

    def listPluginsDetailed() {
        //list plugins and config settings for project/framework props
        IFramework framework = frameworkService.getRundeckFramework()
        Locale locale = getLocale()

        //framework level plugin descriptions
        //TODO: use pluginService.listPlugins for these services/plugintypes
        def pluginDescs= [
                framework.getNodeExecutorService(),
                framework.getFileCopierService(),
                framework.getNodeStepExecutorService(),
                framework.getStepExecutionService(),
        ].collectEntries{
            [it.name, it.listDescriptions().sort {a,b->a.name<=>b.name}]
        }

        //load via pluginService to include spring-based app plugins
        pluginDescs['ResourceModelSource'] = pluginService.listPlugins(
                ResourceModelSourceFactory,
                framework.getResourceModelSourceService()
        ).findAll { it.value.description }.collect {
            it.value.description
        }.sort { a, b -> a.name <=> b.name }


        //TODO: use pluginService.listPlugins for these services/plugintypes
        [
                framework.getResourceFormatParserService(),
                framework.getResourceFormatGeneratorService(),
                framework.getOrchestratorService()
        ].each {

            pluginDescs[it.name] = it.listDescriptions().sort { a, b -> a.name <=> b.name }
        }

        //web-app level plugin descriptions
        pluginDescs[notificationService.notificationPluginProviderService.name]=notificationService.listNotificationPlugins().collect {
            it.value.description
        }.sort { a, b -> a.name <=> b.name }
        pluginDescs[loggingService.streamingLogReaderPluginProviderService.name]=loggingService.listStreamingReaderPlugins().collect {
            it.value.description
        }.sort { a, b -> a.name <=> b.name }
        pluginDescs[loggingService.streamingLogWriterPluginProviderService.name]=loggingService.listStreamingWriterPlugins().collect {
            it.value.description
        }.sort { a, b -> a.name <=> b.name }
        pluginDescs[logFileStorageService.executionFileStoragePluginProviderService.name]= logFileStorageService.listLogFileStoragePlugins().collect {
            it.value.description
        }.sort { a, b -> a.name <=> b.name }
        pluginDescs[storagePluginProviderService.name]= pluginService.listPlugins(StoragePlugin.class, storagePluginProviderService).collect {
            it.value.description
        }.sort { a, b -> a.name <=> b.name }
        pluginDescs[storageConverterPluginProviderService.name] = pluginService.listPlugins(StorageConverterPlugin.class, storageConverterPluginProviderService).collect {
            it.value.description
        }.sort { a, b -> a.name <=> b.name }
        pluginDescs[scmService.scmExportPluginProviderService.name]=scmService.listPlugins('export').collect {
            it.value.description
        }.sort { a, b -> a.name <=> b.name }
        pluginDescs[scmService.scmImportPluginProviderService.name]=scmService.listPlugins('import').collect {
            it.value.description
        }.sort { a, b -> a.name <=> b.name }

        pluginDescs['FileUploadPluginService']=pluginService.listPlugins(FileUploadPlugin).collect {
            it.value.description
        }.sort { a, b -> a.name <=> b.name }
        pluginDescs['LogFilter'] = pluginService.listPlugins(LogFilterPlugin).collect {
            it.value.description
        }.sort { a, b -> a.name <=> b.name }
        pluginDescs['ContentConverter']=pluginService.listPlugins(ContentConverterPlugin).collect {
            it.value.description
        }.sort { a, b -> a.name <=> b.name }


        def uiPluginProfiles = [:]
        def loadedFileNameMap=[:]
        pluginDescs.each { svc, list ->
            list.each { desc ->
                def provIdent = svc + ":" + desc.name
                uiPluginProfiles[provIdent] = uiPluginService.getProfileFor(svc, desc.name)
                def filename = uiPluginProfiles[provIdent].metadata?.filename
                if(filename){
                    if(!loadedFileNameMap[filename]){
                        loadedFileNameMap[filename]=[]
                    }
                    loadedFileNameMap[filename]<< provIdent
                }
            }
        }

        def defaultScopes=[
                (framework.getNodeStepExecutorService().name) : PropertyScope.InstanceOnly,
                (framework.getStepExecutionService().name) : PropertyScope.InstanceOnly,
        ]
        def bundledPlugins=[
                (framework.getNodeExecutorService().name): framework.getNodeExecutorService().getBundledProviderNames(),
                (framework.getFileCopierService().name): framework.getFileCopierService().getBundledProviderNames(),
                (framework.getResourceFormatParserService().name): framework.getResourceFormatParserService().getBundledProviderNames(),
                (framework.getResourceFormatGeneratorService().name): framework.getResourceFormatGeneratorService().getBundledProviderNames(),
                (framework.getResourceModelSourceService().name): framework.getResourceModelSourceService().getBundledProviderNames(),
                (storagePluginProviderService.name): storagePluginProviderService.getBundledProviderNames()+['db'],
                FileUploadPluginService: ['filesystem-temp'],
        ]
        //list included plugins
        def embeddedList = frameworkService.listEmbeddedPlugins(grailsApplication)
        def embeddedFilenames=[]
        if(embeddedList.success && embeddedList.pluginList){
            embeddedFilenames=embeddedList.pluginList*.fileName
        }
        def specialConfiguration=[
                (storagePluginProviderService.name):[
                        description: message("plugin.storage.provider.special.description",locale),
                        prefix:"rundeck.storage.provider.[index].config."
                ],
                (storageConverterPluginProviderService.name):[
                        description: message("plugin.storage.converter.special.description",locale),
                        prefix:"rundeck.storage.converter.[index].config."
                ],
                (framework.getResourceModelSourceService().name):[
                        description: message("plugin.resourceModelSource.special.description",locale),
                        prefix:"resources.source.[index].config."
                ],
                (logFileStorageService.executionFileStoragePluginProviderService.name):[
                        description: message("plugin.executionFileStorage.special.description",locale),
                ],
                (scmService.scmExportPluginProviderService.name):[
                        description: message("plugin.scmExport.special.description",locale),
                ],
                (scmService.scmImportPluginProviderService.name):[
                        description: message("plugin.scmImport.special.description",locale),
                ],
                FileUploadPluginService:[
                        description: message("plugin.FileUploadPluginService.special.description",locale),
                ]
        ]
        def specialScoping=[
                (scmService.scmExportPluginProviderService.name):true,
                (scmService.scmImportPluginProviderService.name):true
        ]

        [
                descriptions        : pluginDescs,
                serviceDefaultScopes: defaultScopes,
                bundledPlugins      : bundledPlugins,
                embeddedFilenames   : embeddedFilenames,
                specialConfiguration: specialConfiguration,
                specialScoping      : specialScoping,
                uiPluginProfiles    : uiPluginProfiles
        ]
    }

    def listPlugins() {
        Locale locale = getLocale()
        String appDate = servletContext.getAttribute('version.date')
        String appVer = servletContext.getAttribute('version.number')
        def pluginList = listPluginsDetailed()
        def tersePluginList = pluginList.descriptions.collect {
            String service = it.key
            def providers = it.value.collect { provider ->
                def meta = frameworkService.getRundeckFramework().
                        getPluginManager().
                        getPluginMetadata(service, provider.name)
                boolean builtin = meta == null
                String ver = meta?.pluginFileVersion ?: appVer
                String dte = meta?.pluginDate ?: appDate
                String artifactName = meta?.pluginArtifactName ?: provider.name
                String tgtHost = meta?.targetHostCompatibility ?: 'all'
                String rdVer = meta?.rundeckCompatibilityVersion ?: 'unspecified'
                String id = meta?.pluginId ?: PluginUtils.generateShaIdFromName(artifactName)
                [pluginId   : id,
                 pluginName : artifactName,
                 name         : provider.name,
                 title        : provider.title,
                 description  : provider.description,
                 builtin      : builtin,
                 pluginVersion: ver,
                 rundeckCompatibilityVersion: rdVer,
                 targetHostCompatibility: tgtHost,
                 pluginDate   : toEpoch(dte),
                 enabled      : true]
            }
            [service  : it.key,
             desc     : message("framework.service.${service}.description".toString(),locale),
             providers: providers
            ]
        }
        tersePluginList
    }

    def listInstalledPluginIds() {
        return listPlugins()*.providers.collect { it.pluginId }.flatten()
    }

    Locale getLocale() {
        WebUtils.retrieveGrailsWebRequest().getLocale()
    }

    private def message(String code, Locale locale) {
        try {
            messageSource.getMessage(code,[].toArray(),locale)
        } catch(NoSuchMessageException nsme) {
            return code
        }

    }

    private long toEpoch(String dateString) {
        PLUGIN_DATE_FMT.parse(dateString).time
    }

    private static final SimpleDateFormat PLUGIN_DATE_FMT = new SimpleDateFormat("EEE MMM dd hh:mm:ss Z yyyy")
}

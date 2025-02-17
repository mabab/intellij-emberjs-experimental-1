package com.emberjs.glint

import com.dmarcotte.handlebars.file.HbFileType
import com.dmarcotte.handlebars.psi.HbPsiFile
import com.emberjs.gts.GtsFileType
import com.emberjs.hbs.HbReference
import com.emberjs.utils.emberRoot
import com.emberjs.utils.originalVirtualFile
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.impl.BaseIntentionAction
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.injected.editor.DocumentWindow
import com.intellij.injected.editor.VirtualFileWindow
import com.intellij.lang.javascript.JavaScriptFileType
import com.intellij.lang.javascript.TypeScriptFileType
import com.intellij.lang.javascript.completion.JSInsertHandler
import com.intellij.lang.javascript.inspections.JSInspectionSuppressor
import com.intellij.lang.javascript.integration.JSAnnotationError
import com.intellij.lang.javascript.integration.JSAnnotationError.*
import com.intellij.lang.javascript.psi.JSFile
import com.intellij.lang.javascript.psi.JSFunctionType
import com.intellij.lang.javascript.service.JSLanguageServiceProvider
import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.typescript.compiler.TypeScriptService
import com.intellij.lang.typescript.compiler.languageService.TypeScriptLanguageServiceUtil
import com.intellij.lang.typescript.compiler.languageService.TypeScriptMessageBus
import com.intellij.lang.typescript.compiler.languageService.TypeScriptServerServiceCompletionEntry
import com.intellij.lang.typescript.compiler.languageService.codeFixes.TypeScriptSuppressByCommentFix
import com.intellij.lang.typescript.compiler.languageService.protocol.commands.response.TypeScriptCompletionResponse
import com.intellij.lang.typescript.compiler.languageService.protocol.commands.response.TypeScriptSymbolDisplayPart
import com.intellij.lsp.LspServer
import com.intellij.lsp.api.LspServerManager
import com.intellij.lsp.methods.HoverMethod
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.xml.XmlElement
import com.intellij.ui.EditorNotifications
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletableFuture.completedFuture
import java.util.concurrent.Future
import java.util.stream.Stream

class GlintLanguageServiceProvider(val project: Project) : JSLanguageServiceProvider {

    override fun isHighlightingCandidate(file: VirtualFile) = file.fileType is HbFileType || file.fileType is JavaScriptFileType || file.fileType is TypeScriptFileType || file.fileType is GtsFileType

    override fun getService(file: VirtualFile) = allServices.firstOrNull()

    override fun getAllServices() =
            if (project.guessProjectDir()?.emberRoot != null) listOf(GlintTypeScriptService.getInstance(project)) else emptyList()
}



class GlintTypeScriptService(private val project: Project) : TypeScriptService, Disposable {
    var currentlyChecking: PsiElement? = null

    companion object {
        private val LOG = Logger.getInstance(GlintTypeScriptService::class.java)
        fun getInstance(project: Project): GlintTypeScriptService = project.getService(GlintTypeScriptService::class.java)
    }

    fun getDescriptor(virtualFile: VirtualFile): GlintLspServerDescriptor? {
        return if (getDescriptor()?.isSupportedFile(virtualFile) == true)
            getDescriptor()
        else
            null
    }

    fun getDescriptor(): GlintLspServerDescriptor? {
        return if (project.guessProjectDir()?.emberRoot != null) getGlintDescriptor(project) else null
    }

    private fun <T> withServer(action: LspServer.() -> T): T? = getDescriptor()!!.server?.action()

    override val name = "Glint TypeScript LSP"
    override fun isServiceCreated() = withServer { isRunning || isMalfunctioned } ?: false

    override fun showStatusBar() = withServer { isServiceCreated() } ?: false

    override fun getStatusText() = withServer {
        when {
            isRunning -> "Glint TypeScript LSP"
            isMalfunctioned -> "Glint LSP ⚠"
            else -> "..."
        }
    }

    override fun openEditor(file: VirtualFile) {

    }

    override fun closeLastEditor(file: VirtualFile) {

    }

    override fun getCompletionMergeStrategy(parameters: CompletionParameters, file: PsiFile, context: PsiElement): TypeScriptService.CompletionMergeStrategy {
        return TypeScriptLanguageServiceUtil.getCompletionMergeStrategy(parameters, file, context)
    }

    override fun updateAndGetCompletionItems(virtualFile: VirtualFile, parameters: CompletionParameters): Future<List<TypeScriptService.CompletionEntry>?>? {
        val descriptor = getDescriptor(virtualFile) ?: return null
        return withServer {
            val items = completedFuture(descriptor.server!!.getCompletionItems(parameters)).get().toList().map { GlintCompletionEntry(it) }
            val file = PsiManager.getInstance(project).findFile(virtualFile)!!
            val doc = PsiDocumentManager.getInstance(project).getDocument(file)!!
            return@withServer getDetailedCompletionItems(virtualFile, items, doc, parameters.offset)
        }
    }

    fun isTSCompilerError(annotationError: GlintAnnotationError): Boolean {
        return annotationError.code == "tslint"
    }

    fun getSuppressActions(element: PsiElement?): List<BaseIntentionAction>? {
        if (element == null) return null
        if (element.containingFile !is JSFile) {
            return listOf(GlintHBSupressErrorFix("ignore"), GlintHBSupressErrorFix("expect"))
        }
        val aClass = JSInspectionSuppressor.getHolderClass(element)
        return listOf(TypeScriptSuppressByCommentFix(aClass), TypeScriptSupressByExpectErrorFix(aClass))
    }

    override fun getServiceFixes(file: PsiFile, element: PsiElement?, result: JSAnnotationError): List<IntentionAction?> {
        if (result as? GlintAnnotationError == null) {
            return emptyList()
        }

        val descriptor = getDescriptor(file.virtualFile) ?: return emptyList()
        descriptor.server?.let {
            val codeActionMethod = GlintCodeActionMethod.create(it, file, result.diagnostic) { command, _ ->
                return@create command == "x"
            }
            val actions = it.invokeSynchronously(codeActionMethod)?.toMutableList() ?: emptyList<IntentionAction?>().toMutableList()
            val isSuggestion = "hide" == result.category
            val isTSCompilerError = isTSCompilerError(result)
            if (!isTSCompilerError && file.virtualFile != null) {
                if (!isSuggestion || isTSCompilerError) {
                    val suppressByCommentFix = this.getSuppressActions(element)
                    if (suppressByCommentFix != null) {
                        actions.addAll(suppressByCommentFix)
                    }
                }
                return actions.toList()
            }
            return emptyList()
        } ?: return emptyList()
    }

    override fun getDetailedCompletionItems(virtualFile: VirtualFile,
                                            items: List<TypeScriptService.CompletionEntry>,
                                            document: Document,
                                            positionInFileOffset: Int): Future<List<TypeScriptService.CompletionEntry>?>? {
        val descriptor = getDescriptor(virtualFile) ?: return null
        return withServer {
            val entries = items.filterIsInstance<TypeScriptServerServiceCompletionEntry>()
            return@withServer completedFuture(items.filterIsInstance<GlintCompletionEntry>().map { descriptor.server!!.getResolvedCompletionItem((it as GlintCompletionEntry).item) }
                    .map { descriptor.server!!.getResolvedCompletionItem(it) }
                    .map {
                        val detail = TypeScriptCompletionResponse.CompletionEntryDetail()
                        detail.name = it.label
                        detail.kind = it.kind.name
                        val doc = TypeScriptSymbolDisplayPart()
                        doc.text = it.documentation?.left ?: ""
                        doc.kind = "text"
                        val disp = TypeScriptSymbolDisplayPart()
                        disp.kind = it.kind?.name
                        disp.text = it.detail
                        detail.documentation = arrayOf(doc)
                        detail.displayParts = arrayOf(disp)
                        TypeScriptServerServiceCompletionEntry(detail)
                    } + entries)
        }
    }

    fun getNavigationFor(document: Document, elem: PsiElement, includeOther: Boolean): Array<PsiElement>? {
        var sourceElement: PsiElement = elem
        if (!includeOther && (sourceElement is XmlElement || sourceElement.containingFile is HbPsiFile)) {
            return null
        }
        var element = sourceElement.containingFile.originalFile.findElementAt(sourceElement.textOffset) ?: sourceElement
        if (currentlyChecking == null && element.containingFile is HbPsiFile) {
            currentlyChecking = sourceElement
            if (element is LeafPsiElement) {
                element = element.parent!!
            }
            if (element.reference is HbReference || element.references.find { it is HbReference } != null) {
                currentlyChecking = null
                return null
            }
            currentlyChecking = null
        }
        if (sourceElement.containingFile.fileType == GtsFileType.INSTANCE) {
            element = sourceElement
        }
        class DelegateElement(val element: PsiElement, val origElement: PsiElement, val documentWindow: DocumentWindow) : PsiElement by element {
            override fun getTextRange(): TextRange {
                val range = origElement.textRange
                val hostRange = documentWindow.hostRanges.first()
                return TextRange(hostRange.startOffset + range.startOffset, hostRange.startOffset + range.endOffset)
            }
        }

        var elem: Any = element
        if (document is DocumentWindow) {
            val vfile = (element.originalVirtualFile as VirtualFileWindow).delegate
            val f = PsiManager.getInstance(element.project).findFile(vfile)!!
            elem = f.findElementAt(document.hostRanges.first().startOffset + element.textOffset)!!
            elem = DelegateElement(elem, element, document)
        }

        return getDescriptor()?.server?.getElementDefinitions(elem as PsiElement)?.toTypedArray()
    }

    override fun getNavigationFor(document: Document, elem: PsiElement): Array<PsiElement>? {
        return getNavigationFor(document, elem, false)
    }


    override fun getSignatureHelp(file: PsiFile, context: CreateParameterInfoContext): Future<Stream<JSFunctionType>?>? = null

    fun quickInfo(element: PsiElement): String? {
        val server = getDescriptor()?.server
        val raw = server?.invokeSynchronously(HoverMethod.create(server, element)) ?: return null
        LOG.info("Quick info for $element : $raw")
        return raw.substring("<html><body><pre>".length, raw.length - "</pre></body></html>".length)
    }

    override fun isDisabledByContext(context: VirtualFile): Boolean {
        return getDescriptor()?.isAvailable?.not() ?: return true
    }

    override fun getQuickInfoAt(element: PsiElement, originalElement: PsiElement, originalFile: VirtualFile): CompletableFuture<String?> =
            completedFuture(quickInfo(element))

    override fun restart(recreateToolWindow: Boolean) {
        val lspServerManager = LspServerManager.getInstance(project)
        val lspServers = lspServerManager.getServersForProvider(GlintLspSupportProvider::class.java)
        lspServers.forEach { lspServerManager.stopServer(it) }
        if (!lspServers.isEmpty()) {
            getGlintDescriptor(project).ensureStarted()
            TypeScriptMessageBus.get(project).changed()
        }
    }

    override fun highlight(file: PsiFile): CompletableFuture<List<JSAnnotationError>>? {
        val server = getDescriptor()?.server ?: return completedFuture(emptyList())
        val virtualFile = file.virtualFile

        EditorNotifications.getInstance(project).updateNotifications(virtualFile)

        return completedFuture(server.getDiagnostics(virtualFile)?.map {
            GlintAnnotationError(it, virtualFile.canonicalPath)
        })
    }

    override fun canHighlight(file: PsiFile) = file.fileType is HbFileType ||
            file.fileType is TypeScriptFileType ||
            file.fileType is GtsFileType ||
            file.fileType is JavaScriptFileType

    override fun isAcceptable(file: VirtualFile) = file.fileType is HbFileType ||
                                                   file.fileType is TypeScriptFileType ||
                                                   file.fileType is GtsFileType ||
                                                   file.fileType is JavaScriptFileType

    override fun dispose() {
        return
    }
}

class GlintCompletionEntry(internal val item: CompletionItem) : TypeScriptService.CompletionEntry {
    override val name: String get() = item.label
    val detail: String? get() = item.detail

    override fun intoLookupElement() = LookupElementBuilder.create(item.label)
            .withTypeText(item.detail, true)
            .withInsertHandler(JSInsertHandler.DEFAULT)
}

class GlintAnnotationError(val diagnostic: Diagnostic, private val path: String?) : JSAnnotationError {
    override fun getLine() = diagnostic.range.start.line
    val endLine = diagnostic.range.end.line
    val endColumn = diagnostic.range.end.character
    val diagCode = diagnostic.code?.get()
    override fun getColumn() = diagnostic.range.start.character
    val code by lazy { diagnostic.source?.toString() }

    override fun getAbsoluteFilePath(): String? = path

    override fun getDescription(): String = diagnostic.message + " (${diagnostic.source}${diagCode?.let { ":${it}" } ?: ""})"

    override fun getCategory() = when (diagnostic.severity) {
        DiagnosticSeverity.Error -> ERROR_CATEGORY
        DiagnosticSeverity.Warning -> WARNING_CATEGORY
        DiagnosticSeverity.Hint, DiagnosticSeverity.Information -> INFO_CATEGORY
    }
}

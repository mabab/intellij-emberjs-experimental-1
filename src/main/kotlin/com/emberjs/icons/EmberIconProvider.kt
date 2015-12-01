package com.emberjs.icons

import com.emberjs.project.EmberModuleType
import com.emberjs.resolver.EmberName
import com.intellij.icons.AllIcons
import com.intellij.ide.IconProvider
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.Iconable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.ui.LayeredIcon

import javax.swing.*

class EmberIconProvider : IconProvider() {

    override fun getIcon(element: PsiElement, @Iconable.IconFlags flags: Int) = getIcon(element.containingFile)

    fun getIcon(psiFile: PsiFile?) = psiFile?.let { getIcon(it.virtualFile) }

    fun getIcon(file: VirtualFile): Icon? {
        if (file.extension != "js" && file.extension != "hbs")
            return null

        val project = ProjectLocator.getInstance().guessProjectForFile(file) ?: return null
        val module = EmberModuleType.findModuleForFile(file, project) ?: return null

        return ModuleRootManager.getInstance(module).contentRoots
                .map { root -> EmberName.from(root, file)?.let { getIcon(it) } }
                .filterNotNull()
                .firstOrNull()
    }

    fun getIcon(name: EmberName) = getIcon(name.type)

    companion object {
        fun getIcon(type: String): Icon? {
            val typeWithoutSuffix = type.removeSuffix("-test").removeSuffix("-integration")
            val baseIcon = EmberIcons.FILE_TYPE_ICONS[typeWithoutSuffix]

            return when {
                baseIcon == null -> null
                type.endsWith("-test") -> LayeredIcon(baseIcon, AllIcons.Nodes.JunitTestMark)
                else -> baseIcon
            }
        }
    }
}

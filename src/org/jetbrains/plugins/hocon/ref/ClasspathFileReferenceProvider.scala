package org.jetbrains.plugins.hocon
package ref

import com.intellij.psi.{PsiElement, PsiReference, PsiReferenceProvider}
import com.intellij.util.ProcessingContext
import org.jetbrains.plugins.hocon.psi.HClasspathTarget

class ClasspathFileReferenceProvider extends PsiReferenceProvider {
  def getReferencesByElement(element: PsiElement, context: ProcessingContext): Array[PsiReference] = element match {
    case target: HClasspathTarget => target.getFileReferences.asInstanceOf[Array[PsiReference]]
    case _ => PsiReference.EMPTY_ARRAY
  }
}

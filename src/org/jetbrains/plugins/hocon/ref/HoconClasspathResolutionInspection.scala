package org.jetbrains.plugins.hocon
package ref

import com.intellij.codeInspection.{LocalInspectionTool, ProblemHighlightType, ProblemsHolder}
import com.intellij.psi.{PsiElement, PsiElementVisitor}
import org.jetbrains.plugins.hocon.psi.HClasspathTarget

class HoconClasspathResolutionInspection extends LocalInspectionTool {
  override def buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
    new PsiElementVisitor {
      override def visitElement(element: PsiElement): Unit = element match {
        case ct: HClasspathTarget =>
          ct.getFileReferences.foreach { ref =>
            if (!ref.isSoft && ref.multiResolve(false).isEmpty) {
              holder.registerProblem(
                ref,
                ProblemsHolder.unresolvedReferenceMessage(ref),
                ProblemHighlightType.LIKE_UNKNOWN_SYMBOL,
              )
            }
          }
        case _ =>
          super.visitElement(element)
      }
    }
}

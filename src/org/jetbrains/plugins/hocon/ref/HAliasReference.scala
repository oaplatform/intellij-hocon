package org.jetbrains.plugins.hocon
package ref

import com.intellij.openapi.util.TextRange
import com.intellij.psi.{ElementManipulators, PsiElement, PsiReference}
import org.jetbrains.plugins.hocon.psi.HAlias
import org.jetbrains.plugins.hocon.semantics.HoconAnchors

/** Reference from a `*name` alias (non-standard OAP extension) to its `&name` anchor definition. Unlike
  * [[HKeyReference]], this is a single reference for the whole alias token, not one per dotted-path segment -
  * aliases are a single name, not a path.
  */
class HAliasReference(alias: HAlias) extends PsiReference {
  def getElement: PsiElement = alias

  def getCanonicalText: String = alias.name.getOrElse("")

  // skip the leading '*'
  def getRangeInElement: TextRange = TextRange.from(1, math.max(0, alias.getTextLength - 1))

  def resolve(): PsiElement = alias.resolvedAnchorDef.orNull

  def isReferenceTo(element: PsiElement): Boolean = element == resolve()

  def bindToElement(element: PsiElement): PsiElement = null

  def handleElementRename(newElementName: String): PsiElement =
    ElementManipulators.handleContentChange(alias, getRangeInElement, newElementName)

  def isSoft = true

  override def getVariants: Array[AnyRef] =
    HoconAnchors.anchorsInFile(alias.hoconFile).flatMap(_.name.iterator).distinct.toArray[AnyRef]
}

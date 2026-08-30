package org.jetbrains.plugins.hocon
package ref

import com.intellij.psi.PsiFile
import org.jetbrains.plugins.hocon.lexer.HoconTokenType
import org.jetbrains.plugins.hocon.psi.{HClasspathTarget, HObjectField}
import org.junit.Assert.assertEquals

class HoconClasspathResolutionTest extends HoconSingleModuleTest {
  def rootPath = s"$testdataPath/includes/singlemodule"

  def testClasspathValueResolution(): Unit = {
    val psiFile = findHoconFile("classpathValue.conf", getProject)

    psiFile.depthFirst.collectOnly[HClasspathTarget].foreach { target =>
      // HClasspathTarget -> HClasspathReference -> HValuedField -> HObjectField
      val objectField = target.getParent.getParent.getParent.asInstanceOf[HObjectField]
      val prevComments = objectField.nonWhitespaceChildren
        .takeWhile(e => e.getNode.getElementType == HoconTokenType.HashComment)
        .toVector

      val expectedFiles =
        prevComments.flatMap(_.getText.stripPrefix("#").split(',')).map(_.trim).filter(_.nonEmpty).map(findVirtualFile).toSet

      val actualFiles = target.getFileReferences.last
        .multiResolve(false)
        .map(_.getElement)
        .collect { case file: PsiFile =>
          file.getVirtualFile
        }
        .toSet

      assertEquals(target.getText, expectedFiles, actualFiles)
    }
  }
}

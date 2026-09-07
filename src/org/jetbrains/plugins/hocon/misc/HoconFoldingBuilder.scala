package org.jetbrains.plugins.hocon
package misc

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.{FoldingBuilder, FoldingDescriptor}
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.tree.TokenSet

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

class HoconFoldingBuilder extends FoldingBuilder {

  import org.jetbrains.plugins.hocon.lexer.HoconTokenType.*
  import org.jetbrains.plugins.hocon.parser.HoconElementType.*

  private val blockTypes = TokenSet.create(BlockObject, BlockArray)

  def buildFoldRegions(node: ASTNode, document: Document): Array[FoldingDescriptor] = {
    val foldableTypes =
      TokenSet.create(Object, Array, BlockObject, BlockArray, MultilineString, LiteralBlockScalar, FoldedBlockScalar)

    val buffer = ArrayBuffer[FoldingDescriptor]()
    val iterator = depthFirst(node)
    while (iterator.hasNext) {
      val n = iterator.next()
      if (foldableTypes.contains(n.getElementType) && n.getTextLength > 0) {
        buffer += new FoldingDescriptor(n, foldRange(n, document))
      }
    }
    buffer.toArray
  }

  // BlockObject/BlockArray have no brace/bracket to anchor the fold to the key's line - unlike `a: { ... }`,
  // the node itself starts on the value's own line (e.g. the first `- item` or nested field), one line below
  // `a:`. Left as n.getTextRange, a single-line block value (e.g. `a:\n  b: 2`) would report the same start
  // and end line and never fold, and a multi-line one would show its gutter icon a line too low. Widening the
  // range back to the end of the key's own line fixes both.
  private def foldRange(n: ASTNode, document: Document): TextRange = {
    val end = n.getTextRange.getEndOffset
    if (!blockTypes.contains(n.getElementType)) new TextRange(n.getStartOffset, end)
    else {
      val startLine = document.getLineNumber(n.getStartOffset)
      val start = if (startLine > 0) document.getLineEndOffset(startLine - 1) else n.getStartOffset
      new TextRange(start, end)
    }
  }

  def isCollapsedByDefault(node: ASTNode) =
    false

  def getPlaceholderText(node: ASTNode): String = node.getElementType match {
    case Object => "{...}"
    case Array => "[...]"
    case BlockObject => "{...}"
    case BlockArray => "[...]"
    case MultilineString => "\"\"\"...\"\"\""
    case LiteralBlockScalar => "|..."
    case FoldedBlockScalar => ">..."
  }

  private def depthFirst(root: ASTNode): Iterator[ASTNode] = new DepthFirstIterator(root)

  private class DepthFirstIterator(node: ASTNode) extends Iterator[ASTNode] {
    private val stack = mutable.Stack[ASTNode](node)

    def hasNext: Boolean = stack.nonEmpty

    def next(): ASTNode = {
      val element = stack.pop()
      pushChildren(element)
      element
    }

    def pushChildren(element: ASTNode): Unit = {
      var child = element.getLastChildNode
      while (child != null) {
        stack.push(child)
        child = child.getTreePrev
      }
    }
  }

}

package org.jetbrains.plugins.hocon
package lexer

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType

import scala.annotation.{switch, tailrec}

object HoconLexer {

  case class State(raw: Int) extends AnyVal

  final val Initial = State(0)
  final val Value = State(1)
  final val SubStarting = State(2)
  final val SubStarted = State(3)
  final val Substitution = State(4)

  final val ForbiddenChars = """$"{}[]:=,+#`^?!@*&\"""
  final val UnquotedSpecialChars = """.()"""
  final val KeyForbiddenChars = ForbiddenChars + '.'
  final val SpecialWhitespace = "\u00A0\u2007\u202F\uFEFF"

  def isHoconWhitespace(char: Char): Boolean =
    char.isWhitespace || SpecialWhitespace.contains(char)
}

class HoconLexer extends LexerBase {

  import org.jetbrains.plugins.hocon.lexer.HoconLexer.*
  import org.jetbrains.plugins.hocon.lexer.HoconTokenType.*

  private def onContents(state: State): State = state match {
    case Initial | SubStarting => Value
    case SubStarted => Substitution
    case _ => state
  }

  private def onDollar(state: State): State = state match {
    case Initial | Value => SubStarting
    case SubStarted => Substitution
    case _ => state
  }

  private def onWhitespace(state: State, newLine: Boolean): State = state match {
    case _ if newLine => Initial
    case SubStarting => Value
    case SubStarted => Substitution
    case _ => state
  }

  private var input: CharSequence = _
  private var endOffset: Int = _
  private var stateBefore: State = Initial
  private var stateAfter: State = Initial

  private var tokenStart: Int = _
  private var tokenEnd: Int = _
  private var token: IElementType = _

  private def setNewToken(newToken: HoconTokenType, length: Int, newState: State): Unit = {
    tokenEnd = tokenStart + length
    token = newToken
    stateBefore = stateAfter
    stateAfter = newState
  }

  def getBufferEnd: Int = endOffset

  def getBufferSequence: CharSequence = input

  def advance(): Unit = {
    @tailrec def indexOfNewlineOrEof(idx: Int): Int =
      if (idx >= endOffset || input.charAt(idx) == '\n') idx
      else indexOfNewlineOrEof(idx + 1)

    def continuesUnquotedChars(seq: CharSequence, index: Int): Boolean = index < endOffset && {
      val char = seq.charAt(index)
      !UnquotedSpecialChars.contains(char) && !ForbiddenChars.contains(char) && !isHoconWhitespace(char) &&
      (char != '/' || index + 1 >= endOffset || seq.charAt(index + 1) != '/')
    }

    @tailrec def drainUnquoted(idx: Int): Int =
      if (!continuesUnquotedChars(input, idx)) idx
      else drainUnquoted(idx + 1)

    tokenStart = tokenEnd
    if (endOffset > tokenStart) {
      (input.charAt(tokenStart): @switch) match {
        case '$' => setNewToken(Dollar, 1, onDollar(stateAfter))
        case '?' if stateAfter == SubStarted => setNewToken(QMark, 1, Substitution)
        case '{' =>
          stateAfter match {
            case SubStarting => setNewToken(SubLBrace, 1, SubStarted)
            case _ => setNewToken(LBrace, 1, Initial)
          }
        case '}' =>
          stateAfter match {
            case SubStarted | Substitution => setNewToken(SubRBrace, 1, Value)
            case _ => setNewToken(RBrace, 1, Value)
          }
        case '[' => setNewToken(LBracket, 1, Initial)
        case ']' => setNewToken(RBracket, 1, Value)
        case '(' => setNewToken(LParen, 1, Initial)
        case ')' => setNewToken(RParen, 1, Value)
        case ':' => setNewToken(Colon, 1, Initial)
        case ',' => setNewToken(Comma, 1, Initial)
        case '=' => setNewToken(Equals, 1, Initial)
        case '+' if input.containsAt(tokenStart, "+=") =>
          setNewToken(PlusEquals, 2, Initial)
        case '.' => setNewToken(Period, 1, onContents(stateAfter))
        case '#' => setNewToken(HashComment, indexOfNewlineOrEof(tokenStart) - tokenStart, stateAfter)
        case '/' if input.containsAt(tokenStart, "//") =>
          setNewToken(DoubleSlashComment, indexOfNewlineOrEof(tokenStart) - tokenStart, stateAfter)

        case '\"' if input.containsAt(tokenStart, "\"\"\"") =>
          val strWithoutOpening = input.subSeqView(tokenStart + 3)
          val length = HoconConstants.MultilineStringEnd
            .findFirstMatchIn(strWithoutOpening)
            .map(m => m.end + 3)
            .getOrElse(endOffset - tokenStart)
          setNewToken(MultilineString, length, onContents(stateAfter))

        case '\"' =>
          @tailrec
          def drainQuoted(offset: Int, escaping: Boolean): Int =
            if (offset >= endOffset) offset
            else {
              input.charAt(offset) match {
                case '\n' => offset
                case '\"' if !escaping => offset + 1
                case '\\' if !escaping => drainQuoted(offset + 1, escaping = true)
                case _ => drainQuoted(offset + 1, escaping = false)
              }
            }
          val length = drainQuoted(tokenStart + 1, escaping = false) - tokenStart
          setNewToken(QuotedString, length, onContents(stateAfter))

        case c @ ('|' | '>') =>
          blockScalarTokenLength(tokenStart, c) match {
            case Some(length) =>
              val tokenType = if (c == '|') LiteralBlockScalar else FoldedBlockScalar
              setNewToken(tokenType, length, onContents(stateAfter))
            case None =>
              val length = drainUnquoted(tokenStart) - tokenStart
              setNewToken(UnquotedChars, length, onContents(stateAfter))
          }

        case c if isHoconWhitespace(c) =>
          var idx = tokenStart
          var nl = false
          while (idx < endOffset && isHoconWhitespace(input.charAt(idx))) {
            nl ||= input.charAt(idx) == '\n'
            idx += 1
          }
          val token = if (nl) LineBreakingWhitespace else InlineWhitespace
          setNewToken(token, idx - tokenStart, onWhitespace(stateAfter, nl))

        case _ if continuesUnquotedChars(input, tokenStart) =>
          val length = drainUnquoted(tokenStart) - tokenStart
          setNewToken(UnquotedChars, length, onContents(stateAfter))

        case _ =>
          setNewToken(BadCharacter, 1, stateAfter)
      }
    } else {
      stateBefore = Initial
      stateAfter = Initial
      token = null
    }
  }

  // A block scalar header ('|' or '>', optionally followed by a '-'/'+' chomp indicator) is only recognized
  // when nothing but inline whitespace and/or a trailing '#'/'//' comment follows it to end of line - anything
  // else (e.g. `foo: |bar`, `a | b`) is left for ordinary UnquotedChars draining. When valid, scans forward
  // through the indented body: the first non-blank body line fixes the block's base indentation (must exceed
  // the header line's own first-token column); the block continues through lines indented at least that much
  // (blank lines are tentatively included, superseded by further content or kept if the block ends via EOF or
  // dedent right after them - chomping is applied later at the PSI layer, not here) and ends at the first
  // under-indented non-blank line, or at EOF. Returns the length of the combined header+body token.
  private def blockScalarTokenLength(headerStart: Int, headerChar: Char): Option[Int] = {
    @tailrec def indexOfNewlineOrEof(idx: Int): Int =
      if (idx >= endOffset || input.charAt(idx) == '\n') idx
      else indexOfNewlineOrEof(idx + 1)

    val hasChomp = headerStart + 1 < endOffset &&
      (input.charAt(headerStart + 1) == '-' || input.charAt(headerStart + 1) == '+')
    val afterHeader = headerStart + (if (hasChomp) 2 else 1)

    @tailrec def headerTailValid(idx: Int): Boolean =
      if (idx >= endOffset) true
      else input.charAt(idx) match {
        case '\n' => true
        case '#' => true
        case '/' if idx + 1 < endOffset && input.charAt(idx + 1) == '/' => true
        case ch if isHoconWhitespace(ch) => headerTailValid(idx + 1)
        case _ => false
      }

    if (!headerTailValid(afterHeader)) None
    else {
      @tailrec def lineStartOf(idx: Int): Int =
        if (idx <= 0 || input.charAt(idx - 1) == '\n') idx else lineStartOf(idx - 1)

      @tailrec def firstNonWsColumn(idx: Int, col: Int): Int =
        if (idx < endOffset && input.charAt(idx) != '\n' && isHoconWhitespace(input.charAt(idx)))
          firstNonWsColumn(idx + 1, col + 1)
        else col

      val parentIndent = firstNonWsColumn(lineStartOf(headerStart), 0)
      val headerLineEnd = indexOfNewlineOrEof(afterHeader)
      val bodyStart = headerLineEnd + 1

      // A line ending right before the block's own terminating dedent must not have its trailing '\n'
      // swallowed into the token - that newline has to stay for ordinary whitespace tokenization, or the
      // parser's newline-before-current-token tracking (used right after a block scalar value, e.g. to
      // recognize a following block-array '-' item) breaks. Not applied to the empty-block case (confirmedEnd
      // still its initial headerLineEnd, which already points at - not past - that line's '\n').
      def trimTrailingNewline(end: Int): Int =
        if (end > bodyStart && input.charAt(end - 1) == '\n') end - 1 else end

      // baseIndent < 0 means "not yet fixed by a non-blank body line". Blank lines never advance confirmedEnd
      // themselves (only real content lines do) - they're purely tentative, only ending up included in the
      // token if the scan reaches true EOF (nothing follows, so no boundary to protect); on a dedent they're
      // discarded along with the newline-trim above.
      @tailrec def scanLines(lineStart: Int, baseIndent: Int, confirmedEnd: Int): Int =
        if (lineStart >= endOffset) endOffset
        else {
          val nlOrEof = indexOfNewlineOrEof(lineStart)
          var i = lineStart
          while (i < nlOrEof && isHoconWhitespace(input.charAt(i))) i += 1
          val lineEndInclusive = if (nlOrEof < endOffset) nlOrEof + 1 else nlOrEof
          if (i >= nlOrEof) {
            scanLines(lineEndInclusive, baseIndent, confirmedEnd)
          } else {
            val indent = i - lineStart
            if (baseIndent < 0) {
              if (indent > parentIndent) scanLines(lineEndInclusive, indent, lineEndInclusive)
              else confirmedEnd
            } else if (indent >= baseIndent) {
              scanLines(lineEndInclusive, baseIndent, lineEndInclusive)
            } else trimTrailingNewline(confirmedEnd)
          }
        }

      Some(scanLines(bodyStart, -1, headerLineEnd) - headerStart)
    }
  }

  def getTokenEnd: Int = tokenEnd

  def getTokenStart: Int = tokenStart

  def getTokenType: IElementType = {
    if (token == null) {
      advance()
    }
    token
  }

  def getState: Int = stateBefore.raw

  def start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int): Unit = {
    this.token = null
    this.input = buffer
    this.tokenStart = startOffset
    this.tokenEnd = startOffset
    this.endOffset = endOffset
    this.stateBefore = State(initialState)
  }
}

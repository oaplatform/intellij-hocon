package org.jetbrains.plugins.hocon
package parser

import lexer.HoconTokenSets.*
import lexer.HoconTokenType.*
import parser.HoconElementType.*

import java.net.{MalformedURLException, URI}
import com.intellij.lang.*
import com.intellij.lang.PsiBuilder.Marker
import com.intellij.lang.WhitespacesAndCommentsBinder.TokenTextGetter
import com.intellij.psi.tree.IElementType

import scala.annotation.tailrec
import scala.util.matching.Regex

class HoconPsiParser extends PsiParser {

  def parse(root: IElementType, builder: PsiBuilder): ASTNode = {
    val file = builder.mark()
    new Parser(builder).parseFile()
    file.done(root)
    builder.getTreeBuilt
  }

  class Parser(builder: PsiBuilder) {

    object DocumentationCommentsBinder extends WhitespacesAndCommentsBinder {
      override def getEdgePosition(tokens: JList[? <: IElementType], atStreamEdge: Boolean, getter: TokenTextGetter)
        : Int = {

        @tailrec
        def goThrough(commentToken: IElementType, resultSoFar: Int, i: Int): Int = {
          def token = tokens.get(i)

          def text = getter.get(i)

          def entireLineComment =
            token == commentToken && (if (i > 0) tokens.get(i - 1) == LineBreakingWhitespace else atStreamEdge)

          def noBlankLineWhitespace =
            Whitespace.contains(token) && text.charIterator.count(_ == '\n') <= 1

          if (i < 0) resultSoFar
          else if (noBlankLineWhitespace) goThrough(commentToken, resultSoFar, i - 1)
          else if (entireLineComment) goThrough(commentToken, i, i - 1)
          else resultSoFar
        }

        val dsCommentsStart = goThrough(DoubleSlashComment, tokens.size, tokens.size - 1)
        goThrough(HashComment, dsCommentsStart, dsCommentsStart - 1)
      }
    }

    // beware of rollbacks!
    var newLineSuppressedIndex: Int = 0

    def newLinesBeforeCurrentToken: Boolean =
      builder.rawTokenIndex > newLineSuppressedIndex && builder.rawLookup(-1) == LineBreakingWhitespace

    def suppressNewLine(): Unit = {
      newLineSuppressedIndex = builder.rawTokenIndex
    }

    def advanceLexer(): Unit =
      builder.advanceLexer()

    def matches(matcher: Matcher): Boolean =
      (matcher.tokenSet.contains(builder.getTokenType) && (!matcher.requireNoNewLine || !newLinesBeforeCurrentToken)) ||
        (matcher.matchNewLine && newLinesBeforeCurrentToken) || (matcher.matchEof && builder.eof)

    def matchesUnquoted(str: String): Boolean =
      matches(UnquotedChars) && builder.getTokenText == str

    def matchesUnquoted(pattern: Regex): Boolean =
      matches(UnquotedChars) && pattern.pattern.matcher(builder.getTokenText).matches

    def pass(matcher: Matcher): Boolean = {
      val result = matches(matcher)
      if (result && (!matcher.matchNewLine || !newLinesBeforeCurrentToken) && (!matcher.matchEof || !builder.eof)) {
        advanceLexer()
      }
      result
    }

    def errorUntil(matcher: Matcher, msg: String, onlyNonEmpty: Boolean = false): Unit = {
      if (!onlyNonEmpty || !matches(matcher)) {
        val marker = builder.mark()
        while (!matches(matcher)) {
          builder.advanceLexer()
        }
        marker.error(msg)
      }
    }

    def tokenError(msg: String): Unit = {
      val marker = builder.mark()
      builder.advanceLexer()
      marker.error(msg)
    }

    def setEdgeTokenBinders(marker: Marker, nonGreedyLeft: Boolean, nonGreedyRight: Boolean): Unit = {
      import com.intellij.lang.WhitespacesBinders.*
      marker.setCustomEdgeTokenBinders(
        if (nonGreedyLeft) DEFAULT_LEFT_BINDER else GREEDY_LEFT_BINDER,
        if (nonGreedyRight) DEFAULT_RIGHT_BINDER else GREEDY_RIGHT_BINDER,
      )
    }

    def parseFile(): Unit = {
      if (matches(LBrace))
        parseObject()
      else if (matches(LBracket))
        parseArray()
      else
        parseObjectEntries(insideObject = false)
      errorUntil(Empty.orEof, "expected end of file", onlyNonEmpty = true)
    }

    def parseStringLiteral(stringType: HoconElementType): Unit = {
      val marker = builder.mark()

      val unclosedQuotedString = builder.getTokenType == QuotedString &&
        !HoconConstants.ProperlyClosedQuotedString.pattern.matcher(builder.getTokenText).matches
      val unclosedMultilineString = builder.getTokenType == MultilineString && !builder.getTokenText.endsWith("\"\"\"")

      advanceLexer()

      if (unclosedQuotedString) {
        builder.error("unclosed quoted string")
      } else if (unclosedMultilineString) {
        builder.error("unclosed multiline string")
      }

      marker.done(stringType)
    }

    def parseObject(): Unit = {
      val marker = builder.mark()

      advanceLexer()
      parseObjectEntries(insideObject = true)
      if (!pass(RBrace)) {
        builder.error("expected '}'")
      }

      marker.done(Object)
    }

    def parseObjectEntries(insideObject: Boolean): Unit = {
      val marker = builder.mark()

      while (!matches(RBrace.orEof)) {
        if (matches(ObjectEntryStart)) {
          parseObjectEntry()
          pass(Comma)
        } else {
          tokenError("expected object field" + (if (insideObject) ", include or '}'" else " or include"))
        }
      }

      marker.done(ObjectEntries)
      setEdgeTokenBinders(marker, nonGreedyLeft = false, nonGreedyRight = false)
    }

    def parseObjectEntry(): Unit = {
      if (matchesUnquoted(HoconConstants.Include))
        parseInclude()
      else
        parseObjectField()
      errorUntil(ValueEnding.orNewLineOrEof, "unexpected token", onlyNonEmpty = true)
    }

    def parseInclude(): Unit = {
      val marker = builder.mark()
      advanceLexer()
      parseIncluded()
      marker.done(Include)

      marker.setCustomEdgeTokenBinders(DocumentationCommentsBinder, WhitespacesBinders.DEFAULT_RIGHT_BINDER)
    }

    def parseIncluded(): Unit = {
      val marker = builder.mark()

      if (matchesUnquoted(HoconConstants.RequiredModifer)) {
        advanceLexer()
        if (matches(LParen) && !Whitespace.contains(builder.rawLookup(-1))) {
          advanceLexer()
          parseQualifiedIncluded()
          if (matches(RParen)) {
            advanceLexer()
          } else errorUntil(ValueEnding.orNewLineOrEof, "expected ')'")
        } else errorUntil(ValueEnding.orNewLineOrEof, "expected '(' immediately after 'required'")
      } else {
        parseQualifiedIncluded()
      }

      marker.done(Included)
    }

    def parseQualifiedIncluded(): Unit = {
      val marker = builder.mark()

      if (matches(QuotedString)) {
        parseStringLiteral(IncludeTarget)
      } else if (HoconConstants.IncludeLocationModifiers.exists(matchesUnquoted)) {
        val qualifier = builder.getTokenText
        advanceLexer()
        if (matches(LParen) && !Whitespace.contains(builder.rawLookup(-1))) {
          advanceLexer()
          if (matches(QuotedString)) {
            if (qualifier == HoconConstants.UrlModifier) {
              try {
                URI.create(unquote(builder.getTokenText)).toURL
                parseStringLiteral(IncludeTarget)
              } catch {
                case e @ (_: MalformedURLException | _: IllegalArgumentException) =>
                  tokenError(if (e.getMessage != null) e.getMessage else "malformed URL")
              }
            } else {
              parseStringLiteral(IncludeTarget)
            }
            if (matches(RParen)) {
              advanceLexer()
            } else errorUntil(ValueEnding.orNewLineOrEof, "expected ')'")
          } else errorUntil(ValueEnding.orNewLineOrEof, "expected quoted string")
        } else errorUntil(ValueEnding.orNewLineOrEof, s"expected '(' immediately after '$qualifier'")
      } else
        errorUntil(
          ValueEnding.orNewLineOrEof,
          "expected quoted string, optionally wrapped in 'url(...)', 'file(...)' or 'classpath(...)'",
        )

      marker.done(QualifiedIncluded)
    }

    def parseObjectField(): Unit = {
      val marker = builder.mark()
      parseKeyedField(true)
      marker.done(ObjectField)

      marker.setCustomEdgeTokenBinders(DocumentationCommentsBinder, WhitespacesBinders.DEFAULT_RIGHT_BINDER)
    }

    def parseKeyedField(first: Boolean, keyStartColumn: Int = -1): Unit = {
      // Column of the field's own outermost key (e.g. `a` in `a.b.c = ...`), used as the reference column
      // a block-array value's `-` markers must be indented past. Computed before the key is consumed.
      val startColumn = if (first) currentColumn() else keyStartColumn

      if (first) {
        suppressNewLine()
      }

      val marker = builder.mark()
      tryParseKey(first, substitution = false)

      if (pass(Period.noNewLine)) {
        parseKeyedField(first = false, startColumn)
        marker.done(PrefixedField)
      } else {
        if (matches(LBrace)) {
          parseObject()
        } else if (pass(KeyValueSeparator)) {
          if (matchesBlockArrayDash(startColumn)) {
            parseBlockArrayValue(startColumn)
          } else if (matches(ValueStart)) {
            parseValue()
          } else {
            errorUntil(ValueEnding.orNewLineOrEof, "expected value for object field")
          }
        } else errorUntil(ValueEnding.orNewLineOrEof, "expected ':', '=', '+=' or object")
        marker.done(ValuedField)
      }

      setEdgeTokenBinders(marker, first, nonGreedyRight = true)
    }

    def parsePath(prefixMarker: Option[Marker] = None): Unit = {
      val first = prefixMarker.isEmpty
      if (first) {
        suppressNewLine()
      }

      if (!matches(PathEnding.orNewLineOrEof)) {
        if (!first) {
          pass(Period.noNewLine)
        }
        val marker = prefixMarker.map(_.precede()).getOrElse(builder.mark())
        tryParseKey(first, substitution = true)
        marker.done(Path)
        parsePath(Some(marker))
      }
    }

    def tryParseKey(first: Boolean, substitution: Boolean): Unit = {
      if (!matches(KeyEnding.orNewLineOrEof)) {
        parseKey(first, substitution)
      } else {
        builder.error("expected key (use quoted \"\" if you want empty key)")
      }
    }

    def parseKey(first: Boolean, substitution: Boolean): Unit = {
      val marker = builder.mark()

      @tailrec
      def parseKeyParts(first: Boolean): Unit = {
        if (!matches(KeyEnding.orNewLineOrEof)) {
          if (matches(UnquotedChars)) {
            parseUnquotedString(KeyPart, UnquotedChars.noNewLine, first, PathEnding.orNewLineOrEof)
          } else if (matches(StringLiteral)) {
            parseStringLiteral(KeyPart)
          } else {
            tokenError(
              "key must be a concatenation of unquoted, quoted or multiline strings " +
                "(characters $ \" { } [ ] : = , + # ` ^ ? ! @ * & \\ are forbidden unquoted)"
            )
          }
          parseKeyParts(first = false)
        }
      }

      suppressNewLine()
      parseKeyParts(first)

      marker.done(if (substitution) SubstitutionKey else FieldKey)

      setEdgeTokenBinders(marker, first, matches(PathEnding.orNewLineOrEof))
    }

    def parseUnquotedString(
      stringType: HoconElementType,
      matcher: Matcher,
      nonGreedyLeft: Boolean,
      nonGreedyRightMatcher: Matcher,
    ): Unit = {
      val stringMarker = builder.mark()
      val marker = builder.mark()
      suppressNewLine()
      while (matches(matcher)) {
        advanceLexer()
      }
      marker.done(UnquotedString)
      setEdgeTokenBinders(marker, nonGreedyLeft, matches(nonGreedyRightMatcher))
      stringMarker.done(stringType)
      setEdgeTokenBinders(stringMarker, nonGreedyLeft, matches(nonGreedyRightMatcher))
    }

    def parseValue(): Unit = {
      def tryParse(parsingCode: => Boolean, element: HoconElementType): Boolean = {
        val marker = builder.mark()
        if (parsingCode) {
          marker.done(element)
          true
        } else {
          marker.rollbackTo()
          false
        }
      }

      def passKeyword(kw: String) =
        if (matchesUnquoted(kw)) {
          advanceLexer()
          true
        } else
          false

      val endingMatcher = ValueEnding.orNewLineOrEof

      def tryParseNull = tryParse(passKeyword(HoconConstants.Null) && matches(endingMatcher), Null)

      def tryParseBoolean = tryParse(
        (passKeyword(HoconConstants.True) || passKeyword(HoconConstants.False)) && matches(endingMatcher),
        Boolean,
      )

      def tryParseNumber = tryParse(passNumber() && matches(endingMatcher), Number)

      @tailrec
      def parseValueParts(partCount: Int): Int =
        if (!matches(endingMatcher)) {
          if (matches(LBrace)) {
            parseObject()
          } else if (matches(LBracket)) {
            parseArray()
          } else if (matches(Dollar) && builder.lookAhead(1) == SubLBrace) {
            parseSubstitution()
          } else if (matches(ValueUnquotedChars)) {
            parseUnquotedString(StringValue, ValueUnquotedChars.noNewLine, partCount == 0, ValueEnding.orNewLineOrEof)
          } else if (matches(StringLiteral)) {
            parseStringLiteral(StringValue)
          } else {
            tokenError("characters $ \" { } [ ] : = , + # ` ^ ? ! @ * & \\ are forbidden unquoted")
          }
          parseValueParts(partCount + 1)
        } else partCount

      suppressNewLine()
      if (!tryParseNull && !tryParseBoolean && !tryParseNumber) {
        val marker = builder.mark()
        val parts = parseValueParts(0)
        if (parts > 1) {
          marker.done(Concatenation)
        } else {
          marker.drop()
        }

      }

    }

    def passNumber(): Boolean = matchesUnquoted(HoconConstants.IntegerPattern) && {
      val textBuilder = new StringBuilder
      // we need to detect whitespaces between tokens forming a number to behave as if number is a single token
      val integerRawTokenIdx = builder.rawTokenIndex
      textBuilder ++= builder.getTokenText
      advanceLexer()

      val gotPeriod = matches(Period)
      val noPeriodWhitespace = gotPeriod && builder.rawTokenIndex == integerRawTokenIdx + 1

      if (gotPeriod) {
        textBuilder ++= builder.getTokenText
        advanceLexer()
      }

      val gotDecimalPart = gotPeriod && matchesUnquoted(HoconConstants.DecimalPartPattern)
      val noDecimalPartWhitespace = gotDecimalPart && builder.rawTokenIndex == integerRawTokenIdx + 2

      if (gotDecimalPart) {
        textBuilder ++= builder.getTokenText
        advanceLexer()
      }

      lazy val isValid = {
        val text = textBuilder.result()
        try {
          if (gotPeriod) text.toDouble else text.toLong.toDouble
          true
        } catch {
          case _: NumberFormatException => false
        }
      }

      (!gotPeriod || noPeriodWhitespace) && (!gotDecimalPart || noDecimalPartWhitespace) && isValid
    }

    // Column (0-based, tabs counted as 1) of the token the builder is currently positioned at, computed by
    // scanning back to the preceding '\n' in the raw source text. Used only by the block-array grammar -
    // nothing else in this parser is indentation-sensitive.
    def currentColumn(): Int = {
      val text = builder.getOriginalText
      val offset = builder.getCurrentOffset
      var i = offset - 1
      var col = 0
      while (i >= 0 && text.charAt(i) != '\n') {
        i -= 1
        col += 1
      }
      col
    }

    // A standalone '-' token (i.e. not fused with following chars like `-item`/`-5`), immediately followed
    // by whitespace or EOF (so `-{` is deliberately never recognized as a block-array marker).
    def isBlockArrayDash: Boolean =
      matchesUnquoted("-") && {
        val next = builder.rawLookup(1)
        next == null || Whitespace.contains(next)
      }

    // Whether the current position starts a block-array item list for a field whose own key started at
    // minColumn: a '-' marker, on its own line, indented further than the field's key.
    //
    // isBlockArrayDash is checked first (it touches builder.getTokenType() via matchesUnquoted): PsiBuilder's
    // rawLookup/rawTokenIndex - which newLinesBeforeCurrentToken depends on - only reflect the current
    // position correctly once getTokenType() has been queried there at least once. Every other caller of
    // newLinesBeforeCurrentToken goes through matches(), whose own tokenSet.contains(getTokenType) check
    // always runs first; this is the one place that isn't already guarded that way, so the order matters.
    def matchesBlockArrayDash(minColumn: Int): Boolean =
      isBlockArrayDash && newLinesBeforeCurrentToken && currentColumn() > minColumn

    // Pure lookahead (always rolls back): does the content right after a '-' marker look like a key
    // (optionally dotted) followed by '{' or a key/value separator - i.e. should this item be parsed as an
    // implicit object field rather than a plain scalar/array/object value? Any imprecision here can only
    // misclassify malformed input, since the real parse afterwards always goes through the unmodified
    // parseBlockObjectEntries/parseValue productions.
    def looksLikeObjectFieldStart(): Boolean = {
      val trial = builder.mark()

      @tailrec
      def consumeKeyParts(gotAny: Boolean): Boolean =
        if (matches(UnquotedChars.noNewLine) || matches(StringLiteral.noNewLine)) {
          advanceLexer()
          consumeKeyParts(gotAny = true)
        } else if (gotAny && pass(Period.noNewLine)) {
          consumeKeyParts(gotAny = true)
        } else gotAny

      val gotKey = consumeKeyParts(gotAny = false)
      val result = gotKey && (matches(LBrace) || matches(KeyValueSeparator))
      trial.rollbackTo()
      result
    }

    // Sequence of `-`-prefixed items, one per line, each indented further than minColumn (the enclosing
    // field's own key column). Ends at EOF, a non-dash token, or a dash indented at or before minColumn.
    def parseBlockArrayValue(minColumn: Int): Unit = {
      val marker = builder.mark()

      while (matchesBlockArrayDash(minColumn)) {
        advanceLexer() // consume '-'
        if (looksLikeObjectFieldStart()) {
          val itemColumn = currentColumn()
          val objMarker = builder.mark()
          parseBlockObjectEntries(itemColumn)
          objMarker.done(BlockObject)
        } else if (matches(ValueStart)) {
          parseValue()
        } else {
          errorUntil(ValueEnding.orNewLineOrEof, "expected array element value after '-'")
        }
      }

      marker.done(BlockArray)
    }

    // The implicit object formed by one block-array item: its first field (on the '-' marker's own line)
    // plus every following field indented to exactly itemColumn (the first field's own column). Reuses
    // parseObjectField verbatim, so nested prefixed keys, doc comments and nested values all work unchanged.
    def parseBlockObjectEntries(itemColumn: Int): Unit = {
      val marker = builder.mark()

      parseObjectField()
      // matches() first: see matchesBlockArrayDash for why newLinesBeforeCurrentToken must not be the first
      // getTokenType()-touching call at a fresh position.
      while (matches(ObjectEntryStart) && newLinesBeforeCurrentToken && currentColumn() == itemColumn) {
        parseObjectField()
      }

      marker.done(ObjectEntries)
    }

    def parseArray(): Unit = {
      val marker = builder.mark()
      advanceLexer()

      while (!matches(ArrayElementsEnding.orEof)) {
        if (matches(ValueStart)) {
          parseValue()
          pass(Comma)
        } else {
          tokenError("expected array element or ']'")
        }
      }

      if (!pass(RBracket)) {
        builder.error("expected ']'")
      }

      marker.done(Array)
    }

    def parseSubstitution(): Unit = {
      val marker = builder.mark()
      advanceLexer()
      advanceLexer()
      pass(QMark)
      if (matches(SubstitutionPathStart.noNewLine)) {
        parsePath()
        if (!pass(SubRBrace)) {
          builder.error("expected '}'")
        }
      } else errorUntil(PathEnding.orNewLineOrEof, "expected path expression")
      pass(SubRBrace)

      marker.done(Substitution)
    }

  }

}

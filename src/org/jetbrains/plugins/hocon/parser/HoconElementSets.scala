package org.jetbrains.plugins.hocon
package parser

import com.intellij.psi.TokenType

object HoconElementSets {

  import org.jetbrains.plugins.hocon.parser.HoconElementType.*

  final val KeyedField =
    PrefixedField | ValuedField
  final val Literal =
    Null | Boolean | Number | StringValue
  final val Value =
    Literal | Object | BlockObject | Array | BlockArray | Substitution | Concatenation | ClasspathReference
  final val ForcedLeafBlock =
    FieldKey | SubstitutionKey | Path | UnquotedString | Number | Null | Boolean | TokenType.ERROR_ELEMENT
}

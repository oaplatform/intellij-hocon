package org.jetbrains.plugins.hocon
package semantics

import org.jetbrains.plugins.hocon.psi.{HAnchorDef, HoconPsiElement, HoconPsiFile}

/** Lookup for YAML-style anchor definitions (`&name`, non-standard OAP extension - see `HoconElementType.AnchorDef`).
  * Deliberately file-scoped and name-based only, not integrated into `ResolutionCtx`'s include/candidate-file
  * graph like substitutions are - anchors do not resolve across files.
  */
object HoconAnchors {
  def anchorsInFile(file: HoconPsiFile): Iterator[HAnchorDef] =
    file.depthFirst.collectOnly[HAnchorDef]

  /** The anchor named `name` nearest before `from` in the file (by text offset), falling back to the last matching
    * anchor anywhere in the file if none precede it - lenient during editing, when definition order is transiently
    * wrong (e.g. an alias typed above its anchor while both are still being written).
    */
  def findAnchor(file: HoconPsiFile, from: HoconPsiElement, name: String): Option[HAnchorDef] = {
    val candidates = anchorsInFile(file).filter(_.name.contains(name)).toVector
    candidates.filter(_.startOffset < from.startOffset).lastOption.orElse(candidates.lastOption)
  }
}

# IntelliJ IDEA Plugin for [HOCON](https://github.com/oaplatform/intellij-hocon/blob/master/README.md)

[Plugin page](https://plugins.jetbrains.com/plugin/10481-hocon)

## Contents

* [Features and usage instructions](#features-and-usage-instructions)
* [OAP extensions (non-standard)](#oap-extensions-non-standard)
  * [Block arrays](#block-arrays)
  * [Block objects](#block-objects)
  * [Block scalars](#block-scalars)
  * [`classpath(...)` value construct](#classpath-value-construct)

## Features and usage instructions

* HOCON file type

  Files with `*.conf` extensions are automatically interpreted as HOCON files. Because `*.conf` is a common extension,
  you can change this in `File -> Settings -> Editor -> File Types`.
  
* Syntax highlighting, brace matching and code folding

  ![syntaxhighligting.png](img/syntaxhighlighting.png)
  
* Configurable color scheme in `File -> Settings -> Editor -> Color Scheme -> HOCON`

* Code formatter (the IntelliJ **Reformat Code** action) along with configurable code style in 
  `File -> Settings -> Editor -> Code Style -> HOCON`
  
* Breadcrumbs
  
  ![bradcrumbs.png](img/breadcrumbs.png)
  
* **Copy Reference** action

  Use this action (e.g. using `Ctrl+Alt+Shift+C`) when having the caret at HOCON key in order to copy full path
  of the HOCON property.
  
* **Move Statement Up/Down** action which can also move entries in and out of objects

  ![movestatement.gif](img/movestatement.gif)
  
* Resolution of HOCON includes with navigation

  ![includeresolution.gif](img/includeresolution.gif)
  
* Resolution of HOCON substitutions with navigation

  Substitutions are resolved in the current file, all the included files and finally in all the `reference.conf` files
  that can be found on the classpath (e.g. in library JARs). This reflects the way HOCON is usually loaded and resolved
  in runtime using [`ConfigFactory.load`](https://lightbend.github.io/config/latest/api/com/typesafe/config/ConfigFactory.html#load-com.typesafe.config.Config-) using [Typesafe/Lightbend Config](https://github.com/lightbend/config/) library.
  
  ![substitutionresolution.gif](img/substitutionresolution.gif)
  
* **HOCON: Go to Prev/Next Definition** actions for navigating between subsequent occurrences of the same HOCON path:

  These actions reuse keyboard shortcuts of standard **Super Method** and **Implementation(s)** actions
  (e.g. `Ctrl+U` and `Ctrl+Alt+B`)

  ![gotoprevnext.gif](img/gotoprevnext.gif)
  
* Detection of HOCON path references in string literals of other languages

  **NOTE**: This only works as long as the HOCON path can be resolved in `application.conf` files at the root of the 
  classpath (e.g. `application.conf` must be directly in one of the *sources* or *resources* folders of IntelliJ module).
  
  ![stringlitrefs.gif](img/stringlitrefs.gif)
  
* Detection of Java/Scala fully qualified class references in HOCON strings

  ![classreferences.gif](img/classreferences.gif)

* **Quick Documentation** action

  HOCON has no notion of documentation comments but it has two comment styles - `#` and `//`. Only the `#` comments
  which directly precede a HOCON key will be interpreted as its documentation.
  
  Quick documentation also displays resolved value of a HOCON key.

  ![quickdoc.png](img/quickdoc.png)
  
* **Find Usages** action on HOCON entries

  **Find Usages** looks for all usages of given HOCON path in the project. This may include other definitions of the
  same HOCON path or references to that path in HOCON substitutions or string literals (in HOCON and other languages).
  
* Autocompletion

  Autocompletion works when overriding configuration options and when referring to them in HOCON substitutions.
  Suggestions are loaded from current file, all the included files and all the `reference.conf` files found on the 
  root of the classpath (e.g. in library JARs). This reflects the way HOCON is usually loaded and resolved
  in runtime using [`ConfigFactory.load`](https://lightbend.github.io/config/latest/api/com/typesafe/config/ConfigFactory.html#load-com.typesafe.config.Config-) using [Typesafe/Lightbend Config](https://github.com/lightbend/config/) library.
  
  Autocompletion also works in string literals of other languages but it must be invoked explicitly 
  (e.g. with `Ctrl+Space`) and the path being referred must be resolvable in `application.conf` files at the root
  of the classpath (e.g. `application.conf` must be directly in one of the *sources* or *resources* folders 
  of IntelliJ module).
  
  Autocompletion conveniently displays type and resolved value of suggested HOCON entries and is also integrated
  with **Quick Documentation** action.
  
  ![autocompletion.gif](img/autocompletion.gif)
  
## OAP extensions (non-standard)

This fork adds YAML-style, indentation-delimited syntax for arrays and objects as an alternative to `[...]`
and `{...}`. This is **not** part of the [HOCON spec](https://github.com/lightbend/config/blob/master/HOCON.md)
and isn't understood by `ConfigFactory.load` or any other standard HOCON parser - it's a deliberate extension
for use with [oap-config](https://github.com/oaplatform/config), the Java OAP HOCON implementation these
extensions are modeled after and meant to stay compatible with.

* <a id="block-arrays"></a>**Block arrays** - a `-`-prefixed list item per line, instead of `[...]`:

  ```hocon
  my_array:
    - item1
    - item2

  my_object_array:
    - field1.a = fg
      field2 = 3
  ```

  Multiple key/value lines indented to the same column as an item's first field become an implicit object for
  that item (`field1.a = fg` and `field2 = 3` above form one object).

* <a id="block-objects"></a>**Block objects** - a bracket-less object value, with fields only distinguished by indentation:

  ```hocon
  a:
    v:
      c = 5
      d = 6
  ```

  Both extensions trigger only when nothing else follows the `:`/`=` on the same line, and nest arbitrarily
  (a block array's item may itself have a block-object or block-array field, and vice versa).

* <a id="block-scalars"></a>**Block scalars** - a `|` (literal) or `>` (folded) header line, followed by an indented block of raw text,
  usable anywhere a value is expected (field values, array elements, block-array items):

  ```hocon
  description: |
    line one
    line two

  summary: >
    folded
    onto one line

  raw: |-
    no trailing newline

  arr: [|
    also works as an array element
  ]
  ```

  * The header must be alone on its line (only trailing whitespace/comment allowed), optionally suffixed with
    a chomping indicator: no suffix clips to exactly one trailing newline, `-` strips it entirely, `+` keeps
    all trailing blank lines.
  * Indentation is auto-detected from the first non-blank body line; the block ends at the first line
    indented less than that (or at EOF). More-indented lines keep their extra leading spaces literally.
  * The body is raw text, not re-parsed as HOCON - `#`, quotes, `$`, `{`/`}` etc. inside it are literal
    characters, not syntax.
  * `|` preserves line breaks as-is; `>` folds them into spaces (a blank line becomes one preserved break).

* <a id="classpath-value-construct"></a>**`classpath(...)` value construct** - usable anywhere a HOCON value is expected (field value, array
  element, block-array item, etc.), resolving an unquoted path against the containing module's classpath as
  an exact resource name - like `ClassLoader.getResource(...)` at runtime. Unlike `include classpath("...")`,
  it does *not* guess a `.conf`/`.json`/`.properties` extension when one is missing:

  ```hocon
  a:
    b: classpath(/oap/files/my.resource)
  ```

  Supports navigation (`Ctrl`+click / go-to-declaration) to the resolved resource, is flagged by an
  inspection when the resource cannot be found on the classpath, and stays in sync automatically when the
  target resource is renamed or moved in the IDE. The path is unquoted only (no `classpath("...")` form).
  
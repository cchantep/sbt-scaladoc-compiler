# SBT Scaladoc Compiler

This SBT plugin extracts Scala code examples from Scaladoc comments and compiles them during test compilation, ensuring your documentation stays accurate and up-to-date.

[![CI](https://github.com/cchantep/sbt-scaladoc-compiler/workflows/CI/badge.svg)](https://github.com/cchantep/sbt-scaladoc-compiler/actions)

## Table of Contents

- [Motivation](#motivation)
- [Benefits](#benefits)
- [Get Started](#get-started)
- [Extraction Behaviour](#extraction-behaviour)
- [Troubleshooting](#troubleshooting)
- [Build](#build)

## Motivation

Consider a Scaladoc as below:

```scala
package example

/**
 * Foo
 *
 * {{{
 * import example.Foo
 *
 * Foo.bar()
 * }}}
 */
object Foo {
  def bar(): String = "..."
}
```

Using this SBT plugin, the code samples between `{{{` and `}}}` (in Scaladoc started with `/**` and ended with `*/`, in files `*.scala`) will be extracted as test sources, and so compiled/validated by test compilation.

## Benefits

- **Prevents documentation rot**: Automatically catches outdated examples when your API changes
- **CI/CD integration**: Failed examples break the build, ensuring documentation quality
- **Zero maintenance overhead**: Examples are validated automatically during normal test compilation
- **Confidence**: Ship documentation you can trust

## Get started

**Requirements:**
- SBT 0.13+ or 1.0+
- Scala 2.10+ or 3.x

Update your `project/plugins.sbt`:

```scala
resolvers ++= Seq(
  "Tatami Releases" at "https://raw.github.com/cchantep/tatami/master/releases")

addSbtPlugin("cchantep" % "sbt-scaladoc-compiler" % "0.4")
```

*See a SBT build [using this plugin](https://github.com/ReactiveMongo/Reactivemongo-BSON/blob/master/project/plugins.sbt).*

Then, any time `test:compile` task is executed in SBT, the Scaladoc examples will be compiled along.

**Multi-Project Setup**

In a [Multi-Project build](https://www.scala-sbt.org/1.x/docs/Multi-Project.html), you may need to disable this plugin on the root aggregation project if sub-project examples require dependencies that aren't available at the aggregation level:

```scala
lazy val root = Project(id = "...", base = file(".")).
  aggregate(/* ... */).
  disablePlugins(ScaladocExtractorPlugin)
```

## Extraction behaviour

**What gets extracted:**
- Code blocks between `{{{` and `}}}` in Scaladoc comments (`/**` ... `*/`)
- From `*.scala` files in `sourceDirectories in Compile` (configurable via `scaladocExtractorIncludes`/`scaladocExtractorExcludes`)
- Generated as test sources in `target/scala-<version>/test-src-managed/`

**Requirements for code samples:**

Each code sample needs to be standalone:

- All imports must be explicitly specified
- No calls to private members (snippets can't access private APIs)
- Self-contained examples that compile independently

**Skipping examples:**

To exclude a code example from compilation, use the skip token (default: `"// not compilable"`):

```scala
/**
 * Lorem ipsum
 *
 * {{{
 * // not compilable: some details why (as also displayed in Scaladoc)
 * foo bar ... anyway it's not compiled (if any good reason)
 * }}}
 */
def foo(s: String): Int = s.size
```

**Customizing the skip token:**

```scala
scaladocExtractorSkipToken := "// skip-example"
```

**Filtering files to process:**

By default, all `*.scala` files are processed. You can customize which files are included or excluded:

```scala
// Only process files matching a pattern
scaladocExtractorIncludes := "*.scala"

// Exclude specific files or patterns
scaladocExtractorExcludes := new SimpleFileFilter(_.getName.startsWith("Generated"))

// Example: Only process files in specific directories
scaladocExtractorIncludes := new SimpleFileFilter { file =>
  val path = file.getPath
  path.contains("/api/") || path.contains("/models/")
}

// Example: Exclude test utilities and generated code
scaladocExtractorExcludes := new SimpleFileFilter { file =>
  file.getName.endsWith("Spec.scala") || file.getName.contains("Generated")
}
```

## Troubleshooting

**Viewing generated files:**

Extracted snippets are saved to:
```
target/scala-<version>/test-src-managed/main/scala/scaladocextractor/scaladoc-<SourceFile>-<LineNumber>.scala
```

**Common compilation errors:**

1. **Missing imports**: Ensure all required imports are included in the snippet
2. **Private member access**: Snippets can't access private/protected members
3. **Missing dependencies**: Make sure test dependencies include everything needed by examples

**Debugging:**

```bash
# See extracted files
find target -path "*/scaladocextractor/*.scala"

# Compile with verbose output
sbt "test:compile"
```

**Disabling for specific projects:**

```scala
lazy val myProject = project
  .disablePlugins(ScaladocExtractorPlugin)
```

## Build

This plugin is built using SBT.

```bash
sbt '^ publishLocal'
```

# SBT scaladoc compiler

This SBT plugin extracts the Scala examples from the Scaladoc comments, and compile the extracted code.

## Motivation

Considering a Scaladoc as bellow:

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

## Get started

This plugin requires SBT 0.13+.

You need to update the `project/plugins.sbt`.

```scala
resolvers ++= Seq(
  "Tatami Releases" at "https://raw.github.com/cchantep/tatami/master/releases")

addSbtPlugin("cchantep" % "sbt-scaladoc-compiler" % "0.1")
```

*See a SBT build [using this plugin](https://github.com/ReactiveMongo/Reactivemongo-BSON/blob/master/project/plugins.sbt).*

Then, any time `test:compile` task is executed in SBT, the Scaladoc examples will be compiled along.

## Extraction behaviour

Each code sample need to be standalone:

- all imports explicitly specified,
- no call to private members.

## Build

This is built using SBT.

    sbt '^ publishLocal'

[![Build Status](https://travis-ci.org/cchantep/sbt-scaladoc-compiler.svg?branch=master)](https://travis-ci.org/cchantep/sbt-scaladoc-compiler)

package cchantep

import scala.collection.mutable.Buffer

final class ScaladocExtractorPluginSpec extends org.specs2.mutable.Specification {
  "Scaladoc extractor plugin".title

  "parseString" should {
    "return None if delimiter not found and tail is empty" in {
      val skipTok = "SKIP"
      val line = 1L
      val delimiter = ","
      val head = "abc"
      val tail = Iterator.empty
      val result = ScaladocExtractorPlugin.parseString[String](skipTok, line, delimiter, head, tail)
      result must beNone
    }

    "return Some with dropped head if delimiter is found" in {
      val skipTok = "SKIP"
      val line = 1L
      val delimiter = ","
      val head = "abc,def"
      val tail = Iterator.empty
      val result = ScaladocExtractorPlugin.parseString[String](skipTok, line, delimiter, head, tail)
      result must beSome((1L, "def", tail))
    }

    "return Some with next tail element if delimiter not found but tail hasNext" in {
      val skipTok = "SKIP"
      val line = 1L
      val delimiter = ","
      val head = "abc"
      val tail = Iterator("def")
      val result = ScaladocExtractorPlugin.parseString[String](skipTok, line, delimiter, head, tail)
      result must beSome((2L, "def", tail))
    }

    "handle delimiter at start of string" in {
      val skipTok = "SKIP"
      val line = 1L
      val delimiter = ","
      val head = ",abc"
      val tail = Iterator.empty
      val result = ScaladocExtractorPlugin.parseString[String](skipTok, line, delimiter, head, tail)
      result must beSome((1L, "abc", tail))
    }

    "handle multiple delimiters in string" in {
      val skipTok = "SKIP"
      val line = 1L
      val delimiter = ","
      val head = "abc,def,ghi"
      val tail = Iterator.empty
      val result = ScaladocExtractorPlugin.parseString[String](skipTok, line, delimiter, head, tail)
      result must beSome((1L, "def,ghi", tail))
    }
  }
  
  "cleanSnippet" should {
    "remove leading spaces and star" in {
      val input = "   * some code"
      val result = ScaladocExtractorPlugin.cleanSnippet(input)

      result must_=== "some code"
    }

    "remove only leading star if no spaces" in {
      val input = "*some code"
      val result = ScaladocExtractorPlugin.cleanSnippet(input)

      result must_=== "some code"
    }

    "not remove star in middle of string" in {
      val input = "  some * code"
      val result = ScaladocExtractorPlugin.cleanSnippet(input)

      result must_=== "  some * code"
    }

    "handle empty string" in {
      val input = ""
      val result = ScaladocExtractorPlugin.cleanSnippet(input)

      result must_=== ""
    }
  }

  "parseSnippet" should {
    "skip snippet when skip token is present" in {
      val skipTok = "SKIP"
      val debugMsgs = Buffer[String]()
      val output = Buffer[String]()
      val head = s"some code $skipTok more"
      val tail = Iterator.empty
      val result = ScaladocExtractorPlugin.parseSnippet[String](
        skipTok,
        msg => debugMsgs.append(msg),
        "TestOutput",
        "TestSource.scala",
        42L,
        str => output.append(str),
        head,
        tail
      )

      debugMsgs.exists(_.contains("Skip Scaladoc snippet")) must beTrue and {
        result must beSome((42L, " more", tail))
      }
    }

    "end snippet when premature end is found" in {
      val skipTok = "SKIP"
      val debugMsgs = Buffer[String]()
      val output = Buffer[String]()
      val head = "some code */"
      val tail = Iterator.empty
      val result = ScaladocExtractorPlugin.parseSnippet[String](
        skipTok,
        msg => debugMsgs.append(msg),
        "TestOutput",
        "TestSource.scala",
        10L,
        str => output.append(str),
        head,
        tail
      )

      debugMsgs.exists(_.contains("Premature end of Scaladoc snippet")) must beTrue and {
        output.exists(_.startsWith("// Premature end")) must beTrue
      } and {
        result must beSome((10L, "", tail))
      }
    }

    "end snippet when snippet end is found" in {
      val skipTok = "SKIP"
      val debugMsgs = Buffer[String]()
      val output = Buffer[String]()
      val head = "val x = 1 }}}, more"
      val tail = Iterator.empty
      val result = ScaladocExtractorPlugin.parseSnippet[String](
        skipTok,
        msg => debugMsgs.append(msg),
        "TestOutput",
        "TestSource.scala",
        5L,
        str => output.append(str),
        head,
        tail
      )

      output.exists(_.contains("val x = 1")) must beTrue and {
        output.exists(_ == "}") must beTrue
      } and {
        debugMsgs.exists(_.contains("Generating TestOutput")) must beTrue
      } and {
        result must beSome((5L, ", more", tail))
      }
    }

    "parse multi-line snippet until end" in {
      val skipTok = "SKIP"
      val debugMsgs = Buffer[String]()
      val output = Buffer[String]()
      val head = "val x = 1"
      val tail = Iterator("val y = 2 }}}, trailing")
      val result = ScaladocExtractorPlugin.parseSnippet[String](
        skipTok,
        msg => debugMsgs.append(msg),
        "TestOutput",
        "TestSource.scala",
        1L,
        str => output.append(str),
        head,
        tail
      )

      output.exists(_.contains("val x = 1")) must beTrue and {
        output.exists(_.contains("val y = 2")) must beTrue
      } and {
        output.exists(_ == "}") must beTrue
      } and {
        debugMsgs.exists(_.contains("Generating TestOutput")) must beTrue
      } and {
        result must beSome((2L, ", trailing", tail))
      }
    }

    "return None if tail is empty and no end found" in {
      val skipTok = "SKIP"
      val debugMsgs = Buffer[String]()
      val output = Buffer[String]()
      val head = "val x = 1"
      val tail = Iterator.empty
      val result = ScaladocExtractorPlugin.parseSnippet[String](
        skipTok,
        msg => debugMsgs.append(msg),
        "TestOutput",
        "TestSource.scala",
        1L,
        str => output.append(str),
        head,
        tail
      )
      
      output.exists(_.contains("val x = 1")) must beTrue and {
        result must beNone
      }
    }
  }

  "parse" should {
    "return generated list when no scaladoc or string found" in {
      val skipTok = "SKIP"
      val debugMsgs = Buffer[String]()
      val outputName = "TestOutput"
      val sourcePath = "TestSource.scala"
      val head = "val x = 1"
      val tail = Iterator.empty
      val acc = (n: String, acc: List[String]) => n :: acc
      val generated = List.empty[String]
      val nameToWriter: String => (String => Unit) = _ => _ => ()
      val result = ScaladocExtractorPlugin.parse[String](
        skipTok,
        msg => debugMsgs.append(msg),
        nameToWriter,
        outputName,
        sourcePath,
        1L,
        head,
        tail,
        acc,
        generated
      )

      result must_=== generated
    }

    "handle string delimiter and advance iterator" in {
      val skipTok = "SKIP"
      val debugMsgs = Buffer[String]()
      val outputName = "TestOutput"
      val sourcePath = "TestSource.scala"
      val head = "val x = \"abc\""
      val tail = Iterator.empty
      val acc = (n: String, acc: List[String]) => n :: acc
      val generated = List.empty[String]
      val nameToWriter: String => (String => Unit) = _ => _ => ()
      val result = ScaladocExtractorPlugin.parse[String](
        skipTok,
        msg => debugMsgs.append(msg),
        nameToWriter,
        outputName,
        sourcePath,
        1L,
        head,
        tail,
        acc,
        generated
      )

      result must_=== generated
    }

    "handle scaladoc and call parseScaladoc" in {
      val skipTok = "SKIP"
      val debugMsgs = Buffer[String]()
      val outputName = "TestOutput"
      val sourcePath = "TestSource.scala"
      val head = "/**\n * Example\n */"
      val tail = Iterator.empty
      val acc = (n: String, acc: List[String]) => n :: acc
      val generated = List.empty[String]
      val nameToWriter: String => (String => Unit) = _ => _ => ()
      val result = ScaladocExtractorPlugin.parse[String](
        skipTok,
        msg => debugMsgs.append(msg),
        nameToWriter,
        outputName,
        sourcePath,
        1L,
        head,
        tail,
        acc,
        generated
      )

      result must_=== generated
    }

    "advance through iterator when no match found" in {
      val skipTok = "SKIP"
      val debugMsgs = Buffer[String]()
      val outputName = "TestOutput"
      val sourcePath = "TestSource.scala"
      val lines = Iterator("val x = 1", "val y = 2", "val z = 3")
      val acc = (n: String, acc: List[String]) => n :: acc
      val generated = List.empty[String]
      val nameToWriter: String => (String => Unit) = _ => _ => ()
      val result = ScaladocExtractorPlugin.parse[String](
        skipTok,
        msg => debugMsgs.append(msg),
        nameToWriter,
        outputName,
        sourcePath,
        1L,
        lines.next(),
        lines,
        acc,
        generated
      )

      result must_=== generated
    }

    "preserve indent" in {
      val skipTok = "SKIP"
      val debugMsgs = Buffer[String]()
      val generated = Buffer[String]()
      val dummySource = Iterator(
        "package test",
        "",
        "/**",
        " * Example of enum usage in Scala 3",
        " * {{{",
        " * enum Color:",
        " *   case Red, Green, Blue",
        " *   val valueOf: String => Option[Color] = EnumHelper.strictValueOf[Color]",
        " * }}}",
        " */",
        "object Dummy { def foo = 42 }"
      )
      val nameToWriter: String => (String => Unit) = _ => (s => generated.append(s))
      cchantep.ScaladocExtractorPlugin.parse[String](
        skipTok,
        msg => debugMsgs.append(msg),
        nameToWriter,
        "DummyOutput",
        "Dummy.scala",
        1L,
        dummySource.next(),
        dummySource,
        (n, acc) => n :: acc,
        Nil
      )

      val expected =
        "package scaladocextractor\r\n" +
        "\r\n" + // blank line after package with 5 spaces
        "/* Dummy.scala, ln 5 */\r\n" +
        "object DummyOutput5Snippet {\r\n" +
        "\r\n" + // blank line before enum with 5 spaces
        "  enum Color:\r\n" +
        "    case Red, Green, Blue\r\n" +
        "    val valueOf: String => Option[Color] = EnumHelper.strictValueOf[Color]\r\n" +
        "\r\n" + // blank line after enum block with 5 spaces
        "}" // closing object

      generated.mkString("\r\n") must_=== expected
    }
  }
}

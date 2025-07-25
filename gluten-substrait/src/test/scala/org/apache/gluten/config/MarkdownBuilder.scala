/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.gluten.config

import com.vladsch.flexmark.formatter.Formatter
import com.vladsch.flexmark.parser.{Parser, ParserEmulationProfile, PegdownExtensions}
import com.vladsch.flexmark.profile.pegdown.PegdownOptionsAdapter
import com.vladsch.flexmark.util.data.{MutableDataHolder, MutableDataSet}
import com.vladsch.flexmark.util.sequence.SequenceUtils.EOL

import scala.collection.mutable.ListBuffer

class MarkdownBuilder {
  private val buffer = new ListBuffer[String]

  /** Append a single line with replacing EOL to empty string. */
  def +=(str: String): MarkdownBuilder = {
    buffer += str.stripMargin.linesIterator.mkString
    this
  }

  /** Append the multiline with splitting EOL into single lines. */
  def ++=(multiline: String, marginChar: Char = '|'): MarkdownBuilder = {
    buffer ++= multiline.stripMargin(marginChar).linesIterator
    this
  }

  /** Append the auto-generation hint. */
  def generationHint(className: String): MarkdownBuilder = {
    this ++=
      s"""
         |<!-- DO NOT MODIFY THIS FILE DIRECTLY, IT IS AUTO-GENERATED BY [$className] -->
         |
         |"""
  }

  def toMarkdown: Stream[String] = {
    def createParserOptions(emulationProfile: ParserEmulationProfile): MutableDataHolder = {
      PegdownOptionsAdapter
        .flexmarkOptions(PegdownExtensions.ALL)
        .toMutable
        .set(Parser.PARSER_EMULATION_PROFILE, emulationProfile)
    }

    def createFormatterOptions(
        parserOptions: MutableDataHolder,
        emulationProfile: ParserEmulationProfile): MutableDataSet = {
      new MutableDataSet()
        .set(Parser.EXTENSIONS, Parser.EXTENSIONS.get(parserOptions))
        .set(Formatter.FORMATTER_EMULATION_PROFILE, emulationProfile)
    }

    val emulationProfile = ParserEmulationProfile.COMMONMARK
    val parserOptions = createParserOptions(emulationProfile)
    val formatterOptions = createFormatterOptions(parserOptions, emulationProfile)
    val parser = Parser.builder(parserOptions).build
    val renderer = Formatter.builder(formatterOptions).build
    val document = parser.parse(buffer.mkString(EOL))
    val formattedLines = new ListBuffer[String]
    val formattedLinesAppendable = new Appendable {
      override def append(csq: CharSequence): Appendable = {
        if (csq.length() > 0) {
          formattedLines.append(csq.toString)
        }
        this
      }

      override def append(csq: CharSequence, start: Int, end: Int): Appendable = {
        append(csq.toString.substring(start, end))
      }

      override def append(c: Char): Appendable = {
        append(c.toString)
      }
    }
    renderer.render(document, formattedLinesAppendable)
    // trim the ending EOL appended by renderer for each line
    formattedLines.toStream.map(
      str =>
        if (str.endsWith(EOL)) {
          str.substring(0, str.length - 1)
        } else {
          str
        })
  }
}

object MarkdownBuilder {
  def apply(className: String = null): MarkdownBuilder = {
    val builder = new MarkdownBuilder
    if (className != null) { builder.generationHint(className) }
    builder
  }
}

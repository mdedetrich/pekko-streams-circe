/*
 * Copyright 2015 – 2018 Paul Horn
 * Copyright 2018 – 2021 Matthew de Detrich
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mdedetrich.pekko.http

import org.apache.pekko
import pekko.actor.ActorSystem
import pekko.http.scaladsl.marshalling.Marshal
import pekko.http.scaladsl.model.HttpCharsets.`UTF-8`
import pekko.http.scaladsl.model.MediaTypes.`application/json`
import pekko.http.scaladsl.model.{HttpEntity, RequestEntity, UniversalEntity}
import pekko.http.scaladsl.unmarshalling.Unmarshal
import pekko.stream.scaladsl.{Keep, Sink, Source}
import pekko.util.ByteString
import io.circe.generic.semiauto._
import io.circe.{Decoder, Encoder, Printer}
import org.mdedetrich.pekko.http.support.CirceHttpSupport
import org.mdedetrich.pekko.stream.support.CirceStreamSupport
import org.scalatest._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.jawn.{AsyncParser, ParseException}

import scala.concurrent.Await
import scala.concurrent.duration._

case class Foo(bar: String, baz: Int, qux: List[Boolean])
object Foo {
  implicit val decoderFoo: Decoder[Foo] = deriveDecoder
  implicit val encoderFoo: Encoder[Foo] = deriveEncoder
}
case class Bar(foo: Foo)
object Bar {
  implicit val decoderBar: Decoder[Bar] = deriveDecoder
  implicit val encoderBar: Encoder[Bar] = deriveEncoder
}

class JsonSupportSpec
    extends AsyncWordSpec
    with CirceHttpSupport
    with CirceStreamSupport
    with Matchers
    with BeforeAndAfterAll {

  val foo            = Foo("bar", 42, List(true, false))
  val goodJson       = """{"bar":"bar","baz":42,"qux":[true,false]}"""
  val incompleteJson = """{"bar":"bar","baz":42,"qux"""
  val badJson        = """{"bar":"bar"}"""
  val badBarJson     = """{"foo":{"bar":"bar"}}"""
  val wrongJson      = """{"bar":"bar","baz":"forty two","qux":[]}"""
  val wrongBarJson   = """{"foo":{"bar":"bar","baz":"forty two","qux":[]}}"""
  val invalidJson    = """{"bar"="bar"}"""
  val prettyJson =
    """
      |{
      |  "bar" : "bar",
      |  "baz" : 42,
      |  "qux" : [
      |    true,
      |    false
      |  ]
      |}""".stripMargin.trim

  implicit val system: ActorSystem = ActorSystem()
//  implicit val mat    = system

  "enable marshalling of an A for which an Encoder[A] exists" can {
    "The marshalled entity" should {
      val futureEntity = Marshal(foo).to[RequestEntity]

      "have `application/json` as content type" in
        futureEntity.map {
          _.contentType.mediaType shouldBe `application/json`
        }

      "have UTF-8 as charset" in
        futureEntity.map {
          _.contentType.charsetOption should contain(`UTF-8`)
        }

      "have the correct body" in {
        for {
          entity <- futureEntity
          body   <- entity.dataBytes.runFold(ByteString.empty)(_ ++ _).map(_.utf8String)
        } yield body shouldBe goodJson

      }
    }

    "The marshalled entity with overridden Printer" should {
      implicit val makeItPretty: Printer = Printer.spaces2

      val futureEntity = Marshal(foo).to[RequestEntity]

      "have `application/json` as content type" in
        futureEntity.map {
          _.contentType.mediaType shouldBe `application/json`
        }

      "have UTF-8 as charset" in
        futureEntity.map {
          _.contentType.charsetOption should contain(`UTF-8`)
        }

      "have the correct body" in {
        for {
          entity <- futureEntity
          body   <- entity.dataBytes.runFold(ByteString.empty)(_ ++ _).map(_.utf8String)
        } yield body shouldBe prettyJson
      }
    }
  }

  "enable unmarshalling of an A for which a Decoder[A] exists" can {
    "A valid, strict json entity" should {
      val goodEntity = mkEntity(goodJson, strict = true)
      "produce the proper type" in
        Unmarshal(goodEntity).to[Foo].map {
          _ shouldBe foo
        }
    }

    "A valid, lazily streamed json entity" should {
      val lazyEntity = mkEntity(goodJson)
      "produce the proper type" in
        Unmarshal(lazyEntity).to[Foo].map {
          _ shouldBe foo
        }
    }

    "A complete, lazily streamed json entity with superfluous content" should {
      val entity = mkEntity(goodJson + incompleteJson)
      "produce the proper type" in
        Unmarshal(entity).to[Foo].map {
          _ shouldBe foo
        }
    }

    "Multiple, lazily streamed json entities via a flow" should {
      val entity = mkEntity(goodJson + goodJson + goodJson)
      "produce all values" in {
        val collect = Sink.fold[Vector[Foo], Foo](Vector())(_ :+ _)
        val parsed  = entity.dataBytes.runWith(decode[Foo].toMat(collect)(Keep.right))
        parsed.map {
          _ shouldBe Vector(foo, foo, foo)
        }
      }
    }

    "Multiple, lazily streamed json entities via a flow in an JSON array" should {
      val entity = mkEntity(s"[$goodJson,$goodJson,$goodJson]")
      "produce all values" in {
        val parsed = entity.dataBytes.via(decode[List[Foo]]).runWith(Sink.seq)

        parsed.map {
          _.flatten shouldBe Seq(foo, foo, foo)
        }
      }

      "produce all values with AsyncParser.UnwrapArray" in {
        val parsed = entity.dataBytes.via(decode[Foo](AsyncParser.UnwrapArray)).runWith(Sink.seq)

        parsed.map {
          _ shouldBe Seq(foo, foo, foo)
        }
      }
    }

    "Multiple, lazily streamed json entities via a flow in an JSON array and null element" should {
      val entity = mkEntity(s"[$goodJson,$goodJson,$goodJson,null]")
      "produce all values" in {
        val parsed = entity.dataBytes.via(decode[List[Option[Foo]]]).runWith(Sink.seq)

        parsed.map {
          _.flatten shouldBe List(Some(foo), Some(foo), Some(foo), None)
        }
      }

      "produce all values with AsyncParser.UnwrapArray" in {
        val parsed = entity.dataBytes.via(decode[Option[Foo]](AsyncParser.UnwrapArray)).runWith(Sink.seq)

        parsed.map {
          _ shouldBe List(Some(foo), Some(foo), Some(foo), None)
        }
      }
    }

    "Multiple, lazily streamed incomplete json entities via a flow in an JSON array and null element" should {
      val rest   = """":[true,false]}"""
      val entity = mkEntity(s"[$incompleteJson$rest,$incompleteJson$rest,$incompleteJson$rest]")
      "produce all values" in {
        val parsed = entity.dataBytes.via(decode[List[Foo]]).runWith(Sink.seq)

        parsed.map {
          _.flatten shouldBe Seq(foo, foo, foo)
        }
      }
    }

    "Multiple, lazily streamed json arrays" should {
      val entity = mkEntity(s"[$goodJson,$goodJson][$goodJson,$goodJson]")

      "produce all values with AsyncParser.UnwrapArray and multiValue = true" in {
        val parsed = entity.dataBytes.via(decode[Foo](AsyncParser.UnwrapArray, multiValue = true)).runWith(Sink.seq)

        parsed.map {
          _ shouldBe Seq(foo, foo, foo, foo)
        }
      }

      "produce a parse exception with AsyncParser.UnwrapArray and multiValue = false" in {
        val futureException = recoverToExceptionIf[ParseException](
          entity.dataBytes.via(decode[Foo](AsyncParser.UnwrapArray, multiValue = false)).runWith(Sink.seq)
        )

        futureException.map {
          _.getMessage shouldBe "expected eof got '[{\"...' (line 1, column 86)"
        }
      }
    }

    "A incomplete, lazily streamed json entity" should {
      val incompleteEntity = mkEntity(incompleteJson)
      "produce a parse exception with the message 'exhausted input'" in {
        val futureException = recoverToExceptionIf[NoSuchElementException](
          Unmarshal(incompleteEntity).to[Foo]
        )

        futureException.map {
          _.getMessage shouldBe "No complete json entity consumed"
        }

      }
    }

    "A bad json entity" should {
      "produce a parse exception" in {
        val badEntity = mkEntity(wrongJson, strict = true)
        val futureException = recoverToExceptionIf[JsonParsingException] {
          Unmarshal(badEntity).to[Foo]
        }
        futureException.map {
          _.getMessage shouldBe "Could not decode [\"forty two\"] at [.baz] as [Int]."
        }
      }
      "produce a parse exception for nested errors" in {
        val badEntity = mkEntity(wrongBarJson, strict = true)
        val futureException = recoverToExceptionIf[JsonParsingException] {
          Unmarshal(badEntity).to[Bar]
        }
        futureException.map {
          _.getMessage shouldBe "Could not decode [\"forty two\"] at [.foo.baz] as [Int]."
        }
      }
      "produce a parse exception with the message 'field missing'" in {
        val badEntity = mkEntity(badJson, strict = true)
        val futureException = recoverToExceptionIf[JsonParsingException] {
          Unmarshal(badEntity).to[Foo]
        }
        futureException.map {
          _.getMessage shouldBe "The field [.baz] is missing."
        }
      }
      "produce a parse exception with the message 'field missing' for nested errors" in {
        val badEntity = mkEntity(badBarJson, strict = true)
        val futureException = recoverToExceptionIf[JsonParsingException] {
          Unmarshal(badEntity).to[Bar]
        }
        futureException.map {
          _.getMessage shouldBe "The field [.foo.baz] is missing."
        }
      }
    }

    "A invalid json entity" should {
      val invalidEntity = mkEntity(invalidJson, strict = true)
      "produce a parse exception with the message 'expected/got'" in {
        val futureException = recoverToExceptionIf[ParseException] {
          Unmarshal(invalidEntity).to[Foo]
        }
        futureException.map {
          _.getMessage should include("expected : got '=")
        }
      }
    }
  }

  def mkEntity(s: String, strict: Boolean = false): UniversalEntity =
    if (strict) {
      HttpEntity(`application/json`, s)
    } else {
      val source = Source.fromIterator(() => s.grouped(8).map(ByteString(_)))
      HttpEntity(`application/json`, s.length.toLong, source)
    }

  override def afterAll(): Unit = {
    Await.result(system.terminate(), 10.seconds)
    ()
  }
}

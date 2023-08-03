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

package org.mdedetrich.pekko.json.stream

import org.apache.pekko
import org.typelevel.jawn.AsyncParser.ValueStream
import org.typelevel.jawn._

import java.nio.ByteBuffer

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.util.Try

import pekko.NotUsed
import pekko.stream.Attributes.name
import pekko.stream.scaladsl.{Flow, Keep, Sink}
import pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import pekko.stream._
import pekko.util.ByteString

object JsonStreamParser {

  private[this] val jsonStream = name("json-stream")

  def apply[J: Facade]: Graph[FlowShape[ByteString, J], NotUsed] =
    apply[J](ValueStream)

  def apply[J: Facade](mode: AsyncParser.Mode): Graph[FlowShape[ByteString, J], NotUsed] =
    new JsonStreamParser(mode, multiValue = false)

  def apply[J: Facade](mode: AsyncParser.Mode, multiValue: Boolean): Graph[FlowShape[ByteString, J], NotUsed] =
    new JsonStreamParser(mode, multiValue)

  def flow[J: Facade]: Flow[ByteString, J, NotUsed] =
    Flow.fromGraph(apply[J]).withAttributes(jsonStream)

  def flow[J: Facade](mode: AsyncParser.Mode): Flow[ByteString, J, NotUsed] =
    Flow.fromGraph(apply[J](mode)).withAttributes(jsonStream)

  def flow[J: Facade](mode: AsyncParser.Mode, multiValue: Boolean): Flow[ByteString, J, NotUsed] =
    Flow.fromGraph(apply[J](mode, multiValue)).withAttributes(jsonStream)

  def head[J: Facade]: Sink[ByteString, Future[J]] =
    flow.toMat(Sink.head)(Keep.right)

  def head[J: Facade](mode: AsyncParser.Mode): Sink[ByteString, Future[J]] =
    flow(mode).toMat(Sink.head)(Keep.right)

  def headOption[J: Facade]: Sink[ByteString, Future[Option[J]]] =
    flow.toMat(Sink.headOption)(Keep.right)

  def headOption[J: Facade](mode: AsyncParser.Mode): Sink[ByteString, Future[Option[J]]] =
    flow(mode).toMat(Sink.headOption)(Keep.right)

  def parse[J: Facade](bytes: ByteString): Try[J] =
    Parser.parseFromByteBuffer(bytes.asByteBuffer)

  private final class ParserLogic[J: Facade](parser: AsyncParser[J], shape: FlowShape[ByteString, J])
      extends GraphStageLogic(shape) {
    private[this] val in      = shape.in
    private[this] val out     = shape.out
    private[this] val scratch = new ArrayBuffer[J](64)

    setHandler(out,
               new OutHandler {
                 override def onPull(): Unit                                 = pull(in)
                 override def onDownstreamFinish(throwable: Throwable): Unit = downstreamFinish()
               }
    )
    setHandler(in,
               new InHandler {
                 override def onPush(): Unit           = upstreamPush()
                 override def onUpstreamFinish(): Unit = finishParser()
               }
    )

    private def upstreamPush(): Unit = {
      scratch.clear()
      val input = grab(in).asByteBuffers
      emitOrPullLoop(input.iterator, scratch)
    }

    private def downstreamFinish(): Unit = {
      parser.finish()
      cancel(in)
    }

    private def finishParser(): Unit =
      parser.finish() match {
        case Left(ParseException("exhausted input", _, _, _)) => complete(out)
        case Left(e)                                          => failStage(e)
        case Right(jsons)                                     => emitMultiple(out, jsons.iterator, () => complete(out))
      }

    @tailrec
    private[this] def emitOrPullLoop(bs: Iterator[ByteBuffer], results: ArrayBuffer[J]): Unit =
      if (bs.hasNext) {
        val next   = bs.next()
        val absorb = parser.absorb(next)
        absorb match {
          case Left(e) => failStage(e)
          case Right(jsons) =>
            if (jsons.nonEmpty) {
              results ++= jsons
            }
            emitOrPullLoop(bs, results)
        }
      } else {
        if (results.nonEmpty) {
          emitMultiple(out, results.iterator)
        } else {
          pull(in)
        }
      }
  }
}

final class JsonStreamParser[J: Facade] private (mode: AsyncParser.Mode, multiValue: Boolean)
    extends GraphStage[FlowShape[ByteString, J]] {
  private[this] val in                         = Inlet[ByteString]("Json.in")
  private[this] val out                        = Outlet[J]("Json.out")
  override val shape: FlowShape[ByteString, J] = FlowShape(in, out)
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new JsonStreamParser.ParserLogic[J](AsyncParser[J](mode, multiValue), shape)
}

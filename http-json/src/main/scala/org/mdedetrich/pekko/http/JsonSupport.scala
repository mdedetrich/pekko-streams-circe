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
import org.mdedetrich.pekko.json.stream.JsonStreamParser
import org.typelevel.jawn.Facade

import scala.concurrent.{Future, Promise}

import pekko.http.scaladsl.model.HttpEntity
import pekko.http.scaladsl.model.MediaTypes.`application/json`
import pekko.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, Unmarshaller}
import pekko.http.scaladsl.util.FastFuture
import pekko.stream._
import pekko.stream.scaladsl.Sink
import pekko.stream.stage._

trait JsonSupport {

  implicit def jsonUnmarshaller[J <: AnyRef: Facade]: FromEntityUnmarshaller[J] =
    Unmarshaller
      .withMaterializer[HttpEntity, J](_ =>
        implicit mat => {
          case HttpEntity.Strict(_, data) =>
            FastFuture(JsonStreamParser.parse[J](data))
          case entity => entity.dataBytes.via(JsonStreamParser[J]).runWith(JsonSupport.firstElementSink[J])
        }
      )
      .forContentTypes(`application/json`)
}

object JsonSupport extends JsonSupport {
  private def firstElementSink[J <: AnyRef]: Sink[J, Future[J]] =
    Sink.fromGraph(new FirstElementSinkStage[J])

  private final class FirstElementSinkStage[J <: AnyRef]
      extends GraphStageWithMaterializedValue[SinkShape[J], Future[J]] {
    private[this] val in: Inlet[J] = Inlet("firstElement.in")

    override val shape: SinkShape[J]                     = SinkShape.of(in)
    override protected def initialAttributes: Attributes = Attributes.name("firstElement")

    override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[J]) = {
      val p: Promise[J] = Promise()
      (new GraphStageLogic(shape) with InHandler {
         private[this] var element: J = null.asInstanceOf[J]

         override def preStart(): Unit = pull(in)

         def onPush(): Unit = {
           if (element eq null)
             element = grab(in)
           pull(in)
         }

         override def onUpstreamFinish(): Unit = {
           val el = element
           element = null.asInstanceOf[J]
           if (el ne null)
             p.trySuccess(el)
           else
             p.tryFailure(new NoSuchElementException("No complete json entity consumed"))
           completeStage()
         }

         override def onUpstreamFailure(ex: Throwable): Unit = {
           element = null.asInstanceOf[J]
           p.tryFailure(ex)
           failStage(ex)
         }

         override def postStop(): Unit =
           if (!p.isCompleted) {
             p.failure(new AbruptStageTerminationException(this))
             ()
           }

         setHandler(in, this)
       },
       p.future
      )
    }

    override def toString: String = "FirstElementSinkStage"
  }
}

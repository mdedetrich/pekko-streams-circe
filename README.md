[![Build Status][ci-img]][ci]
[![Coverage][coverage-img]][coverage]
[![Maven][maven-img]][maven]
[![Join at Gitter][gitter-img]][gitter]
[![Apache License][license-img]][license]

# Pekko Streams Circe Support

This library provides Json support for stream based applications using [jawn](https://github.com/non/jawn)
as a parser. It supports all backends that jawn supports with support for [circe](https://github.com/travisbrown/circe) provided as a example.

It is a fork of [akka-streams-json](https://github.com/mdedetrich/akka-streams-json) built with [Apache Pekko](https://pekko.apache.org/).
[akka-streams-json](https://github.com/mdedetrich/akka-streams-json) which is in itself a fork of
https://github.com/knutwalker/akka-stream-json.

## Installation

There are two main modules, `pekko-stream-json` and `pekko-http-json`.
`pekko-stream-json` is the basis and provides the stream-based parser while
`pekko-http-json` enabled support to use the desired json library as an Unmarshaller.


```
libraryDependencies ++= List(
  "org.mdedetrich" %% "pekko-stream-json" % <VERSION>,
  "org.mdedetrich" %% "pekko-http-json" % <VERSION>"
)
```

`pekko-stream-circe` is published for Scala 2.13 and 2.12.

## Usage

The parser lives at `org.mdedetrich.pekko.json.stream.JsonStreamParser`

Use one of the constructor methods in the companion object to create the parser at
various levels of abstraction, either a Stage, a Flow, or a Sink.
You just add the [jawn support facade](https://github.com/non/jawn#supporting-external-asts-with-jawn)
of your choice and you will can parsed into their respective Json AST.


For Http support, either `import org.mdedetrich.pekko.http.JsonSupport._`
or mixin `... with org.mdedetrich.pekko.http.JsonSupport`.

Given an implicit jawn facade, this enable you to decode into the respective Json AST
using the Pekko HTTP marshalling framework. As jawn is only about parsing and does not abstract
over rendering, you'll only get an Unmarshaller.


### Circe

```
libraryDependencies ++= List(
  "org.mdedetrich" %% "pekko-stream-circe" % "<VERSION>",
  "org.mdedetrich" %% "pekko-http-circe" % "<VERSION>"
)
```

(Using circe 0.14.x)

Adding support for a specific framework is
[quite](support/stream-circe/src/main/scala/org/mdedetrich/pekko/stream/support/CirceStreamSupport.scala)
[easy](support/http-circe/src/main/scala/org/mdedetrich/pekko/http/support/CirceHttpSupport.scala).

These support modules allow you to directly marshall from/unmarshall into your data types
using circes `Decoder` and `Encoder` type classes.

Just mixin or import `org.mdedetrich.pekko.http.support.CirceHttpSupport` for Http
or pipe your `Source[ByteString, _].via(org.mdedetrich.pekko.stream.CirceStreamSupport.decode[A])`
to get a `Source[A, _]`.

This flow even supports parsing multiple json documents in whatever
fragmentation they may arrive, which is great for consuming stream/sse based APIs.

If there is an error in parsing the Json you can catch `org.mdedetrich.pekko.http.support.CirceStreamSupport.JsonParsingException`.
The exception provides Circe cursor history, current cursor and the type hint of the error.

## Why jawn?

Jawn provides a nice interface for asynchronous parsing.
Most other Json marshalling provider will consume the complete entity
at first, convert it to a string and then start to parse.
With jawn, the json is incrementally parsed with each arriving data chunk,
using directly the underlying ByteBuffers without conversion.

## License

This code is open source software licensed under the Apache 2.0 License.

[ci-img]: https://github.com/mdedetrich/pekko-streams-circe/actions/workflows/ci.yml/badge.svg?branch=main
[coverage-img]: https://coveralls.io/repos/github/mdedetrich/pekko-streams-circe/badge.svg?branch=main
[maven-img]: https://img.shields.io/maven-central/v/org.mdedetrich/pekko-stream-json_2.12.svg?label=latest
[gitter-img]: https://img.shields.io/badge/gitter-Join_Chat-1dce73.svg
[license-img]: https://img.shields.io/badge/license-APACHE_2-green.svg

[ci]: https://github.com/mdedetrich/pekko-streams-circe/actions/workflows/ci.yml?query=branch%3Amain
[coverage]: https://coveralls.io/github/mdedetrich/pekko-streams-circe?branch=main
[maven]: http://search.maven.org/#search|ga|1|g%3A%22org.mdedetrich%22%20AND%20%28a%3Apekko-stream-*_2.11%20OR%20a%3Apekko-http-*_2.11%20OR%20a%3Apekko-stream-*_2.12%20OR%20a%3Apekko-http-*_2.12%29
[gitter]: https://gitter.im/mdedetrich/pekko-streams-circe?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge
[license]: https://www.apache.org/licenses/LICENSE-2.0

/*
 *  Copyright 2021-2022 Disney Streaming
 *
 *  Licensed under the Tomorrow Open Source Technology License, Version 1.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     https://disneystreaming.github.io/TOST-1.0.txt
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package smithy4s.compliancetests.weaverimpl

import smithy4s.compliancetests.{ComplianceTest, ReverseRouter}
import smithy4s.compliancetests.internals.AllowRules
import smithy4s.{Document, Schema, ShapeId}
import smithy4s.dynamic.DynamicSchemaIndex
import cats.syntax.all._
import cats.effect.IO
import cats.effect.std.Env
import fs2.io.file.Path
import smithy4s.http.CodecAPI
import smithy4s.dynamic.model.Model
import smithy4s.dynamic.DynamicSchemaIndex.load
import smithy4s.schema.Schema.document
import smithy4s.compliancetests._
import smithy4s.http.PayloadError
import weaver._

abstract class ProtocolComplianceTestSuite
    extends EffectSuite[IO]
    with BaseCatsSuite {

  implicit protected def effectCompat: EffectCompat[IO] = CatsUnsafeRun

  def getSuite: EffectSuite[IO] = this

  def allowAll: Boolean = true

  def allTests: DynamicSchemaIndex => List[ComplianceTest[IO]]

  def spec(args: List[String]): fs2.Stream[IO, TestOutcome] = {
    fs2.Stream
      .eval(dynamicSchemaIndexLoader)
      .evalMap(index => decodeAllowRules(index).map(index -> _))
      .flatMap { case (schemaIndex, allowRules) =>
        fs2.Stream.emits(allTests(schemaIndex).map((allowRules, _)))
      }
      .evalMap { case (allowRules, test) =>
        runInWeaver(allowAll, allowRules, test)
      }
  }

  def dynamicSchemaIndexLoader: IO[DynamicSchemaIndex]

  def genClientTests(
      impl: ReverseRouter[IO],
      shapeIds: ShapeId*
  ): DynamicSchemaIndex => List[ComplianceTest[IO]] = { dynamicSchemaIndex =>
    shapeIds.toList.flatMap(shapeId =>
      dynamicSchemaIndex
        .getService(shapeId)
        .toList
        .flatMap(wrapper => {
          HttpProtocolCompliance
            .clientTests(
              impl,
              wrapper.service
            )
        })
    )
  }

  def genServerTests(
      impl: Router[IO],
      shapeIds: ShapeId*
  ): DynamicSchemaIndex => List[ComplianceTest[IO]] = { dynamicSchemaIndex =>
    shapeIds.toList.flatMap(shapeId =>
      dynamicSchemaIndex
        .getService(shapeId)
        .toList
        .flatMap(wrapper => {
          HttpProtocolCompliance
            .serverTests(
              impl,
              wrapper.service
            )
        })
    )
  }

  def genClientAndServerTests(
      impl: ReverseRouter[IO] with Router[IO],
      shapeIds: ShapeId*
  ): DynamicSchemaIndex => List[ComplianceTest[IO]] = { dynamicSchemaIndex =>
    shapeIds.toList.flatMap(shapeId =>
      dynamicSchemaIndex
        .getService(shapeId)
        .toList
        .flatMap(wrapper => {
          HttpProtocolCompliance
            .clientAndServerTests(
              impl,
              wrapper.service
            )
        })
    )
  }

  private def decodeAllowRules(dsi: DynamicSchemaIndex): IO[AllowRules] = {
    Document.DObject(dsi.metadata).decode[AllowRules].liftTo[IO]
  }

  def loadDynamic(
      doc: Document
  ): Either[PayloadError, DynamicSchemaIndex] = {
    Document.decode[Model](doc).map(load)
  }
  private[smithy4s] def fileFromEnv(key: String): IO[Path] = Env
    .make[IO]
    .get(key)
    .flatMap(
      _.liftTo[IO](sys.error("MODEL_DUMP env var not set"))
        .map(fs2.io.file.Path(_))
    )

  def decodeDocument(
      bytes: Array[Byte],
      codecApi: CodecAPI
  ): Document = {
    val schema: Schema[Document] = document
    val codec: codecApi.Codec[Document] = codecApi.compileCodec(schema)
    codecApi
      .decodeFromByteArray[Document](codec, bytes)
      .getOrElse(sys.error("unable to decode smithy model into document"))

  }

  private def runInWeaver(
      allowAll: Boolean,
      allowList: AllowRules,
      tc: ComplianceTest[IO]
  ): IO[TestOutcome] = {
    val runner: IO[Expectations] = {
      // TODO add more robust filtering system and remove any specific mention of a domain such as Alloy
      if (allowAll || allowList.isAllowed(tc) || tc.show.contains("alloy")) {
        tc.run
          .map(res => expectSuccess(res))
          .attempt
          .map {
            case Right(expectations) => expectations
            case Left(throwable) =>
              Expectations.Helpers.failure(
                s"unexpected error when running test ${throwable.getMessage} \n $throwable"
              )
          }

      } else
        tc.run.attempt
          .map(_.fold(t => Left(t.toString), identity))
          .map(res => expectFailure(res))
    }

    Test(
      tc.show,
      runner
    )
  }

  def expectSuccess(
      res: ComplianceTest.ComplianceResult
  ): Expectations = {
    res.bifoldMap(
      Expectations.Helpers.failure(_),
      _ => Expectations.Helpers.success
    )
  }

  def expectFailure(
      res: ComplianceTest.ComplianceResult
  ): Expectations = {
    res.bifoldMap(
      reason =>
        throw new weaver.IgnoredException(
          Some(reason),
          weaver.SourceLocation.fromContext
        ),
      _ =>
        Expectations.Helpers.failure(
          "expected failure but got success, please update the Allow list"
        )
    )
  }

}

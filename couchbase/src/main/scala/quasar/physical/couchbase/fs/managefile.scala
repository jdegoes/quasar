/*
 * Copyright 2014–2016 SlamData Inc.
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

package quasar.physical.couchbase.fs

import quasar.Predef._
import quasar.contrib.pathy._
import quasar.effect.{MonotonicSeq, Read}
import quasar.fp.free._
import quasar.fs._
import quasar.physical.couchbase.common._

import pathy.Path._
import scalaz._, Scalaz._
import scalaz.concurrent.Task

object managefile {
  import ManageFile._

  def interpret[S[_]](implicit
    S0: MonotonicSeq :<: S,
    S1: Read[Context, ?] :<:  S,
    S2: Task :<: S
  ): ManageFile ~> Free[S, ?] = λ[ManageFile ~> Free[S, ?]] {
    case Move(scenario, semantics) => move(scenario, semantics)
    case Delete(path)              => delete(path)
    case TempFile(path)            => tempFile(path)
  }

  def move[S[_]](
    scenario: MoveScenario, semantics: MoveSemantics
  )(implicit
    S0: Task :<: S,
    context: Read.Ops[Context, S]
  ): Free[S, FileSystemError \/ Unit] =
    (for {
      ctx       <- context.ask.liftM[FileSystemErrT]
      src       <- EitherT(bucketCollectionFromPath(scenario.src).point[Free[S, ?]])
      dst       <- EitherT(bucketCollectionFromPath(scenario.dst).point[Free[S, ?]])
      _         <- EitherT((
                     if (src.bucket =/= dst.bucket) FileSystemError.pathErr(
                       PathError.invalidPath(scenario.dst, "different bucket from src path")).left
                     else
                       ().right
                   ).point[Free[S, ?]])
      bkt       <- EitherT(getBucket(src.bucket))
      srcDocs   <- lift(docIdTypesWithTypePrefix(bkt, src.collection)).into.liftM[FileSystemErrT]
      _         <- EitherT((
                     if (srcDocs.isEmpty)
                       FileSystemError.pathErr(PathError.pathNotFound(scenario.src)).left
                     else
                       ().right
                   ).point[Free[S, ?]])
      dstDocs   <- lift(docIdTypesWithTypePrefix(bkt, dst.collection)).into.liftM[FileSystemErrT]
      dstExists =  dstDocs.nonEmpty
      _         <- EitherT((semantics match {
                    case MoveSemantics.FailIfExists if dstExists =>
                      FileSystemError.pathErr(PathError.pathExists(scenario.dst)).left
                    case MoveSemantics.FailIfMissing if !dstExists =>
                      FileSystemError.pathErr(PathError.pathNotFound(scenario.dst)).left
                    case _ =>
                      ().right[FileSystemError]
                  }).point[Free[S, ?]])
      _         <- dstExists.whenM(EitherT(delete(scenario.dst)))
      qStr      =  s"""update `${bkt.name}`
                       set type=("${dst.collection}" || REGEXP_REPLACE(type, "^${src.collection}", ""))
                       where type like "${src.collection}%" returning type"""
      _         <- lift(Task.delay(
                     bkt.query(n1qlQuery(qStr))
                   )).into.liftM[FileSystemErrT]
    } yield ()).run

  def delete[S[_]](
    path: APath
  )(implicit
    S0: Task :<: S,
    context: Read.Ops[Context, S]
  ): Free[S, FileSystemError \/ Unit] =
    (for {
      ctx    <- context.ask.liftM[FileSystemErrT]
      bktCol <- EitherT(bucketCollectionFromPath(path).point[Free[S, ?]])
      bkt    <- EitherT(getBucket(bktCol.bucket))
      docIds <- lift(docIdTypesWithTypePrefix(bkt, bktCol.collection)).into.liftM[FileSystemErrT]
      _      <- EitherT((
                  if (docIds.isEmpty) FileSystemError.pathErr(PathError.pathNotFound(path)).left
                  else ().right
                ).point[Free[S, ?]])
      _      <- lift(
                  docIds.traverse(i => Task.delay(bkt.remove(i.id)))
                ).into.liftM[FileSystemErrT]
    } yield ()).run

  def tempFile[S[_]](
    path: APath
  )(implicit
    S0: MonotonicSeq :<: S
  ): Free[S, FileSystemError \/ AFile] =
    MonotonicSeq.Ops[S].next.map { i =>
      val tmpFilename = file(s"__quasar_tmp_$i")
      refineType(path).fold(
        d => d </> tmpFilename,
        f => fileParent(f) </> tmpFilename
      ).right
    }

}

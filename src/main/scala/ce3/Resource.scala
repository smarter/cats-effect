/*
 * Copyright 2020 Typelevel
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

package ce3

import cats.ApplicativeError
import cats.implicits._
import scala.annotation.tailrec
import cats.Functor
import cats.Applicative
import cats.Monad
import cats.data.AndThen
import cats.MonadError
import cats.~>

sealed abstract class Resource[+F[_], +A] {
  import Resource.{Allocate, Bind, Suspend}

  private def fold[G[x] >: F[x], B, E](
      onOutput: A => G[B],
      onRelease: G[Unit] => G[Unit]
  )(implicit F: Bracket[G, E]): G[B] = {
    // Indirection for calling `loop` needed because `loop` must be @tailrec
    def continue(
        current: Resource[G, Any],
        stack: List[Any => Resource[G, Any]]
    ): G[Any] =
      loop(current, stack)

    // Interpreter that knows how to evaluate a Resource data structure;
    // Maintains its own stack for dealing with Bind chains
    @tailrec def loop(
        current: Resource[G, Any],
        stack: List[Any => Resource[G, Any]]
    ): G[Any] =
      current match {
        case a: Allocate[G, F.Case, Any] @unchecked =>
          F.bracketCase(a.resource) {
            case (a, _) =>
              stack match {
                case Nil => onOutput.asInstanceOf[Any => G[Any]](a)
                case l   => continue(l.head(a), l.tail)
              }
          } {
            case ((_, release), ec) =>
              onRelease(release(ec))
          }
        case b: Bind[G, _, Any] =>
          loop(b.source, b.fs.asInstanceOf[Any => Resource[G, Any]] :: stack)
        case s: Suspend[G, Any] =>
          s.resource.flatMap(continue(_, stack))
      }
    loop(this.asInstanceOf[Resource[G, Any]], Nil).asInstanceOf[G[B]]
  }


  /**
   * Given a `Resource`, possibly built by composing multiple
   * `Resource`s monadically, returns the acquired resource, as well
   * as an action that runs all the finalizers for releasing it.
   *
   * If the outer `F` fails or is interrupted, `allocated` guarantees
   * that the finalizers will be called. However, if the outer `F`
   * succeeds, it's up to the user to ensure the returned `F[Unit]`
   * is called once `A` needs to be released. If the returned
   * `F[Unit]` is not called, the finalizers will not be run.
   *
   * For this reason, this is an advanced and potentially unsafe api
   * which can cause a resource leak if not used correctly, please
   * prefer [[use]] as the standard way of running a `Resource`
   * program.
   *
   * Use cases include interacting with side-effectful apis that
   * expect separate acquire and release actions (like the `before`
   * and `after` methods of many test frameworks), or complex library
   * code that needs to modify or move the finalizer for an existing
   * resource.
   *
   */
  //todo no explicit Outcome?
  def allocated[G[x] >: F[x], B >: A, E](implicit F: Bracket.Aux2[G, E, Outcome[G, *, *]]): G[(B, G[Unit])] = {
    // Indirection for calling `loop` needed because `loop` must be @tailrec
    def continue(current: Resource[G, Any], stack: List[Any => Resource[G, Any]], release: G[Unit]): G[(Any, G[Unit])] =
      loop(current, stack, release)

    // Interpreter that knows how to evaluate a Resource data structure;
    // Maintains its own stack for dealing with Bind chains
    @tailrec def loop(current: Resource[G, Any],
                      stack: List[Any => Resource[G, Any]],
                      release: G[Unit]): G[(Any, G[Unit])] =
      current match {
        case a: Allocate[G, F.Case, Any] @unchecked =>
          F.bracketCase(a.resource) {
            case (a, rel) =>
              stack match {
                //todo: .unit or something else?
                case Nil => F.pure(a -> F.guarantee(rel(F.CaseInstance.unit))(release))
                case l   => continue(l.head(a), l.tail, F.guarantee(rel(F.CaseInstance.unit))(release))
              }
          } {
            case (_, Outcome.Completed(_)) =>
              F.unit
            case ((_, release), ec) =>
              release(ec)
          }
        case b: Bind[G, _, Any] =>
          loop(b.source, b.fs.asInstanceOf[Any => Resource[G, Any]] :: stack, release)
        case s: Suspend[G, Any] =>
          s.resource.flatMap(continue(_, stack, release))
      }

    loop(this.asInstanceOf[Resource[F, Any]], Nil, F.unit).map {
      case (a, release) =>
        (a.asInstanceOf[A], release)
    }
  }

  def use[G[x] >: F[x], B, E](
      f: A => G[B]
  )(implicit F: Bracket[G, E]): G[B] =
    fold[G, B, E](f, identity)

  def used[G[x] >: F[x], E](implicit F: Bracket[G, E]): G[Unit] =
    use(_ => F.unit)
}

object Resource extends ResourceInstances1 {

  /**
    * Creates a resource from an allocating effect.
    *
    * @see [[make]] for a version that separates the needed resource
    *      with its finalizer tuple in two parameters
    *
    * @tparam F the effect type in which the resource is acquired and released
    * @tparam A the type of the resource
    * @param resource an effect that returns a tuple of a resource and
    *        an effect to release it
    */
  def apply[F[_], A, E](
      resource: F[(A, F[Unit])]
  )(implicit F: Bracket[F, E]): Resource[F, A] =
    applyCase[F, F.Case, E, A](resource.map(_.map(Function.const)))(F)

  /**
    * Creates a resource from an allocating effect, with a finalizer
    * that is able to distinguish between [[ExitCase exit cases]].
    *
    * @see [[makeCase]] for a version that separates the needed resource
    *      with its finalizer tuple in two parameters
    *
    * @tparam F the effect type in which the resource is acquired and released
    * @tparam A the type of the resource
    * @param resource an effect that returns a tuple of a resource and
    *        an effectful function to release it
    */
  def applyCase[F[_], Case[_], E, A](
      resource: F[(A, Case[_] => F[Unit])]
  )(implicit F: Bracket.Aux[F, E, Case]): Resource[F, A] =
    Allocate[F, Case, A](resource)

  def applyCase0[F[_], E](
      implicit bracket: Bracket[F, E]
  ): ApplyCasePartiallyApplied[F, bracket.Case, E] =
    new ApplyCasePartiallyApplied[F, bracket.Case, E](bracket)

  final class ApplyCasePartiallyApplied[F[_], Case[_], E](
      bracket: Bracket.Aux[F, E, Case]
  ) {
    def apply[A](resource: F[(A, Case[_] => F[Unit])]): Resource[F, A] =
      applyCase[F, Case, E, A](resource)(bracket)
  }

  def liftK[F[_]: Applicative]: F ~> Resource[F, *] = new (F ~> Resource[F, *]) {
    def apply[A](fa: F[A]): Resource[F,A] = Resource.liftF(fa)
  }
  /**
    * Given a `Resource` suspended in `F[_]`, lifts it in the `Resource` context.
    */
  def suspend[F[_], A](fr: F[Resource[F, A]]): Resource[F, A] =
    Resource.Suspend(fr)

  /**
    * Creates a resource from an acquiring effect and a release function.
    *
    * This builder mirrors the signature of [[Bracket.bracket]].
    *
    * @tparam F the effect type in which the resource is acquired and released
    * @tparam A the type of the resource
    * @param acquire a function to effectfully acquire a resource
    * @param release a function to effectfully release the resource returned by `acquire`
    */
  def make[F[_], A, E](
      acquire: F[A]
  )(release: A => F[Unit])(implicit F: Bracket[F, E]): Resource[F, A] =
    apply[F, A, E](acquire.map(a => a -> release(a)))

  /**
    * Creates a resource from an acquiring effect and a release function that can
    * discriminate between different [[ExitCase exit cases]].
    *
    * This builder mirrors the signature of [[Bracket.bracketCase]].
    *
    * @tparam F the effect type in which the resource is acquired and released
    * @tparam A the type of the resource
    * @param acquire a function to effectfully acquire a resource
    * @param release a function to effectfully release the resource returned by `acquire`
    */
  def makeCase[F[_], Case[_], E, A](
      acquire: F[A]
  )(
      release: (A, Case[_]) => F[Unit]
  )(implicit F: Bracket.Aux[F, E, Case]): Resource[F, A] =
    applyCase[F, Case, E, A](
      acquire.map(a => (a, e => release(a, e)))
    )

  /**
    * Lifts a pure value into a resource. The resource has a no-op release.
    *
    * @param a the value to lift into a resource
    */
  def pure[F[_], A](a: A)(implicit F: Applicative[F]): Resource[F, A] =
    Allocate[F, Any, A](F.pure((a, (_: Any) => F.unit)))

  /**
    * Lifts an applicative into a resource. The resource has a no-op release.
    * Preserves interruptibility of `fa`.
    *
    * @param fa the value to lift into a resource
    */
  def liftF[F[_], A](fa: F[A])(implicit F: Applicative[F]): Resource[F, A] =
    Resource.suspend(fa.map(a => Resource.pure[F, A](a)))

  /**
    * Implementation for the `tailRecM` operation, as described via
    * the `cats.Monad` type class.
    */
  def tailRecM[F[_], A, B, E](
      a: A
  )(
      f: A => Resource[F, Either[A, B]]
  )(implicit F: Bracket[F, E]): Resource[F, B] = {
    def continue(r: Resource[F, Either[A, B]]): Resource[F, B] =
      r match {
        case a: Allocate[F, F.Case, Either[A, B]] @unchecked =>
          Suspend(a.resource.flatMap[Resource[F, B]] {
            case (Left(a), release) =>
              release(F.CaseInstance.pure(a)).map(_ =>
                tailRecM[F, A, B, E](a)(f)
              )
            case (Right(b), release) =>
              F.pure(Allocate[F, F.Case, B](F.pure((b, release))))
          })
        case s: Suspend[F, Either[A, B]] =>
          Suspend(s.resource.map(continue))
        case b: Bind[F, _, Either[A, B]] =>
          Bind(b.source, AndThen(b.fs).andThen(continue))
      }

    continue(f(a))
  }

  /**
    * Lifts an applicative into a resource as a `FunctionK`. The resource has a no-op release.
    */
  // def liftK[F[_]](implicit F: Applicative[F]): F ~> Resource[F, *] =
  // λ[F ~> Resource[F, *]](Resource.liftF(_))

  /**
    * `Resource` data constructor that wraps an effect allocating a resource,
    * along with its finalizers.
    */
  final case class Allocate[F[_], Case[_], A](
      resource: F[(A, Case[_] => F[Unit])]
  ) extends Resource[F, A]

  /**
    * `Resource` data constructor that encodes the `flatMap` operation.
    */
  final case class Bind[F[_], S, +A](
      source: Resource[F, S],
      fs: S => Resource[F, A]
  ) extends Resource[F, A]

  /**
    * `Resource` data constructor that suspends the evaluation of another
    * resource value.
    */
  final case class Suspend[F[_], A](resource: F[Resource[F, A]])
      extends Resource[F, A]

  implicit def concurrentRegionForResource[F[_], E](
      implicit concBracketF: ConcurrentBracket[F, E]
  ): Concurrent[Resource[F, *], E] =
    new ResourceConcurrentRegion[F, E] {
      val F: concBracketF.type = concBracketF
    }
}

trait ResourceInstances1 {
  implicit def regionForResource[F[_], E](
      implicit bracketF: Bracket[F, E]
  ): Region.Aux[Resource, F, E, bracketF.Case] =
    new ResourceRegion[F, E] with ResourceMonadError[F, E]{
      val F: bracketF.type = bracketF
      override type Case[A] = F.Case[A]
      val CaseInstance: ApplicativeError[Case, E] = F.CaseInstance
    }

}

trait ResourceConcurrentRegion[F[_], E] extends ResourceRegion[F, E] with Concurrent[Resource[F, *], E] {
  override implicit val F: ConcurrentBracket[F, E]
  
  def start[A](rfa: Resource[F, A]): Resource[F, Fiber[Resource[F, ?], E, A]] = {
    val delegateF = F start {
      F.bracketCase(rfa.allocated)(x => x.pure[F]) {
        case ((_, fin), Outcome.Canceled | Outcome.Errored(_)) => fin
        case (_, Outcome.Completed(_)) => F.unit
      }
    }

    Resource.liftF(delegateF) map { delegate =>
      new Fiber[Resource[F, *], E, A] {

        val cancel: Resource[F, Unit] = Resource.liftF(delegate.cancel)

        val join: Resource[F, Outcome[Resource[F, *], E, A]] =
          Resource liftF {
            delegate.join map {
              case oc: Outcome.Canceled.type => oc
              case oc @Outcome.Errored(_) => oc
              case Outcome.Completed(allocF) => Outcome.Completed(Resource(allocF))
            }
          }
      }
    }
  }

  def uncancelable[A](body: Resource[F, ?] ~> Resource[F, ?] => Resource[F, A]): Resource[F, A] =
    Resource {
      F uncancelable { poll =>
        val rpoll = λ[Resource[F, ?] ~> Resource[F, ?]] { rfa =>
          Resource(poll(rfa.allocated))
        }

        body(rpoll).allocated
      }
    }

  def canceled: Resource[F, Unit] = Resource.liftF(F.canceled)
  
  def never[A]: Resource[F, A] = Resource.liftF(F.never[A])
  
  def cede: Resource[F, Unit] = Resource.liftF(F.cede)

  def racePair[A, B](fa: Resource[F, A], fb: Resource[F, B]): Resource[F, Either[(A, Fiber[ce3.Resource[F, *], E, B]), (Fiber[ce3.Resource[F, *], E, A], B)]] = 
    uncancelable { poll =>
      def liftFiber[X](fiber: Fiber[F, E, (X, F[Unit])]): Fiber[Resource[F, *], E, X] = new Fiber[Resource[F, *], E, X] {
        val cancel: Resource[F,Unit] = liftF(fiber.cancel)

        val join: Resource[F,Outcome[Resource[F, *], E, X]] =
          liftF(fiber.join).map(_.mapK(Resource.liftK)).map {
            _.fold(
              Outcome.Canceled,
              Outcome.Errored[E],
              c => Outcome.Completed(c.flatMap(r => Resource(r.pure[F])))
            )
        }
      }

      val racedAllocations: F[Either[
        ((A,F[Unit]), Fiber[F, E, (B,F[Unit])]),
        (Fiber[F, E, (A,F[Unit])],(B,F[Unit]))
      ]] = F.racePair(poll(fa).allocated, poll(fb).allocated)

      liftF(racedAllocations).flatMap(
        _.bitraverse(
          _.map(liftFiber).leftTraverse(a => Resource(a.pure[F])),
          _.leftMap(liftFiber).traverse(b => Resource(b.pure[F]))
        )
      )
    }
}

trait ResourceRegion[F[_], E] extends ResourceMonadError[F, E] with Region[Resource, F, E] {
  implicit val F: Bracket[F, E]
  import Resource._

  def openCase[A](acquire: F[A])(
      release: (A, Case[_]) => F[Unit]
  ): Resource[F, A] =
    Allocate[F, Case, A](acquire.map(a => (a, release(a, _))))

  def liftF[A](fa: F[A]): Resource[F, A] = Resource.liftF(fa)

  def supersededBy[B](
      rfa: Resource[F, _],
      rfb: Resource[F, B]
  ): Resource[F, B] = Resource.liftF(rfa.use(_ => F.unit)) *> rfb

}

trait ResourceMonadError[F[_], E] extends MonadError[Resource[F, *], E] {
  import Resource._
  implicit val F: Bracket[F, E]

  def pure[A](x: A): Resource[F, A] = Resource.pure[F, A](x)

  def raiseError[A](e: E): Resource[F, A] = Resource.liftF(F.raiseError(e))

  def handleErrorWith[A](
      fa: Resource[F, A]
  )(f: E => Resource[F, A]): Resource[F, A] =
    flatMap(attempt(fa)) {
      case Right(a) => pure(a)
      case Left(e)  => f(e)
    }

  override def attempt[A](fa: Resource[F, A]): Resource[F, Either[E, A]] =
    fa match {
      case alloc: Allocate[F, F.Case, b] @unchecked =>
        Allocate[F, F.Case, Either[E, A]](
          F.map(F.attempt(alloc.resource)) {
            case Left(error) =>
              (error.asLeft[A], (_: F.Case[_]) => F.unit)
            case Right((a, release)) => (Right(a), release)
          }
        )
      case Bind(
          source: Resource[F, Any] @unchecked,
          fs: (Any => Resource[F, A]) @unchecked
          ) =>
        Suspend(F.pure(source).map[Resource[F, Either[E, A]]] { source =>
          Bind(
            attempt(source),
            (r: Either[E, Any]) =>
              r match {
                case Left(error) => pure(Left(error))
                case Right(s) => attempt(fs(s))
              }
          )
        })

      case s: Suspend[F, a] =>
        Suspend(F.map(F.attempt(s.resource)) {
          case Left(error) => pure(Left(error))
          case Right(fa)   => attempt(fa)
        })
    }

  def flatMap[A, B](fa: Resource[F, A])(
      f: A => Resource[F, B]
  ): Resource[F, B] = Bind(fa, f)

  def tailRecM[A, B](
      a: A
  )(f: A => Resource[F, Either[A, B]]): Resource[F, B] =
    Resource.tailRecM(a)(f)

}
package no.scalabin.http4s.directives

import cats.data.OptionT
import cats.Monad
import cats.syntax.flatMap._
import cats.syntax.functor._
import org.http4s._

import scala.language.{higherKinds, reflectiveCalls}

case class Directive[F[+ _]: Monad, +L, +R](run: Request[F] => F[Result[L, R]]) {
  def flatMap[LL >: L, B](f: R => Directive[F, LL, B]): Directive[F, LL, B] =
    Directive[F, LL, B](req =>
      run(req).flatMap {
        case Result.Success(value) => f(value).run(req)
        case Result.Failure(value) => Monad[F].pure(Result.failure(value))
        case Result.Error(value)   => Monad[F].pure(Result.error(value))
    })

  def map[B](f: R => B): Directive[F, L, B] = Directive[F, L, B](req => run(req).map(_.map(f)))

  def filter[LL >: L](f: R => Directive.Filter[LL]): Directive[F, LL, R] =
    flatMap { r =>
      val result = f(r)
      if (result.result)
        Directive.success[F, R](r)
      else
        Directive.failure[F, LL](result.failure())
    }

  def withFilter[LL >: L](f: R => Directive.Filter[LL]): Directive[F, LL, R] = filter(f)

  def orElse[LL >: L, RR >: R](next: Directive[F, LL, RR]): Directive[F, LL, RR] =
    Directive[F, LL, RR](req =>
      run(req).flatMap {
        case Result.Success(value) => Monad[F].pure(Result.success(value))
        case Result.Failure(_)     => next.run(req)
        case Result.Error(value)   => Monad[F].pure(Result.error(value))
    })

  def |[LL >: L, RR >: R](next: Directive[F, LL, RR]): Directive[F, LL, RR] = orElse(next)

}

object Directive {

  implicit def monad[F[+ _]: Monad, L]: Monad[({ type X[A] = Directive[F, L, A] })#X] =
    new Monad[({ type X[A] = Directive[F, L, A] })#X] {
      override def flatMap[A, B](fa: Directive[F, L, A])(f: A => Directive[F, L, B]) = fa flatMap f

      override def pure[A](a: A) = Directive[F, L, A](_ => Monad[F].pure(Result.success(a)))

      override def tailRecM[A, B](a: A)(f: A => Directive[F, L, Either[A, B]]) =
        tailRecM(a)(a0 => Directive(f(a0).run))
    }

  def request[F[+ _]: Monad]: Directive[F, Nothing, Request[F]] = Directive(req => Monad[F].pure(Result.success(req)))

  def pure[F[+ _]: Monad, A](a: => A): Directive[F, Nothing, A] = monad[F, Nothing].pure(a)

  def result[F[+ _]: Monad, L, R](result: => Result[L, R]): Directive[F, L, R] = Directive[F, L, R](_ => Monad[F].pure(result))

  def success[F[+ _]: Monad, R](success: => R): Directive[F, Nothing, R] = pure(success)

  def failure[F[+ _]: Monad, L](failure: => L): Directive[F, L, Nothing] = result[F, L, Nothing](Result.failure(failure))

  def error[F[+ _]: Monad, L](error: => L): Directive[F, L, Nothing] = result[F, L, Nothing](Result.error(error))

  def liftF[F[+ _]: Monad, X](f: F[X]): Directive[F, Nothing, X] = Directive[F, Nothing, X](_ => f.map(Result.Success(_)))

  def successF[F[+ _]: Monad, X](f: F[X]): Directive[F, Nothing, X] = liftF(f)

  def failureF[F[+ _]: Monad, X](f: F[X]): Directive[F, X, Nothing] = Directive[F, X, Nothing](_ => f.map(Result.Failure(_)))

  def errorF[F[+ _]: Monad, X](f: F[X]): Directive[F, X, Nothing] = Directive[F, X, Nothing](_ => f.map(Result.Error(_)))

  case class Filter[+L](result: Boolean, failure: () => L)

  object commit {
    def flatMap[F[+ _]: Monad, R, A](f: Unit => Directive[F, R, A]): Directive[F, R, A] =
      commit(f(()))

    def apply[F[+ _]: Monad, R, A](d: Directive[F, R, A]): Directive[F, R, A] = Directive[F, R, A] { r =>
      d.run(r).map {
        case Result.Failure(response) => Result.error[R](response)
        case result                   => result
      }
    }
  }

  def getOrElseF[F[+ _]: Monad, L, R](opt: F[Option[R]], orElse: => F[L]): Directive[F, L, R] =
    Directive(_ => OptionT(opt).cata(orElse.map(Result.failure), v => Monad[F].pure(Result.success(v))).flatten)

  def getOrElse[F[+ _]: Monad, L, A](opt: Option[A], orElse: => F[L]): Directive[F, L, A] = opt match {
    case Some(r) => success(r)
    case None    => failureF(orElse)
  }
}

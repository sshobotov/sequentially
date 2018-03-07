package com.evolutiongaming.concurrent.sequentially

import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{Materializer, OverflowStrategy}
import com.evolutiongaming.concurrent.CurrentThreadExecutionContext
import com.evolutiongaming.concurrent.sequentially.Sequentially.{BufferSize, Substreams}
import com.evolutiongaming.concurrent.sequentially.SourceQueueHelper._

import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal

/**
  * Runs tasks sequentially for the same key and in parallel - for different keys
  */
trait SequentiallyAsync[-K] extends Sequentially[K] {

  def async[KK <: K, T](key: K)(task: => Future[T]): Future[T]

  def apply[KK <: K, T](key: KK)(task: => T): Future[T] = {
    async(key) {
      Future.successful(task)
    }
  }
}

object SequentiallyAsync {

  def apply[K](
    substreams: Int = Substreams,
    bufferSize: Int = BufferSize,
    overflowStrategy: OverflowStrategy = OverflowStrategy.backpressure)
    (implicit materializer: Materializer): SequentiallyAsync[K] = {

    val queue = Source
      .queue[Elem](bufferSize, overflowStrategy)
      .groupBy(substreams, _.substream)
      .buffer(bufferSize, OverflowStrategy.backpressure)
      .mapAsync(1) { _.apply() }
      .to(Sink.ignore)
      .run()

    val ec = materializer.executionContext
    implicit val ecNow = CurrentThreadExecutionContext

    case class Elem(substream: Int, apply: () => Future[Any])

    new SequentiallyAsync[K] {

      def async[KK <: K, T](key: K)(task: => Future[T]): Future[T] = {
        val promise = Promise[T]
        val safeTask = () => {
          val result = Future(task)(ec) flatMap identity
          promise completeWith result
          result.recover[Any] { case _ => () }
        }
        val substream = Substream(key, substreams)
        val elem = Elem(substream, safeTask)
        for {
          _ <- queue.offerOrError(elem, s"$key failed to enqueue task")
          result <- promise.future
        } yield result
      }
    }
  }


  def now[K]: SequentiallyAsync[K] = Now


  private object Now extends SequentiallyAsync[Any] {
    def async[KK <: Any, T](key: Any)(task: => Future[T]) = {
      try task catch { case NonFatal(failure) => Future.failed(failure) }
    }
  }
}
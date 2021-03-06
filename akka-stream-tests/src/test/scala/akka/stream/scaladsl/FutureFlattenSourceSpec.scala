/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import java.util.concurrent.{ CompletableFuture, TimeUnit }

import akka.stream._
import akka.stream.stage.{ GraphStage, GraphStageLogic, GraphStageWithMaterializedValue }
import akka.stream.testkit.Utils.{ TE, assertAllStagesStopped }
import akka.stream.testkit.{ StreamSpec, TestPublisher, TestSubscriber }
import akka.testkit.TestLatch
import akka.{ Done, NotUsed }

import scala.concurrent.{ Await, Future, Promise }

class FutureFlattenSourceSpec extends StreamSpec {

  implicit val materializer = ActorMaterializer()
  implicit def ec = system.dispatcher

  "Future source" must {

    val underlying: Source[Int, String] =
      Source(List(1, 2, 3)).mapMaterializedValue(_ ⇒ "foo")

    "emit the elements of the future source" in assertAllStagesStopped {

      val sourcePromise = Promise[Source[Int, String]]()
      val (sourceMatVal, sinkMatVal) =
        Source.fromFutureSource(sourcePromise.future)
          .toMat(Sink.seq)(Keep.both)
          .run()

      sourcePromise.success(underlying)
      // should complete as soon as inner source has been materialized
      sourceMatVal.futureValue should ===("foo")
      sinkMatVal.futureValue should ===(List(1, 2, 3))
    }

    "emit the elements from a source in a completion stage" in assertAllStagesStopped {
      val (sourceMatVal, sinkMatVal) =
        Source.fromSourceCompletionStage(
          // can't be inferred
          CompletableFuture.completedFuture[Graph[SourceShape[Int], String]](underlying)
        ).toMat(Sink.seq)(Keep.both)
          .run()

      sourceMatVal.toCompletableFuture.get(remainingOrDefault.toMillis, TimeUnit.MILLISECONDS) should ===("foo")
      sinkMatVal.futureValue should ===(List(1, 2, 3))
    }

    "handle downstream cancelling before the underlying Future completes" in assertAllStagesStopped {
      val sourcePromise = Promise[Source[Int, String]]()

      val (sourceMatVal, termination) =
        Source.fromFutureSource(sourcePromise.future)
          .watchTermination()(Keep.both)
          .to(Sink.cancelled)
          .run()

      // wait for cancellation to occur
      termination.futureValue should ===(Done)

      // even though canceled the underlying matval should arrive
      sourcePromise.success(underlying)
      sourceMatVal.futureValue should ===("foo")
    }

    "fail if the underlying Future is failed" in assertAllStagesStopped {
      val failure = TE("foo")
      val underlying = Future.failed[Source[Int, String]](failure)
      val (sourceMatVal, sinkMatVal) = Source.fromFutureSource(underlying).toMat(Sink.seq)(Keep.both).run()
      sourceMatVal.failed.futureValue should ===(failure)
      sinkMatVal.failed.futureValue should ===(failure)
    }

    "fail as the underlying Future fails after outer source materialization" in assertAllStagesStopped {
      val failure = TE("foo")
      val sourcePromise = Promise[Source[Int, String]]()
      val materializationLatch = TestLatch(1)
      val (sourceMatVal, sinkMatVal) =
        Source.fromFutureSource(sourcePromise.future)
          .mapMaterializedValue { value ⇒
            materializationLatch.countDown()
            value
          }
          .toMat(Sink.seq)(Keep.both)
          .run()

      // we don't know that materialization completed yet (this is still a bit racy)
      Await.ready(materializationLatch, remainingOrDefault)
      Thread.sleep(100)
      sourcePromise.failure(failure)

      sourceMatVal.failed.futureValue should ===(failure)
      sinkMatVal.failed.futureValue should ===(failure)
    }

    "fail as the underlying Future fails after outer source materialization with no demand" in assertAllStagesStopped {
      val failure = TE("foo")
      val sourcePromise = Promise[Source[Int, String]]()
      val testProbe = TestSubscriber.probe[Int]()
      val sourceMatVal =
        Source.fromFutureSource(sourcePromise.future)
          .to(Sink.fromSubscriber(testProbe))
          .run()

      testProbe.expectSubscription()
      sourcePromise.failure(failure)

      sourceMatVal.failed.futureValue should ===(failure)
    }

    "handle back-pressure when the future completes" in assertAllStagesStopped {
      val subscriber = TestSubscriber.probe[Int]()
      val publisher = TestPublisher.probe[Int]()

      val sourcePromise = Promise[Source[Int, String]]()

      val matVal = Source.fromFutureSource(sourcePromise.future)
        .to(Sink.fromSubscriber(subscriber))
        .run()

      subscriber.ensureSubscription()

      sourcePromise.success(Source.fromPublisher(publisher).mapMaterializedValue(_ ⇒ "woho"))

      // materialized value completes but still no demand
      matVal.futureValue should ===("woho")

      // then demand and let an element through to see it works
      subscriber.ensureSubscription()
      subscriber.request(1)
      publisher.expectRequest()
      publisher.sendNext(1)
      subscriber.expectNext(1)
      publisher.sendComplete()
      subscriber.expectComplete()
    }

    class FailingMatGraphStage extends GraphStageWithMaterializedValue[SourceShape[Int], String] {
      val out = Outlet[Int]("whatever")
      override val shape: SourceShape[Int] = SourceShape(out)
      override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, String) = {
        throw TE("argh, materialization failed")
      }

    }

    // Behaviour when inner source throws during materialization is undefined (leaks ActorGraphInterpreters)
    // until ticket #22358 has been fixed, this test fails because of it
    "fail when the future source materialization fails" in pendingUntilFixed(assertAllStagesStopped {
      val failure = TE("MatEx")

      val (sourceMatVal, sinkMatVal) =
        Source.fromFutureSource(
          Future.successful(Source.fromGraph(new FailingMatGraphStage))
        ).toMat(Sink.seq)(Keep.both)
          .run()

      sinkMatVal.failed.futureValue should ===(failure)
      println(sourceMatVal.futureValue)
      sourceMatVal.failed.futureValue should ===(failure)

    })
  }
}

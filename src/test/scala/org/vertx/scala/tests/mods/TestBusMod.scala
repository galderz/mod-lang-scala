package org.vertx.scala.tests.mods

import org.vertx.scala.platform.Verticle
import org.vertx.scala.mods.ScalaBusMod
import org.vertx.scala.core.eventbus.Message
import org.vertx.scala.core.json._
import org.vertx.scala.core.FunctionConverters._
import org.vertx.scala.mods.replies._
import scala.concurrent.Promise
import scala.util.Try
import scala.util.Success
import scala.util.Failure
import org.vertx.scala.mods.BusModException
import scala.concurrent.Future
import org.vertx.testtools.VertxAssert

class TestBusMod extends Verticle with ScalaBusMod {
  override def start(promise: Promise[Unit]): Unit = {
    vertx.eventBus.registerHandler("test-bus-mod", this, {
      case Success(_) => promise.success()
      case Failure(ex) => promise.failure(ex)
    }: Try[Void] => Unit)
  }

  override def receive = (msg: Message[JsonObject]) => {
    case "sync-hello" => Ok(Json.obj("result" -> "sync-hello-result"))
    case "sync-error" => throw new RuntimeException("sync-error-result")
    case "sync-echo" =>
      Option(msg.body.getString("echo")) match {
        case Some(echo) => Ok(Json.obj("echo" -> echo))
        case None => Error("No echo argument given!", "NO_ECHO_GIVEN")
      }
    case "async-hello" => AsyncReply {
      val p = Promise[BusModReply]
      vertx.setTimer(10, { timerId => p.success(Ok(Json.obj("result" -> "async-hello-result"))) })
      p.future
    }
    case "async-cascade" => AsyncReply {
      val p = Promise[BusModReply]
      vertx.setTimer(10, { timerId =>
        p.success(AsyncReply {
          val p2 = Promise[BusModReply]
          vertx.setTimer(10, { timerId2 =>
            p2.success(Ok(Json.obj("result" -> "async-cascade-result")))
          })
          p2.future
        })
      })
      p.future
    }
    case "async-cascade-uncaught" => AsyncReply {
      val p = Promise[BusModReply]
      vertx.setTimer(10, { timerId =>
        p.failure(new RuntimeException("async-uncaught-exception"))
      })
      p.future
    }
    case "async-error-1" => AsyncReply {
      throw new RuntimeException("uncaught-exception")
    }
    case "async-error-2" => AsyncReply {
      Future.failed(new BusModException("caught-exception", id = "CAUGHT_EXCEPTION"))
    }
    case "reply-notimeout" =>
      Ok(Json.obj("echo" -> msg.body.getString("echo")), Some(Receiver(msg => {
        case "reply-notimeout-reply" => Ok(Json.obj("echo" -> msg.body.getString("echo")))
      })))
    case "reply-timeout" =>
      val clientAddress = msg.body().getString("address")
      Ok(Json.obj("state" -> "waitForTimeout"), Some(ReceiverWithTimeout(msg => {
        case x =>
          vertx.eventBus.send(clientAddress, Json.obj("state" -> "error:should-not-get-reply"))
          Error("should not receive message")
      }, 500L, { () =>
        vertx.eventBus.send(clientAddress, Json.obj("state" -> "timeout"))
      })))
    case "reply-error-ok" =>
      Error("no.", replyHandler = Some(Receiver(msg => {
        case "please" => Ok(Json.obj("echo" -> msg.body.getString("echo")))
      })))
    case "no-direct-reply" =>
      val clientAddress = msg.body.getString("address")
      vertx.eventBus.send(clientAddress, Json.obj("echo" -> msg.body.getString("echo")))
      NoReply
  }

}
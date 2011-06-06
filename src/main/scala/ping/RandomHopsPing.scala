/**
 * @author Francisco de Freitas
 *
 * This is a microbenchmark to test a feature of Akka, namely ReceiveTimeout.
 *
 * The algorithm sends 'ping' messages to some initial workers (akka-actors). These than randomly
 * choose a worker to send another 'ping' message to. At each worker, the message hop is decreased until
 * it reaches zero, converging the algorithm to terminate.
 *
 */
package ping

import akka.actor.Actor
import Actor._
import akka.actor.ActorRef
import scala.util.Random
import akka.actor.PoisonPill
import java.util.Date

import org.clapper.argot.{ ArgotUsageException, ArgotParser }
import org.clapper.argot.ArgotConverters._

import akka.actor.ReceiveTimeout

object RandomHopsPing extends App {

  val parser = new ArgotParser("Evaluation")

  val wks = parser.option[Int](List("w", "workers"), "workers", "Number of workers")(convertInt)
  val msgs = parser.option[Int](List("m", "messages"), "messages", "Number of messages to be sent")(convertInt)
  val hps = parser.option[Int](List("h", "hops"), "hops", "Number of hops")(convertInt)
  val rps = parser.option[Int](List("r", "repetitions"), "repetitions", "Number of run repetitions")(convertInt)

  try {
    parser.parse(args)
  } catch {
    case e: ArgotUsageException => println(e.message)
  }

  startPinging(workers = wks.value.getOrElse(4), messages = msgs.value.getOrElse(10000), hops = hps.value.getOrElse(150), repetitions = rps.value.getOrElse(1))

  sealed trait PingMessage
  case class Start extends PingMessage
  case class Ping(hops: Int) extends PingMessage
  case class End extends PingMessage

  var workers: Vector[ActorRef] = _

  var runs: List[Long] = List()

  class Worker(coordRef: ActorRef, numWorkers: Int) extends Actor {

    /*self.receiveTimeout = Some(30000L)

    def receive = {

      case ReceiveTimeout =>*/

    def receive = {

      case Ping(hops) =>
        if (hops == 0)
          coordRef ! End
        else
          workers(Random.nextInt(numWorkers)) ! Ping(hops - 1)

      case End =>
        self.stop()
    }

  }

  class Master(numWorkers: Int, numMessages: Int, numHops: Int, repetitions: Int) extends Actor {

    var start: Long = 0
    var end: Long = 0
    var receivedEnds: Int = 0
    var reps: Int = 1

    def receive = {

      case Start =>

        workers = Vector()
        receivedEnds = 0
        // create the workers
        workers = Vector.fill(numWorkers)(actorOf(new Worker(self, numWorkers)).start())

        println("Master start run #" + reps)

        start = System.nanoTime

        // send to half of the workers some messages
        for (i <- 0 until (Math.floor(numWorkers / 2)).asInstanceOf[Int])
          for (j <- 0 until numMessages)
            workers(Random.nextInt(numWorkers)) ! Ping(numHops)

      case End =>
        receivedEnds += 1

        // all messages have reached 0 hops
        if (receivedEnds == Math.floor(numWorkers / 2).asInstanceOf[Int] * numMessages) {
          end = System.nanoTime

          println("Run #" + reps + " ended! Time = " + ((end - start) / 1000000.0) + "ms")

          runs = (end - start) :: runs

          if (reps != repetitions) {
            reps += 1
            self ! Start
          } else {
            println("Repetitions reached. Broadcasting shutdown...")
            workers.foreach { x => x ! PoisonPill }
            self.stop()
          }
        }

    }

    override def preStart {
      println("Start pinging around: " + new Date(System.currentTimeMillis))
    }

    override def postStop {
      println("End: " + new Date(System.currentTimeMillis))
      val avg = runs.foldLeft(0l)(_ + _) / runs.size
      println("Average execution time = " + avg / 1000000.0 + " ms")
      System.exit(0)
    }

  }

  def startPinging(workers: Int, messages: Int, hops: Int, repetitions: Int) {

    println("Workers: " + workers)
    println("Messages: " + messages)
    println("Hops: " + hops)
    println("Repetitions: " + repetitions)

    // create the master
    val coordRef = actorOf(new Master(workers, messages, hops, repetitions)).start()

    // start the calculation
    coordRef ! Start

  }

}
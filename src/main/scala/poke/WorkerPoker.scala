package poke

import akka.actor.Actor
import Actor._
import akka.actor.ActorRef
import scala.util.Random
import akka.actor.PoisonPill
import java.util.Date

import org.clapper.argot.{ ArgotUsageException, ArgotParser }
import org.clapper.argot.ArgotConverters._

import akka.actor.ReceiveTimeout

object WorkerPoker extends App {

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

  startingPoking(workers = wks.value.getOrElse(4), messages = msgs.value.getOrElse(10000), hops = hps.value.getOrElse(150), repetitions = rps.value.getOrElse(1))

  sealed trait PokeMessage
  case class Start extends PokeMessage
  case class Poke(hops: Int) extends PokeMessage
  case class End extends PokeMessage

  var workers: Vector[ActorRef] = _

  var runs: List[Long] = List()

  class PingerPonger(coordRef: ActorRef, numWorkers: Int) extends Actor {

    /*self.receiveTimeout = Some(30000L)

    def receive = {

      case ReceiveTimeout =>*/

      def receive = {

      case Poke(hops) =>
        if (hops == 0)
          coordRef ! End
        else
          workers(Random.nextInt(numWorkers)) ! Poke(hops - 1)

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
        workers = Vector.fill(numWorkers)(actorOf(new PingerPonger(self, numWorkers)).start())

        println("Master start run #" + reps)

        start = System.currentTimeMillis

        // send to half of the workers some messages
        for (i <- 0 until (Math.floor(numWorkers / 2)).asInstanceOf[Int])
          for (j <- 0 until numMessages)
            workers(Random.nextInt(numWorkers)) ! Poke(numHops)

      case End =>
        receivedEnds += 1

        if (receivedEnds == Math.floor(numWorkers / 2).asInstanceOf[Int] * numMessages) {
          end = System.currentTimeMillis

          println("Run #" + reps + " ended! Time = " + (end - start) + "ms")

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
      println("Start poking around: " + new Date(System.currentTimeMillis))
    }

    override def postStop {
      println("End: " + new Date(System.currentTimeMillis))
      val avg = runs.foldLeft(0l)(_ + _) / runs.size
      println("Average execution time = " + avg + " ms")
      System.exit(0)
    }

  }

  def startingPoking(workers: Int, messages: Int, hops: Int, repetitions: Int) {

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
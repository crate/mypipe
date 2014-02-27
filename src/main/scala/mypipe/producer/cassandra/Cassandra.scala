package mypipe.producer.cassandra

import akka.actor.{ Actor, ActorSystem, Props }
import akka.pattern.ask
import scala.concurrent.duration._
import com.netflix.astyanax.MutationBatch
import mypipe.{ Conf, Log }
import scala.concurrent.Await
import mypipe.api._
import mypipe.api.DeleteMutation
import mypipe.api.UpdateMutation
import mypipe.api.InsertMutation

case class Queue(mutation: Mutation[_])
case class QueueList(mutations: List[Mutation[_]])
case object Flush

class CassandraBatchWriter(mappings: List[Mapping[MutationBatch]]) extends Actor {

  def receive = {
    case Queue(mutation)      ⇒ map(mutation)
    case QueueList(mutationz) ⇒ map(mutationz)
    case Flush                ⇒ sender ! flush()
  }

  def flush(): Boolean = {
    Log.info(s"Flush ${CassandraMappings.mutations.size} mutations.")

    val results = CassandraMappings.mutations.map(m ⇒ {
      try {
        // TODO: use execute async
        Some(m._2.execute())
      } catch {
        case e: Exception ⇒ {
          Log.severe(s"Could not execute mutation batch: ${e.getMessage} -> ${e.getStackTraceString}")
          None
        }
      }
    })

    CassandraMappings.mutations.clear()
    true
  }

  def map(mutations: List[Mutation[_]]): List[MutationBatch] = {
    mutations.map(m ⇒ map(m)).flatten
  }

  def map(mutation: Mutation[_]): List[MutationBatch] = {

    mutation match {

      case i: InsertMutation ⇒ {
        mappings.map(m ⇒ m.map(i)).filter(_.isDefined).map(_.get)
      }

      case u: UpdateMutation ⇒ {
        mappings.map(m ⇒ m.map(u)).filter(_.isDefined).map(_.get)
      }

      case d: DeleteMutation ⇒ {
        mappings.map(m ⇒ m.map(d)).filter(_.isDefined).map(_.get)
      }
    }
  }
}

object CassandraBatchWriter {
  def props(mappings: List[Mapping[MutationBatch]]): Props = Props(new CassandraBatchWriter(mappings))
}

case class CassandraProducer(mappings: List[Mapping[MutationBatch]]) extends Producer {

  val system = ActorSystem("mypipe")
  val worker = system.actorOf(CassandraBatchWriter.props(mappings), "CassandraBatchWriterActor")

  def queue(mutation: Mutation[_]) {
    worker ! Queue(mutation)
  }

  def queueList(mutations: List[Mutation[_]]) {
    worker ! QueueList(mutations)
  }

  def flush() {
    val future = worker.ask(Flush)(Conf.SHUTDOWN_FLUSH_WAIT_SECS seconds)
    val result = Await.result(future, Conf.SHUTDOWN_FLUSH_WAIT_SECS seconds).asInstanceOf[Boolean]
  }
}

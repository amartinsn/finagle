package com.twitter.finagle.kestrel

import _root_.java.util.concurrent.atomic.AtomicBoolean
import _root_.java.util.logging.{Logger, Level}
import org.jboss.netty.buffer.ChannelBuffer
import com.twitter.util.{
  Future, Duration, Time,
  Return, Throw, Promise,
  Timer, NullTimer}
import com.twitter.conversions.time._
import com.twitter.finagle.kestrel.protocol._
import com.twitter.finagle.memcached.util.ChannelBufferUtils._
import com.twitter.concurrent.{ChannelSource, Channel}
import com.twitter.finagle.builder.ClientBuilder
import com.twitter.finagle.{ServiceFactory, Service}
import com.twitter.concurrent.{Offer, Broker}

object ReadClosedException extends Exception
object OutOfRetriesException extends Exception


/**
 * A message that has been read: consists of the message itself, and
 * an offer to acknowledge.
 */
case class ReadMessage(bytes: ChannelBuffer, ack: Offer[Unit])

/**
 * An ongoing transactional read (from {{read}}).
 */
trait ReadHandle {
  /**
   * An offer to synchronize on the next message.  A new message is
   * available only when the previous one has been acknowledged.
   */
  val messages: Offer[ReadMessage]

  /**
   * Indicates an error in the read.
   */
  val error: Offer[Throwable]

  /**
   * Closes the read.  Closes are signaled as an error with
   * {{ReadClosedException}} when the close has completed.
   */
  def close()
}

// A convenience constructor using an offer for closing.
private[kestrel] object ReadHandle {
  def apply(
    _messages: Offer[ReadMessage],
    _error: Offer[Throwable],
    closeOf: Offer[Unit]
  ) = new ReadHandle {
    val messages = _messages
    val error = _error
    def close() = closeOf()
  }
}

object Client {
  def apply(raw: ServiceFactory[Command, Response]): Client = {
    new ConnectedClient(raw)
  }

  def apply(hosts: String): Client = {
    val service = ClientBuilder()
      .codec(Kestrel())
      .hosts(hosts)
      .hostConnectionLimit(1)
      .buildFactory()
    apply(service)
  }
}

/**
 * A friendly Kestrel client Interface.
 */
trait Client {
  /**
   * Enqueue an item.
   *
   * @param  expiry  how long the item is valid for (Kestrel will delete the item
   * if it isn't dequeued in time).
   */
  def set(queueName: String, value: ChannelBuffer, expiry: Time = Time.epoch): Future[Response]

  /**
   * Dequeue an item.
   *
   * @param  waitUpTo  if the queue is empty, indicate to the Kestrel server how
   * long to block the operation, waiting for something to arrive, before returning None
   */
  def get(queueName: String, waitUpTo: Duration = 0.seconds): Future[Option[ChannelBuffer]]

  /**
   * Delete a queue. Removes the journal file on the remote server.
   */
  def delete(queueName: String): Future[Response]

  /**
   * Flush a queue. Empties all items from the queue without deleting the journal.
   */
  def flush(queueName: String): Future[Response]

  /**
   * Get a Channel for the given queue, for reading. Messages begin dequeueing
   * from the Server when the first Observer responds, and pauses when all
   * Observers have disposed. Messages are acknowledged (closed) on the remote
   * server when all observers have successfully completed their write Future.
   * If any observer's write Future errors, the Channel is closed and the
   * item is rolled-back (aborted) on the remote server.
   *
   * @return A Channel object that you can receive items from as they arrive.
   */
  def from(queueName: String, waitUpTo: Duration = 0.seconds): Channel[ChannelBuffer]

  /**
   * Get a ChannelSource for the given queue
   *
   * @return  A ChannelSource that you can send items to.
   */
  def to(queueName: String): ChannelSource[ChannelBuffer]

  /**
   * Read indefinitely from the given queue with transactions.  Note
   * that {{read}} will reserve a connection for the duration of the
   * read.  Note that this does no buffering: we await acknowledment
   * (through synchronizing on ReadMessage.ack) before acknowledging
   * that message to the kestrel server & reading the next one.
   *
   * @return A read handle.
   */
  def read(queueName: String): ReadHandle

  /**
   * Read from a queue reliably: retry streaming reads on failure
   * (which may indeed be backed by multiple kestrel hosts).  This
   * presents to the user a virtual "reliable" stream of messages, and
   * errors are transparent.
   *
   * @param queueName the queue to read from
   * @param timer a timer used to delay retries
   * @param retryBackoffs a (possibly infinite) stream of durations
   * comprising a backoff policy
   *
   * Note: the use of call-by-name for the stream is in order to
   * ensure that we do not suffer a space leak for infinite retries.
   */
  def readReliably(
    queueName: String,
    timer: Timer,
    retryBackoffs: => Stream[Duration]
  ): ReadHandle = {
    val error = new Broker[Throwable]
    val messages = new Broker[ReadMessage]
    val close = new Broker[Unit]

    def loop(handle: ReadHandle, backoffs: Stream[Duration]) {
      Offer.select(
        // proxy messages
        handle.messages { m =>
          messages ! m
          // a succesful read always resets the backoffs
          loop(handle, retryBackoffs)
        },

        // retry on error
        handle.error { t =>
          backoffs match {
            case delay #:: rest =>
              timer.schedule(delay.fromNow) { loop(read(queueName), rest) }
            case _ =>
              error ! OutOfRetriesException
          }
        },

        // proxy the close, and close our reliable channel
        close.recv { _=>
          handle.close()
          error ! ReadClosedException
        }
      )
    }

    loop(read(queueName), retryBackoffs)

    ReadHandle(messages.recv, error.recv, close.send(()))
  }

  /**
   * {{readReliably}} with infinite, 0-second backoff retries.
   */
  def readReliably(queueName: String): ReadHandle =
    readReliably(queueName, new NullTimer, Stream.continually(0.seconds))

  /*
   * Write indefinitely to the given queue.  The given offer is
   * synchronized on indefinitely, writing the items as they become
   * available.  Unlike {{read}}, {{write}} does not reserve a
   * connection.
   *
   * @return a Future indicating client failure.
   */
  def write(queueName: String, offer: Offer[ChannelBuffer]): Future[Throwable]

  /**
   * Close any consume resources such as TCP Connections. This should will not
   * release resources created by the from() and to() methods; it is the
   * responsibility of the caller to release those resources directly.
   */
  def close()
}

/**
 * A Client representing a single TCP connection to a single server.
 *
 * @param underlying  a ServiceFactory[Command, Response].
 */
protected[kestrel] class ConnectedClient(underlying: ServiceFactory[Command, Response])
  extends Client
{
  private[this] val log = Logger.getLogger(getClass.getName)

  def flush(queueName: String) = {
    underlying.service(Flush(queueName))
  }

  def delete(queueName: String) = {
    underlying.service(Delete(queueName))
  }

  def set(queueName: String, value: ChannelBuffer, expiry: Time = Time.epoch) = {
    underlying.service(Set(queueName, expiry, value))
  }

  def get(queueName: String, waitUpTo: Duration = 0.seconds) = {
    underlying.service(Get(queueName, Some(waitUpTo))) map {
      case Values(Seq()) => None
      case Values(Seq(Value(key, value))) => Some(value)
      case _ => throw new IllegalArgumentException
    }
  }

  def from(queueName: String, waitUpTo: Duration = 10.seconds): Channel[ChannelBuffer] = {
    val result = new ChannelSource[ChannelBuffer]
    val isRunning = new AtomicBoolean(false)
    result.numObservers respond { i: Int =>
      i match {
        case 0 =>
          isRunning.set(false)
        case 1 =>
          // only start receiving if we weren't already running
          if (!isRunning.getAndSet(true)) {
            underlying.make() onSuccess { service =>
              receive(isRunning, service, Open(queueName, Some(waitUpTo)), result)
            } onFailure { t =>
              log.log(Level.WARNING, "Could not make service", t)
              result.close()
            }
          }
        case _ =>
      }
      Future.Done
    }
    result
  }

  def to(queueName: String): ChannelSource[ChannelBuffer] = {
    val to = new ChannelSource[ChannelBuffer]
    to.respond { item =>
      set(queueName, item).unit onFailure { _ =>
        to.close()
      }
    }
    to
  }

  // note: this implementation uses "GET" requests, not "MONITOR",
  // so it will incur many roundtrips on quiet queues.
  def read(queueName: String): ReadHandle = {
    val error = new Broker[Throwable]  // this is sort of like a latch …
    val messages = new Broker[ReadMessage]  // todo: buffer?
    val close = new Broker[Unit]

    val open = Open(queueName, None)
    val closeAndOpen = CloseAndOpen(queueName, None)
    val abort = Abort(queueName)

    def recv(service: Service[Command, Response], command: GetCommand) {
      val reply = service(command).toOffer
      Offer.select(
        reply {
          case Return(Values(Seq(Value(_, item)))) =>
            val ack = new Broker[Unit]
            messages ! ReadMessage(item, ack.send(()))

            Offer.select(
              ack.recv { _ => recv(service, closeAndOpen) },
              close.recv { t => service.release(); error ! ReadClosedException }
            )

          case Return(Values(Seq())) =>
            recv(service, open)

          case Return(_) =>
            service.release()
            error ! new IllegalArgumentException("invalid reply from kestrel")

          case Throw(t) =>
            service.release()
            error ! t
        },

        close.recv { _ =>
          reply andThen {
            service(abort) ensure {
              service.release()
              error ! ReadClosedException
            }
          }
        }
      )
    }

    underlying.make() onSuccess { recv(_, open) } onFailure { error ! _ }

    ReadHandle(messages.recv, error.recv, close.send(()))
  }

  def write(queueName: String, offer: Offer[ChannelBuffer]): Future[Throwable] = {
    val closed = new Promise[Throwable]
    write(queueName, offer, closed)
    closed
  }

  private[this] def write(
    queueName: String,
    offer: Offer[ChannelBuffer],
    closed: Promise[Throwable]
  ) {
    offer() foreach { item =>
      set(queueName, item).unit onSuccess { _ =>
        write(queueName, offer)
      } onFailure { t =>
        closed() = Return(t)
      }
    }
  }

  private[this] def receive(
    isRunning: AtomicBoolean,
    service: Service[Command, Response],
    command: GetCommand,
    channel: ChannelSource[ChannelBuffer])
  {
    def receiveAgain(command: GetCommand) {
      receive(isRunning, service, command, channel)
    }

    def cleanup() {
      channel.close()
      service.release()
    }

    // serialize() because of the check(isRunning)-then-act(send) idiom.
    channel.serialized {
      if (isRunning.get && service.isAvailable) {
        service(command) onSuccess {
          case Values(Seq(Value(key, item))) =>
            try {
              Future.join(channel.send(item)) onSuccess { _ =>
                receiveAgain(CloseAndOpen(command.queueName, command.timeout))
              } onFailure { t =>
                // abort if not all observers ack the send
                service(Abort(command.queueName)) ensure { cleanup() }
              }
            }

          case Values(Seq()) =>
            receiveAgain(Open(command.queueName, command.timeout))

          case _ =>
            throw new IllegalArgumentException

        } onFailure { t =>
          log.log(Level.WARNING, "service produced exception", t)
          cleanup()
        }
      } else {
        cleanup()
      }
    }
  }

  private[this] class ChannelSourceWithService(serviceFuture: Future[Service[Command, Response]])
    extends ChannelSource[ChannelBuffer]
  {
    private[this] val log = Logger.getLogger(getClass.getName)

    serviceFuture handle { case t =>
      log.log(Level.WARNING, "service produced exception", t)
      this.close()
    }

    override def close() {
      try {
        serviceFuture.foreach { _.release() }
      } finally {
        super.close()
      }
    }
  }

  def close() {
    underlying.close()
  }
}
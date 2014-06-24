package com.twitter.finagle.exp.zookeeper.client

import com.twitter.finagle.exp.zookeeper.connection.ConnectionManager
import com.twitter.finagle.{CancelledRequestException, WriteException, ChannelException}
import com.twitter.finagle.exp.zookeeper._
import com.twitter.finagle.exp.zookeeper.session.Session.States
import com.twitter.finagle.exp.zookeeper.session.{Session, SessionManager}
import com.twitter.finagle.exp.zookeeper.watch.WatchManager
import com.twitter.finagle.exp.zookeeper.ZookeeperDefs.OpCode
import com.twitter.finagle.transport.Transport
import com.twitter.io.Buf
import com.twitter.util._
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable

class ResponseMatcher(trans: Transport[Buf, Buf]) {
  /**
   * Local variables
   * processesReq - queue of requests waiting for responses
   * watchManager - the session watch manager
   * isReading - the reading loop is started or not
   *
   * encoder - creates a complete Packet from a request
   * decoder - creates a response from a Buffer
   */
  private[this] val processedReq = new mutable.SynchronizedQueue[(RequestRecord, Promise[RepPacket])]

  private[this] var connectionManager: Option[ConnectionManager] = None
  private[this] var sessionManager: Option[SessionManager] = None
  private[this] var watchManager: Option[WatchManager] = None
  private[this] var session: Session = new Session()

  private[this] val isReading = new AtomicBoolean(false)
  private[this] var hasDispatcherFailed = false
  private[this] val encoder = new Writer(trans)
  private[this] val decoder = new Reader(trans)

  def read(): Future[Unit] = {
    val fullRep = if (!hasDispatcherFailed) {
      trans.read() transform {
        case Return(buffer) =>

          val currentReq: Option[(RequestRecord, Promise[RepPacket])] =
            if (processedReq.size > 0) Some(processedReq.front) else None

          decoder.read(currentReq, buffer) onFailure { exc =>
            // If this exception is associated to a request, then propagate to the promise
            if (currentReq.isDefined) {
              processedReq.dequeue()._2.setException(exc)
            } else {
              Throw(new RuntimeException("Undefined exception during reading ", exc))
            }
          }

        case Throw(exc) => exc match {
          case excpt: ChannelException =>
            failDispatcher(excpt)
            Future.exception(new CancelledRequestException(excpt))
          case excpt: WriteException =>
            failDispatcher(excpt)
            Future.exception(new CancelledRequestException(excpt))
          case exc: Exception => Future.exception(new CancelledRequestException(exc))
        }
      }
    } else {
      Future.exception(new CancelledRequestException)
    }

    // Fixme in case of connection failure, the pending request queue can be emptied while decoding a correct response
    fullRep transform {
      case Return(rep) => rep.response match {
        // This is a notification
        case Some(event: WatchEvent) => Future.Done
        // This is a Response with Body
        case Some(resp: Response) =>
          // The request record is dequeued now that it's satisfied
          processedReq.dequeue()._2.setValue(rep)
          Future.Done
        // This is a Response without Body
        case None =>
          processedReq.dequeue()._2.setValue(rep)
          Future.Done
      }

      // This case is already handled during decoder.read (see onFailure)
      case Throw(exc) => Future.Done
    }
  }

  def readLoop(): Future[Unit] = {
    if (!session.isClosingSession.get() && !hasDispatcherFailed) read() before readLoop()
    else Future.Done
  }

  /**
   * We make decisions depending on request type,
   * a request record of the Packet is added to the queue, the packet is
   * finally written to the transport.
   * @param req the request to send
   * @return a Future[Unit] when the request is finally written
   */
  def write(req: ReqPacket): Future[RepPacket] = req match {
    case ReqPacket(None,
    Some(ConfigureRequest(Some(conMngr), Some(sessMngr), Some(watchMngr)))) =>
      connectionManager = Some(conMngr)
      sessionManager = Some(sessMngr)
      watchManager = Some(watchMngr)
      Future(RepPacket(StateHeader(0, 0), None))

    case ReqPacket(None, Some(ConfigureRequest(None, None, None))) =>
      session = sessionManager.get.session
      Future(RepPacket(StateHeader(0, 0), None))

    // ZooKeeper Request
    case ReqPacket(_, _) =>
      if (!hasDispatcherFailed) {
        val reqRecord = req match {
          case ReqPacket(Some(header), _) =>
            RequestRecord(header.opCode, Some(header.xid))
          case ReqPacket(None, Some(req: ConnectRequest)) =>
            RequestRecord(OpCode.CREATE_SESSION, None)
        }
        // The request is about to be written, so we enqueue it in the waiting request queue
        val p = new Promise[RepPacket]()

        synchronized {
          processedReq.enqueue((reqRecord, p))
          encoder.write(req) flatMap { unit =>
            if (!isReading.getAndSet(true)) {
              readLoop()
            }
            p
          }
        }
      } else {
        Future.exception(new CancelledRequestException)
      }
  }

  /**
   * Checks an association between a request and a ReplyHeader
   * @param reqRecord expected request details
   * @param repHeader freshly decoded ReplyHeader
   */
  def checkAssociation(reqRecord: RequestRecord, repHeader: ReplyHeader) = {
    //println("--- associating:  %d and %d ---".format(reqRecord.xid.get, repHeader.xid))
    /*if (packet.requestHeader.getXid() != replyHdr.getXid()) {
      packet.replyHeader.setErr(
        KeeperException.Code.CONNECTIONLOSS.intValue());
      throw new IOException("Xid out of order. Got Xid "
        + replyHdr.getXid() + " with err " +
        + replyHdr.getErr() +
        " expected Xid "
        + packet.requestHeader.getXid()
        + " for a packet with details: "
        + packet );
    }*/
    if (reqRecord.xid.isDefined)
      if (reqRecord.xid.get != repHeader.xid) throw new ZkDispatchingException("wrong association")
  }

  class Reader(trans: Transport[Buf, Buf]) {

    /**
     * This function will try to decode the buffer depending if a request
     * is waiting for a response or not.
     * In case there is a request, it will try to decode with readFromRequest,
     * if an exception is thrown then it will try again with readFromHeader.
     * @param pendingRequest expected request description
     * @param buffer current buffer
     * @return
     */
    def read(
      pendingRequest: Option[(RequestRecord, Promise[RepPacket])],
      buffer: Buf): Future[RepPacket] = {
      /**
       * if Some(record) then a request is waiting for a response
       * if None then this is a watchEvent
       */
      val rep = pendingRequest match {
        case Some(record) => readFromRequest(record, buffer)
        case None => readFromHeader(buffer)
      }

      rep match {
        case Return(response) => response

        case Throw(exc1) => exc1 match {
          /**
           * This is a decoding error, we should try to decode the buffer
           * as a watchEvent
           */
          case decodingExc: ZkDecodingException =>
            readFromHeader(buffer) match {
              case Return(eventRep) => eventRep
              case Throw(exc2) =>
                Future.exception(ZkDecodingException("Impossible to decode this response")
                  .initCause(exc2))
            }

          // fixme we should discard dispatchingExc at the moment
          case dispatchingExc: ZkDispatchingException =>
            readFromHeader(buffer) match {
              case Return(eventRep) => eventRep
              case Throw(exc2) =>
                Future.exception(ZkDecodingException("Impossible to decode this response")
                  .initCause(exc2))
            }
          case serverExc: ZookeeperException => Future.exception(exc1)

          case exc: Throwable =>
            Future.exception(new RuntimeException("Unexpected exception during decoding").initCause(exc))
        }
      }
    }

    /**
     * We try to decode the buffer by reading the ReplyHeader
     * and matching the xid to find the message's type
     * @param buf the current buffer
     * @return possibly the (header, response) or an exception
     */
    def readFromHeader(buf: Buf): Try[Future[RepPacket]] = {
      val (header, rem) = ReplyHeader(buf) match {
        case Return((header@ReplyHeader(_, _, 0), buf2)) => (header, buf2)
        case Return((header@ReplyHeader(_, _, err), buf2)) =>
          throw ZookeeperException.create("Error while readFromHeader :", err)
        case Throw(exc) => throw ZkDecodingException("Error while decoding header")
          .initCause(exc)
      }

      header.xid match {
        case -1 =>
          WatchEvent(rem) match {
            case Return((event@WatchEvent(_, _, _), rem2)) =>
              // Notifies the watch manager we have a new watchEvent
              sessionManager.get.session.parseWatchEvent(event)
              watchManager.get.process(event)
              val packet = RepPacket(StateHeader(header), Some(event))
              Return(Future(packet))
            case Throw(exc) =>
              Throw(ZkDecodingException("Error while decoding watch event").initCause(exc))
          }

        case _ =>
          Throw(ZkDecodingException("Could not decode this Buf"))
      }
    }

    /**
     * If a request is expected, this function will be called first
     * the expected response's opCode is matched, so that we have the
     * correct way to decode. The header is decoded first, the resulting
     * header is checked with checkAssociation to make sure both xids
     * are equal.
     * The body is decoded next and sent with the header in a Future
     *
     * If any exception is thrown then the readFromHeader will be called
     * with the rewinded buffer reader
     * @param req expected request
     * @param buf current buffer reader
     * @return possibly the (header, response) or an exception
     */
    def readFromRequest(
      req: (RequestRecord, Promise[RepPacket]),
      buf: Buf): Try[Future[RepPacket]] = Try {
      val (reqRecord, _) = req

      reqRecord.opCode match {
        case OpCode.AUTH =>
          ReplyHeader(buf) match {
            case Return((header, rem)) =>
              checkAssociation(reqRecord, header)
              Future(RepPacket(StateHeader(header), None))
            case Throw(exc) => throw exc
          }

        case OpCode.CREATE_SESSION =>
          ConnectResponse(buf) match {
            case Return((body, rem)) =>
              // Create a temporary session
              session = new Session()
              session.state = States.CONNECTED
              session.isFirstConnect.set(false)
              Future(RepPacket(StateHeader(0, 0), Some(body)))

            case Throw(exception) => throw exception
          }

        case OpCode.PING =>
          ReplyHeader(buf) match {
            case Return((header, rem)) =>
              checkAssociation(reqRecord, header)
              Future(RepPacket(StateHeader(header), None))
            case Throw(exception) => throw exception
          }

        case OpCode.CLOSE_SESSION =>
          ReplyHeader(buf) match {
            case Return((header, rem)) =>
              checkAssociation(reqRecord, header)
              Future(RepPacket(StateHeader(header), None))
            case Throw(exception) => throw exception
          }

        case OpCode.CREATE =>
          ReplyHeader(buf) match {
            case Return((header, rem)) =>
              checkAssociation(reqRecord, header)

              if (header.err == 0) {
                CreateResponse(rem) match {
                  case Return((body, rem2)) =>
                    Future(RepPacket(StateHeader(header), Some(body)))
                  case Throw(exception) => throw exception
                }
              } else {
                Future(RepPacket(StateHeader(header), None))
              }

            case Throw(exception) => throw exception
          }

        case OpCode.EXISTS =>
          ReplyHeader(buf) match {
            case Return((header, rem)) =>
              checkAssociation(reqRecord, header)

              if (header.err == 0) {
                ExistsResponse(rem) match {
                  case Return((body, rem2)) =>
                    Future(RepPacket(StateHeader(header), Some(body)))
                  case Throw(exception) => throw exception
                }
              } else {
                Future(RepPacket(StateHeader(header), None))
              }

            case Throw(exception) => throw exception
          }

        case OpCode.DELETE =>
          ReplyHeader(buf) match {
            case Return((header, rem)) =>
              checkAssociation(reqRecord, header)
              Future(RepPacket(StateHeader(header), None))

            case Throw(exception) => throw exception
          }

        case OpCode.SET_DATA =>
          ReplyHeader(buf) match {
            case Return((header, rem)) =>
              checkAssociation(reqRecord, header)

              if (header.err == 0) {
                SetDataResponse(rem) match {
                  case Return((body, rem2)) =>
                    Future(RepPacket(StateHeader(header), Some(body)))
                  case Throw(exception) => throw exception
                }
              } else {
                Future(RepPacket(StateHeader(header), None))
              }

            case Throw(exception) => throw exception
          }

        case OpCode.GET_DATA =>
          ReplyHeader(buf) match {
            case Return((header, rem)) =>
              checkAssociation(reqRecord, header)

              if (header.err == 0) {
                GetDataResponse(rem) match {
                  case Return((body, rem2)) =>
                    Future(RepPacket(StateHeader(header), Some(body)))
                  case Throw(exception) => throw exception
                }
              } else {
                Future(RepPacket(StateHeader(header), None))
              }

            case Throw(exception) => throw exception
          }

        case OpCode.SYNC =>
          ReplyHeader(buf) match {
            case Return((header, rem)) =>
              checkAssociation(reqRecord, header)

              if (header.err == 0) {
                SyncResponse(rem) match {
                  case Return((body, rem2)) =>
                    Future(RepPacket(StateHeader(header), Some(body)))
                  case Throw(exception) => throw exception
                }
              } else {
                Future(RepPacket(StateHeader(header), None))
              }

            case Throw(exception) => throw exception
          }

        case OpCode.SET_ACL =>
          ReplyHeader(buf) match {
            case Return((header, rem)) =>
              checkAssociation(reqRecord, header)

              if (header.err == 0) {
                SetACLResponse(rem) match {
                  case Return((body, rem2)) =>
                    Future(RepPacket(StateHeader(header), Some(body)))
                  case Throw(exception) => throw exception
                }
              } else {
                Future(RepPacket(StateHeader(header), None))
              }

            case Throw(exception) => throw exception
          }

        case OpCode.GET_ACL =>
          ReplyHeader(buf) match {
            case Return((header, rem)) =>
              checkAssociation(reqRecord, header)

              if (header.err == 0) {
                GetACLResponse(rem) match {
                  case Return((body, rem2)) =>
                    Future(RepPacket(StateHeader(header), Some(body)))
                  case Throw(exception) => throw exception
                }
              } else {
                Future(RepPacket(StateHeader(header), None))
              }

            case Throw(exception) => throw exception
          }

        case OpCode.GET_CHILDREN =>
          ReplyHeader(buf) match {
            case Return((header, rem)) =>
              checkAssociation(reqRecord, header)

              if (header.err == 0) {
                GetChildrenResponse(rem) match {
                  case Return((body, rem2)) =>
                    Future(RepPacket(StateHeader(header), Some(body)))
                  case Throw(exception) => throw exception
                }
              } else {
                Future(RepPacket(StateHeader(header), None))
              }

            case Throw(exception) => throw exception
          }

        case OpCode.GET_CHILDREN2 =>
          ReplyHeader(buf) match {
            case Return((header, rem)) =>
              checkAssociation(reqRecord, header)

              if (header.err == 0) {
                GetChildren2Response(rem) match {
                  case Return((body, rem2)) =>
                    Future(RepPacket(StateHeader(header), Some(body)))
                  case Throw(exception) => throw exception
                }
              } else {
                Future(RepPacket(StateHeader(header), None))
              }

            case Throw(exception) => throw exception
          }

        case OpCode.SET_WATCHES =>
          ReplyHeader(buf) match {
            case Return((header, rem)) =>
              checkAssociation(reqRecord, header)
              Future(RepPacket(StateHeader(header), None))

            case Throw(exception) => throw exception
          }

        case OpCode.MULTI =>
          ReplyHeader(buf) match {
            case Return((header, rem)) =>
              checkAssociation(reqRecord, header)
              // fixme maybe continue to read even if header does not eq 0

              if (header.err == 0) {
                TransactionResponse(rem) match {
                  case Return((body, rem2)) =>
                    Future(RepPacket(StateHeader(header), Some(body)))
                  case Throw(exception) => throw exception
                }
              } else {
                Future(RepPacket(StateHeader(header), None))
              }

            // TODO return partial result

            case Throw(exception) => throw exception
          }

        case _ => throw new RuntimeException("RequestRecord was not matched during response reading!")
      }
    }
  }

  class Writer(trans: Transport[Buf, Buf]) {
    def write[Req <: Request](packet: ReqPacket): Future[Unit] =
      if (!hasDispatcherFailed) {
        trans.write(packet.buf) transform {
          case Return(res) => Future(res)
          case Throw(exc) => exc match {
            case excpt: ChannelException =>
              failDispatcher(excpt)
              Future.exception(new CancelledRequestException(excpt))
            case excpt: WriteException =>
              failDispatcher(excpt)
              Future.exception(new CancelledRequestException(excpt))
            case exc: Exception => Future.exception(new CancelledRequestException(exc))
          }
        }
      } else {
        Future.exception(new CancelledRequestException)
      }
  }

  def failDispatcher(exc: Throwable) {
    // Stop ping
    sessionManager.get.session.pingScheduler.currentTask.get.cancel()
    // fail incoming requests
    hasDispatcherFailed = true
    // inform connection manager that the connection is no longer valid
    connectionManager.get.connection.get.isValid.set(false)
    // fail pending requests
    failPendingRequests(exc)
  }

  def failPendingRequests(exc: Throwable) {
    processedReq.dequeueAll(_ => true).map { record =>
      record._2.setException(new CancelledRequestException(exc))
    }
  }
}
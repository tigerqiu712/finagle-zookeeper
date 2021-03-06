package com.twitter.finagle.exp.zookeeper.client

import com.twitter.finagle.exp.zookeeper._
import com.twitter.finagle.dispatch.GenSerialClientDispatcher
import com.twitter.finagle.transport.Transport
import com.twitter.io.Buf
import com.twitter.util._

class ZkDispatcher(trans: Transport[Buf, Buf])
  extends GenSerialClientDispatcher[ReqPacket, RepPacket, Buf, Buf](trans) {
  val responseMatcher = new RepDispatcher(trans)

  protected def dispatch(req: ReqPacket, p: Promise[RepPacket]): Future[Unit] = {
    responseMatcher.write(req) respond { resp =>
      p.updateIfEmpty(resp)
    }
  }.unit
}
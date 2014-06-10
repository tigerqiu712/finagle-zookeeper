package com.twitter.finagle.exp.zookeeper.integration

import com.twitter.finagle.exp.zookeeper.ZookeeperDefs.CreateMode
import com.twitter.util.Await
import com.twitter.finagle.exp.zookeeper.watch.{zkState, eventType}
import com.twitter.finagle.exp.zookeeper.data.Ids
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import com.twitter.finagle.exp.zookeeper.NodeWithWatch

@RunWith(classOf[JUnitRunner])
class WatchTest extends IntegrationConfig {
  /* Configure your server here */
  val ipAddress: String = "127.0.0.1"
  val port: Int = 2181
  val timeOut: Long = 1000

  test("Server is up") {
    assert(isPortAvailable === false)
  }

  test("Create, exists with watches , SetData") {
    newClient()
    connect()

    val res = for {
      _ <- client.get.create("/zookeeper/test", "HELLO".getBytes, Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL)
      exist <- client.get.exists("/zookeeper/test", watch = true)
      _ <- client.get.setData("/zookeeper/test", "CHANGE IS GOOD1".getBytes, -1)
    } yield exist


    val ret = Await.result(res).asInstanceOf[NodeWithWatch]

    ret.watch.get onSuccess { rep =>
      assert(rep.typ === eventType.NODE_DATA_CHANGED)
      assert(rep.state === zkState.SYNC_CONNECTED)
      assert(rep.path === "/zookeeper/test")
    }
    Await.ready(ret.watch.get)

    disconnect()
    Await.ready(client.get.closeService)()
  }

  test("Create, getData with watches , SetData") {
    newClient()
    connect()

    val res = for {
      _ <- client.get.create("/zookeeper/test", "HELLO".getBytes, Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL)
      getdata <- client.get.getData("/zookeeper/test", watch = true)
      _ <- client.get.setData("/zookeeper/test", "CHANGE IS GOOD1".getBytes, -1)
    } yield getdata


    val ret = Await.result(res)

    ret.watch.get onSuccess { rep =>
      assert(rep.typ === eventType.NODE_DATA_CHANGED)
      assert(rep.state === zkState.SYNC_CONNECTED)
      assert(rep.path === "/zookeeper/test")
    }

    disconnect()
    Await.ready(client.get.closeService)()
  }

  test("Create, getChildren with watches , delete child") {
    newClient()
    connect()

    val res = for {
      _ <- client.get.create("/zookeeper/test", "HELLO".getBytes, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT)
      _ <- client.get.create("/zookeeper/test/hello", "HELLO".getBytes, Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL)
      getchild <- client.get.getChildren("/zookeeper/test", watch = true)
      _ <- client.get.delete("/zookeeper/test/hello", -1)
      _ <- client.get.delete("/zookeeper/test", -1)
    } yield getchild


    val ret = Await.result(res)

    ret.watch.get onSuccess { rep =>
      assert(rep.typ === eventType.NODE_CHILDREN_CHANGED)
      assert(rep.state === zkState.SYNC_CONNECTED)
      assert(rep.path === "/zookeeper/test")
    }

    disconnect()
    Await.ready(client.get.closeService)()
  }

  test("Create, getChildren2 with watches , delete child") {
    newClient()
    connect()

    val res = for {
      _ <- client.get.create("/zookeeper/test", "HELLO".getBytes, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT)
      _ <- client.get.create("/zookeeper/test/hello", "HELLO".getBytes, Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL)
      getchild <- client.get.getChildren2("/zookeeper/test", watch = true)
      _ <- client.get.delete("/zookeeper/test/hello", -1)
      _ <- client.get.delete("/zookeeper/test", -1)
    } yield getchild


    val ret = Await.result(res)

    ret.watch.get onSuccess { rep =>
      assert(rep.typ === eventType.NODE_CHILDREN_CHANGED)
      assert(rep.state === zkState.SYNC_CONNECTED)
      assert(rep.path === "/zookeeper/test")
    }

    disconnect()
    Await.ready(client.get.closeService)()
  }

  test("Create, getChildren(watch on parent) exists(watch on child) , delete child") {
    newClient()
    connect()

    val res = for {
      _ <- client.get.create("/zookeeper/test", "HELLO".getBytes, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT)
      _ <- client.get.create("/zookeeper/test/hello", "HELLO".getBytes, Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL)
      _ <- client.get.create("/zookeeper/test/hella", "HELLO".getBytes, Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL)
      getchild <- client.get.getChildren("/zookeeper/test", watch = true)
      exist <- client.get.exists("/zookeeper/test/hello", watch = true)
      getdata <- client.get.getData("/zookeeper/test/hella", watch = true)
      _ <- client.get.delete("/zookeeper/test/hello", -1)
      getchild <- client.get.getChildren("/zookeeper/test", watch = true)
      _ <- client.get.delete("/zookeeper/test/hella", -1)
      _ <- client.get.delete("/zookeeper/test", -1)
    } yield (getchild, exist)


    val ret = Await.result(res)

    ret._2.asInstanceOf[NodeWithWatch].watch.get onSuccess { rep =>
      assert(rep.typ === eventType.NODE_DELETED)
      assert(rep.state === zkState.SYNC_CONNECTED)
      assert(rep.path === "/zookeeper/test/hello")
    }

    ret._1.watch.get onSuccess { rep =>
      assert(rep.typ === eventType.NODE_CHILDREN_CHANGED)
      assert(rep.state === zkState.SYNC_CONNECTED)
      assert(rep.path === "/zookeeper/test")
    }

    disconnect()
    Await.ready(client.get.closeService)()
  }


}
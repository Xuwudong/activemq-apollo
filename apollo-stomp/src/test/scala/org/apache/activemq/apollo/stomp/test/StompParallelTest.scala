/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.apollo.stomp.test

import java.util.concurrent.TimeUnit._
import java.nio.channels.DatagramChannel
import org.fusesource.hawtbuf.AsciiBuffer
import org.apache.activemq.apollo.broker._
import java.net.{SocketTimeoutException, InetSocketAddress}
import org.apache.activemq.apollo.stomp.{Stomp, StompProtocolHandler}

/**
 * These tests can be run in parallel against a single Apollo broker.
 */
class StompParallelTest extends StompTestSupport with BrokerParallelTestExecution {

  def skip_if_using_store = skip(broker_config_uri.endsWith("-bdb.xml") || broker_config_uri.endsWith("-leveldb.xml"))

  test("Stomp 1.0 CONNECT") {
    connect("1.0")
  }

  test("Stomp 1.1 CONNECT") {
    connect("1.1")
  }

  test("Stomp 1.1 CONNECT /w STOMP Action") {

    client.open("localhost", port)

    client.write(
      "STOMP\n" +
              "accept-version:1.0,1.1\n" +
              "host:localhost\n" +
              "\n")
    val frame = client.receive()
    frame should startWith("CONNECTED\n")
    frame should include regex ("""session:.+?\n""")
    frame should include("version:1.1\n")
  }

  test("Stomp 1.1 CONNECT /w valid version fallback") {

    client.open("localhost", port)

    client.write(
      "CONNECT\n" +
              "accept-version:1.0,10.0\n" +
              "host:localhost\n" +
              "\n")
    val frame = client.receive()
    frame should startWith("CONNECTED\n")
    frame should include regex ("""session:.+?\n""")
    frame should include("version:1.0\n")
  }

  test("Stomp 1.1 CONNECT /w invalid version fallback") {

    client.open("localhost", port)

    client.write(
      "CONNECT\n" +
              "accept-version:9.0,10.0\n" +
              "host:localhost\n" +
              "\n")
    val frame = client.receive()
    frame should startWith("ERROR\n")
    frame should include regex ("""version:.+?\n""")
    frame should include regex ("""message:.+?\n""")
  }

  test("Stomp CONNECT /w invalid virtual host") {

    client.open("localhost", port)

    client.write(
      "CONNECT\n" +
              "accept-version:1.0,1.1\n" +
              "host:invalid\n" +
              "\n")
    val frame = client.receive()
    frame should startWith("ERROR\n")
    frame should include regex ("""message:.+?\n""")
  }

  test("Stomp 1.1 Broker sends heart-beat") {

    client.open("localhost", port)

    client.write(
      "CONNECT\n" +
              "accept-version:1.1\n" +
              "host:localhost\n" +
              "heart-beat:0,1000\n" +
              "\n")
    val frame = client.receive()
    frame should startWith("CONNECTED\n")
    frame should include regex ("""heart-beat:.+?\n""")

    def heart_beat_after(time: Long) {
      var start = System.currentTimeMillis
      val c = client.in.read()
      c should be === (Stomp.NEWLINE)
      var end = System.currentTimeMillis
      (end - start) should be >= time
    }
    client.in.read()
    heart_beat_after(900)
    heart_beat_after(900)
  }


  test("Stomp 1.1 Broker times out idle connection") {
    StompProtocolHandler.inbound_heartbeat = 1000L
    try {

      client.open("localhost", port)

      client.write(
        "CONNECT\n" +
                "accept-version:1.1\n" +
                "host:localhost\n" +
                "heart-beat:1000,0\n" +
                "\n")

      var frame = client.receive()
      frame should startWith("CONNECTED\n")
      frame should include regex ("""heart-beat:.+?\n""")

      var start = System.currentTimeMillis

      frame = client.receive()
      frame should startWith("ERROR\n")
      frame should include regex ("""message:.+?\n""")

      var end = System.currentTimeMillis
      (end - start) should be >= 1000L

    } finally {
      StompProtocolHandler.inbound_heartbeat = StompProtocolHandler.DEFAULT_INBOUND_HEARTBEAT
    }
  }

  test("UDP to STOMP interop") {

    connect("1.1")
    subscribe("0", "/topic/udp")

    val udp_port: Int = connector_port("udp").get
    val channel = DatagramChannel.open();

    val target = new InetSocketAddress("127.0.0.1", udp_port)
    channel.send(new AsciiBuffer("Hello").toByteBuffer, target)

    assert_received("Hello")
  }

  /**
   * These disconnect tests assure that we don't drop message deliviers that are in flight
   * if a client disconnects before those deliveries are accepted by the target destination.
   */
  test("Messages delivery assured to a queued once a disconnect receipt is received") {

    // figure out at what point a quota'ed queue stops accepting more messages.
    connect("1.1")
    client.socket.setSoTimeout(1 * 1000)
    var block_count = 0
    try {
      while (true) {
        sync_send("/queue/quota.assured1", "%01024d".format(block_count))
        block_count += 1
      }
    } catch {
      case e: SocketTimeoutException =>
    }
    close()

    // Send 5 more messages which do not fit in the queue, they will be
    // held in the producer connection's delivery session buffer..
    connect("1.1")
    for (i <- 0 until (block_count + 5)) {
      async_send("/queue/quota.assured2", "%01024d".format(i))
    }

    // Even though we disconnect, those 5 that did not fit should still
    // get delivered once the queue unblocks..
    disconnect()

    // Lets make sure non of the messages were dropped.
    connect("1.1")
    subscribe("0", "/queue/quota.assured2")
    for (i <- 0 until (block_count + 5)) {
      assert_received("%01024d".format(i))
    }

  }

  test("Messages delivery assured to a topic once a disconnect receipt is received") {

    //setup a subscription which will block quickly..
    var consumer = new StompClient
    connect("1.1", consumer)
    subscribe("0", "/topic/quota.assured1", "client", headers = "credit:1,0\n", c = consumer)

    // figure out at what point a quota'ed consumer stops accepting more messages.
    connect("1.1")
    client.socket.setSoTimeout(1 * 1000)
    var block_count = 0
    try {
      while (true) {
        sync_send("/topic/quota.assured1", "%01024d".format(block_count))
        block_count += 1
      }
    } catch {
      case e: SocketTimeoutException =>
    }
    close()
    close(consumer)

    connect("1.1", consumer)
    subscribe("0", "/topic/quota.assured2", "client", headers = "credit:1,0\n", c = consumer)

    // Send 5 more messages which do not fit in the consumer buffer, they will be
    // held in the producer connection's delivery session buffer..
    connect("1.1")
    for (i <- 0 until (block_count + 5)) {
      async_send("/topic/quota.assured2", "%01024d".format(i))
    }

    // Even though we disconnect, those 5 that did not fit should still
    // get delivered once the queue unblocks..
    disconnect()

    // Lets make sure non of the messages were dropped.
    for (i <- 0 until (block_count + 5)) {
      assert_received("%01024d".format(i), c = consumer)(true)
    }

  }

  test("APLO-206 - Load balance of job queues using small consumer credit windows") {
    connect("1.1")

    for (i <- 1 to 4) {
      async_send("/queue/load-balanced2", i)
    }

    subscribe("1", "/queue/load-balanced2", "client", false, "credit:1,0\n")
    val ack1 = assert_received(1, "1")

    subscribe("2", "/queue/load-balanced2", "client", false, "credit:1,0\n")
    val ack2 = assert_received(2, "2")

    // Ok lets ack now..
    ack1(true)
    val ack3 = assert_received(3, "1")

    ack2(true)
    val ack4 = assert_received(4, "2")
  }

  test("Browsing queues does not cause AssertionError.  Reported in APLO-156") {
    skip_if_using_store
    connect("1.1")
    subscribe("0", "/queue/TOOL.DEFAULT")
    async_send("/queue/TOOL.DEFAULT", "1")
    async_send("/queue/TOOL.DEFAULT", "2")
    assert_received("1", "0")
    assert_received("2", "0")
    subscribe("1", "/queue/TOOL.DEFAULT", "auto", false, "browser:true\n")
    val frame = client.receive()
    frame should startWith(
      "MESSAGE\n" +
              "subscription:1\n" +
              "destination:\n" +
              "message-id:\n" +
              "browser:end")
  }

  test("retain:set makes a topic remeber the message") {
    connect("1.1")
    async_send("/topic/retained-example", 1)
    async_send("/topic/retained-example", 2, "retain:set\n")
    sync_send("/topic/retained-example", 3)
    subscribe("0", "/topic/retained-example")
    assert_received(2)
    async_send("/topic/retained-example", 4)
    assert_received(4)
  }

  test("retain:remove makes a topic forget the message") {
    connect("1.1")
    async_send("/topic/retained-example2", 1)
    async_send("/topic/retained-example2", 2, "retain:set\n")
    async_send("/topic/retained-example2", 3, "retain:remove\n")
    subscribe("0", "/topic/retained-example2")
    async_send("/topic/retained-example2", 4)
    assert_received(4)
  }

  test("Setting `from-seq` header to -1 results in subscription starting at end of the queue.") {
    skip_if_using_store
    connect("1.1")

    def send(id: Int) = {
      client.write(
        "SEND\n" +
                "destination:/queue/from-seq-end\n" +
                "receipt:0\n" +
                "\n" +
                "message:" + id + "\n")
      wait_for_receipt("0")
    }

    send(1)
    send(2)
    send(3)

    client.write(
      "SUBSCRIBE\n" +
              "destination:/queue/from-seq-end\n" +
              "receipt:0\n" +
              "browser:true\n" +
              "browser-end:false\n" +
              "id:0\n" +
              "from-seq:-1\n" +
              "\n")
    wait_for_receipt("0")

    send(4)

    def get(seq: Long) = {
      val frame = client.receive()
      frame should startWith("MESSAGE\n")
      frame should include("message:" + seq + "\n")
    }
    get(4)
  }

  test("The `browser-end:false` can be used to continously browse a queue.") {
    connect("1.1")
    client.write(
      "SUBSCRIBE\n" +
              "destination:/queue/browsing-continous\n" +
              "browser:true\n" +
              "browser-end:false\n" +
              "receipt:0\n" +
              "id:0\n" +
              "\n")
    wait_for_receipt("0")

    def send(id: Int) = client.write(
      "SEND\n" +
              "destination:/queue/browsing-continous\n" +
              "\n" +
              "message:" + id + "\n")

    send(1)
    send(2)

    def get(seq: Long) = {
      val frame = client.receive()
      frame should startWith("MESSAGE\n")
      expect(true)(frame.contains("message:" + seq + "\n"))
    }
    get(1)
    get(2)
  }

  test("Message sequence headers are added when `include-seq` is used.") {
    connect("1.1")
    client.write(
      "SUBSCRIBE\n" +
              "destination:/queue/seq_queue\n" +
              "receipt:0\n" +
              "id:0\n" +
              "include-seq:seq\n" +
              "\n")
    wait_for_receipt("0")

    def send(id: Int) = client.write(
      "SEND\n" +
              "destination:/queue/seq_queue\n" +
              "\n" +
              "message:" + id + "\n")

    send(1)
    send(2)

    def get(seq: Long) = {
      val frame = client.receive()
      frame should startWith("MESSAGE\n")
      expect(true)(frame.contains("seq:" + seq + "\n"))
    }
    get(1)
    get(2)
  }

  test("The `from-seq` header can be used to resume delivery from a given point in a queue.") {
    skip_if_using_store
    connect("1.1")

    def send(id: Int) = {
      client.write(
        "SEND\n" +
                "destination:/queue/from_queue\n" +
                "receipt:0\n" +
                "\n" +
                "message:" + id + "\n")
      wait_for_receipt("0")
    }

    send(1)
    send(2)
    send(3)

    client.write(
      "SUBSCRIBE\n" +
              "destination:/queue/from_queue\n" +
              "receipt:0\n" +
              "browser:true\n" +
              "id:0\n" +
              "include-seq:seq\n" +
              "from-seq:2\n" +
              "\n")
    wait_for_receipt("0")

    def get(seq: Long) = {
      val frame = client.receive()
      frame should startWith("MESSAGE\n")
      frame should include("seq:" + seq + "\n")
    }
    get(2)
    get(3)
  }


  test("The `from-seq` header is not supported with wildcard or composite destinations.") {
    connect("1.1")

    client.write(
      "SUBSCRIBE\n" +
              "destination:/queue/some,/queue/other\n" +
              "browser:true\n" +
              "id:0\n" +
              "include-seq:seq\n" +
              "from-seq:2\n" +
              "\n")

    var frame = client.receive()
    frame should startWith("ERROR\n")
    frame should include("message:The from-seq header is only supported when you subscribe to one destination")

    client.close
    connect("1.1")

    client.write(
      "SUBSCRIBE\n" +
              "destination:/queue/some.*\n" +
              "browser:true\n" +
              "id:0\n" +
              "include-seq:seq\n" +
              "from-seq:2\n" +
              "\n")

    frame = client.receive()
    frame should startWith("ERROR\n")
    frame should include("message:The from-seq header is only supported when you subscribe to one destination")
  }

  test("Selector Syntax") {
    connect("1.1")

    var sub_id = 0;
    def test_selector(selector: String, headers: List[String], expected_matches: List[Int]) = {

      client.write(
        "SUBSCRIBE\n" +
                "destination:/topic/selected\n" +
                "selector:" + selector + "\n" +
                "receipt:0\n" +
                "id:" + sub_id + "\n" +
                "\n")
      wait_for_receipt("0")

      var id = 1;

      headers.foreach {
        header =>
          client.write(
            "SEND\n" +
                    "destination:/topic/selected\n" +
                    header + "\n" +
                    "\n" +
                    "message:" + id + "\n")
          id += 1;
      }

      expected_matches.foreach {
        id =>
          val frame = client.receive()
          frame should startWith("MESSAGE\n")
          frame should endWith regex ("\n\nmessage:" + id + "\n")
      }

      client.write(
        "UNSUBSCRIBE\n" +
                "id:" + sub_id + "\n" +
                "receipt:0\n" +
                "\n")

      wait_for_receipt("0")

      sub_id += 1
    }

    test_selector("color = 'red'", List("color:blue", "not:set", "color:red"), List(3))
    test_selector("hyphen-field = 'red'", List("hyphen-field:blue", "not:set", "hyphen-field:red"), List(3))
    test_selector("age >= 21", List("age:3", "not:set", "age:21", "age:30"), List(3, 4))

  }

  test("Queues load balance across subscribers") {
    connect("1.1")
    subscribe("1", "/queue/load-balanced")
    subscribe("2", "/queue/load-balanced")

    for (i <- 0 until 4) {
      async_send("/queue/load-balanced", "message:" + i)
    }

    var sub1_counter = 0
    var sub2_counter = 0

    def get() = {
      val frame = client.receive()
      frame should startWith("MESSAGE\n")

      if (frame.contains("subscription:1\n")) {
        sub1_counter += 1
      } else if (frame.contains("subscription:2\n")) {
        sub2_counter += 1
      }
    }

    for (i <- 0 until 4) {
      get()
    }

    sub1_counter should be(2)
    sub2_counter should be(2)

  }

  test("Queues do NOT load balance across exclusive subscribers") {
    connect("1.1")

    // Connect to subscribers
    client.write(
      "SUBSCRIBE\n" +
              "destination:/queue/exclusive\n" +
              "id:1\n" +
              "\n")

    client.write(
      "SUBSCRIBE\n" +
              "destination:/queue/exclusive\n" +
              "exclusive:true\n" +
              "receipt:0\n" +
              "ack:client\n" +
              "id:2\n" +
              "\n")

    wait_for_receipt("0")

    def put(id: Int) = {
      client.write(
        "SEND\n" +
                "destination:/queue/exclusive\n" +
                "\n" +
                "message:" + id + "\n")
    }

    for (i <- 0 until 4) {
      put(i)
    }

    var sub1_counter = 0
    var sub2_counter = 0

    def get() = {
      val frame = client.receive()
      frame should startWith("MESSAGE\n")

      if (frame.contains("subscription:1\n")) {
        sub1_counter += 1
      } else if (frame.contains("subscription:2\n")) {
        sub2_counter += 1
      }
    }

    for (i <- 0 until 4) {
      get()
    }

    sub1_counter should be(0)
    sub2_counter should be(4)

    // disconnect the exclusive subscriber.
    client.write(
      "UNSUBSCRIBE\n" +
              "id:2\n" +
              "\n")

    // sub 1 should now get all the messages.
    for (i <- 0 until 4) {
      get()
    }
    sub1_counter should be(4)

  }

  test("Queue browsers don't consume the messages") {
    skip_if_using_store
    connect("1.1")

    def put(id: Int) = {
      client.write(
        "SEND\n" +
                "destination:/queue/browsing\n" +
                "receipt:0\n" +
                "\n" +
                "message:" + id + "\n")
      wait_for_receipt("0")
    }

    put(1)
    put(2)
    put(3)

    // create a browser subscription.
    client.write(
      "SUBSCRIBE\n" +
              "destination:/queue/browsing\n" +
              "browser:true\n" +
              "id:0\n" +
              "\n")

    def get(sub: Int, id: Int) = {
      val frame = client.receive()
      frame should startWith("MESSAGE\n")
      frame should include("subscription:%d\n".format(sub))
      frame should endWith regex ("\n\nmessage:%d\n".format(id))
    }
    get(0, 1)
    get(0, 2)
    get(0, 3)

    // Should get a browse end message
    val frame = client.receive()
    frame should startWith("MESSAGE\n")
    frame should include("subscription:0\n")
    frame should include("browser:end\n")
    frame should include("\nmessage-id:")
    frame should include("\ndestination:")

    // create a regular subscription.
    client.write(
      "SUBSCRIBE\n" +
              "destination:/queue/browsing\n" +
              "id:1\n" +
              "\n")

    get(1, 1)
    get(1, 2)
    get(1, 3)

  }

  test("Queue order preserved") {
    connect("1.1")

    def put(id: Int) = {
      client.write(
        "SEND\n" +
                "destination:/queue/example\n" +
                "\n" +
                "message:" + id + "\n")
    }
    put(1)
    put(2)
    put(3)

    client.write(
      "SUBSCRIBE\n" +
              "destination:/queue/example\n" +
              "id:0\n" +
              "\n")

    def get(id: Int) = {
      val frame = client.receive()
      frame should startWith("MESSAGE\n")
      frame should include("subscription:0\n")
      frame should endWith regex ("\n\nmessage:" + id + "\n")
    }
    get(1)
    get(2)
    get(3)
  }

  test("Topic drops messages sent before before subscription is established") {
    connect("1.1")

    def put(id: Int) = {
      client.write(
        "SEND\n" +
                "destination:/topic/updates1\n" +
                "\n" +
                "message:" + id + "\n")
    }
    put(1)

    client.write(
      "SUBSCRIBE\n" +
              "destination:/topic/updates1\n" +
              "id:0\n" +
              "receipt:0\n" +
              "\n")
    wait_for_receipt("0")

    put(2)
    put(3)

    def get(id: Int) = {
      val frame = client.receive()
      frame should startWith("MESSAGE\n")
      frame should include("subscription:0\n")
      frame should endWith regex ("\n\nmessage:" + id + "\n")
    }

    // note that the put(1) message gets dropped.
    get(2)
    get(3)
  }

  test("Topic /w Durable sub retains messages.") {
    connect("1.1")

    def put(id: Int) = {
      client.write(
        "SEND\n" +
                "destination:/topic/updates2\n" +
                "\n" +
                "message:" + id + "\n")
    }

    client.write(
      "SUBSCRIBE\n" +
              "destination:/topic/updates2\n" +
              "id:my-sub-name\n" +
              "persistent:true\n" +
              "include-seq:seq\n" +
              "receipt:0\n" +
              "\n")
    wait_for_receipt("0")
    client.close

    // Close him out.. since persistent:true then
    // the topic subscription will be persistent accross client
    // connections.

    connect("1.1")
    put(1)
    put(2)
    put(3)

    client.write(
      "SUBSCRIBE\n" +
              "destination:/topic/updates2\n" +
              "id:my-sub-name\n" +
              "persistent:true\n" +
              "include-seq:seq\n" +
              "\n")

    def get(id: Int) = {
      val frame = client.receive()
      frame should startWith("MESSAGE\n")
      frame should include("subscription:my-sub-name\n")
      frame should endWith regex ("\n\nmessage:" + id + "\n")
    }

    get(1)
    get(2)
    get(3)
  }

  test("Queue and a selector") {
    connect("1.1")

    def put(id: Int, color: String) = {
      client.write(
        "SEND\n" +
                "destination:/queue/selected\n" +
                "color:" + color + "\n" +
                "\n" +
                "message:" + id + "\n")
    }
    put(1, "red")
    put(2, "blue")
    put(3, "red")

    client.write(
      "SUBSCRIBE\n" +
              "destination:/queue/selected\n" +
              "selector:color='red'\n" +
              "id:0\n" +
              "\n")

    def get(id: Int) = {
      val frame = client.receive()
      frame should startWith("MESSAGE\n")
      frame should endWith regex ("\n\nmessage:" + id + "\n")
    }
    get(1)
    get(3)
  }

  test("Topic and a selector") {
    connect("1.1")

    def put(id: Int, color: String) = {
      client.write(
        "SEND\n" +
                "destination:/topic/selected\n" +
                "color:" + color + "\n" +
                "\n" +
                "message:" + id + "\n")
    }

    client.write(
      "SUBSCRIBE\n" +
              "destination:/topic/selected\n" +
              "selector:color='red'\n" +
              "id:0\n" +
              "receipt:0\n" +
              "\n")
    wait_for_receipt("0")

    put(1, "red")
    put(2, "blue")
    put(3, "red")

    def get(id: Int) = {
      val frame = client.receive()
      frame should startWith("MESSAGE\n")
      frame should endWith regex ("\n\nmessage:" + id + "\n")
    }
    get(1)
    get(3)
  }

  test("Topic gets copy of message sent to queue") {
    connect("1.1")
    subscribe("1", "/topic/mirrored.a")
    async_send("/queue/mirrored.a", "message:1\n")
    assert_received("message:1\n")
  }

  test("Queue gets copy of message sent to topic") {
    connect("1.1")

    // Connect to subscribers
    client.write(
      "SUBSCRIBE\n" +
              "destination:/queue/mirrored.b\n" +
              "id:1\n" +
              "receipt:0\n" +
              "\n")
    wait_for_receipt("0")

    def put(id: Int) = {
      client.write(
        "SEND\n" +
                "destination:/topic/mirrored.b\n" +
                "\n" +
                "message:" + id + "\n")
    }

    put(1)

    def get(id: Int) = {
      val frame = client.receive()
      frame should startWith("MESSAGE\n")
      frame should endWith regex ("\n\nmessage:" + id + "\n")
    }
    get(1)

  }

  test("Queue does not get copies from topic until it's first created") {
    connect("1.1")

    def put(id: Int) = {
      client.write(
        "SEND\n" +
                "destination:/topic/mirrored.c\n" +
                "\n" +
                "message:" + id + "\n")
    }

    put(1)

    // Connect to subscribers
    client.write(
      "SUBSCRIBE\n" +
              "destination:/queue/mirrored.c\n" +
              "id:1\n" +
              "receipt:0\n" +
              "\n")
    wait_for_receipt("0")

    put(2)

    def get(id: Int) = {
      val frame = client.receive()
      frame should startWith("MESSAGE\n")
      frame should endWith regex ("\n\nmessage:" + id + "\n")
    }
    get(2)
  }

  def path_separator = "."

  test("Messages Expire") {
    connect("1.1")

    def put(msg: String, ttl: Option[Long] = None) = {
      val expires_header = ttl.map(t => "expires:" + (System.currentTimeMillis() + t) + "\n").getOrElse("")
      client.write(
        "SEND\n" +
                expires_header +
                "destination:/queue/exp\n" +
                "\n" +
                "message:" + msg + "\n")
    }

    put("1")
    put("2", Some(1000L))
    put("3")

    Thread.sleep(2000)

    client.write(
      "SUBSCRIBE\n" +
              "destination:/queue/exp\n" +
              "id:1\n" +
              "receipt:0\n" +
              "\n")
    wait_for_receipt("0")


    def get(dest: String) = {
      val frame = client.receive()
      frame should startWith("MESSAGE\n")
      frame should endWith("\n\nmessage:%s\n".format(dest))
    }

    get("1")
    get("3")
  }

  test("Receipts on SEND to unconsummed topic") {
    connect("1.1")

    def put(id: Int) = {
      client.write(
        "SEND\n" +
                "destination:/topic/receipt-test\n" +
                "receipt:" + id + "\n" +
                "\n" +
                "message:" + id + "\n")
    }

    put(1)
    put(2)
    wait_for_receipt("1")
    wait_for_receipt("2")


  }

  test("Receipts on SEND to a consumed topic") {
    connect("1.1")

    def put(id: Int) = {
      client.write(
        "SEND\n" +
                "destination:/topic/receipt-test\n" +
                "receipt:" + id + "\n" +
                "\n" +
                "message:" + id + "\n")
    }

    // start a consumer on a different connection
    var consumer = new StompClient
    connect("1.1", consumer)
    consumer.write(
      "SUBSCRIBE\n" +
              "destination:/topic/receipt-test\n" +
              "id:0\n" +
              "receipt:0\n" +
              "\n")
    wait_for_receipt("0", consumer)

    put(1)
    put(2)
    wait_for_receipt("1")
    wait_for_receipt("2")

  }

  test("Transacted commit after unsubscribe") {
    val producer = new StompClient
    val consumer = new StompClient

    connect("1.1", producer)
    connect("1.1", consumer)

    // subscribe the consumer
    subscribe("0", "/queue/test", "client-individual", false, "", true, consumer)

    // begin the transaction on the consumer
    consumer.write(
      "BEGIN\n" +
              "transaction:x\n" +
              "\n")

    sync_send("/queue/test", "Hello world", "", producer)

    val ack = assert_received("Hello world", "0", consumer, "x")
    ack(true)

    unsubscribe("0", "", consumer)

    consumer.write(
      "COMMIT\n" +
              "transaction:x\n" +
              "\n")

    sync_send("/queue/test", "END", "", producer)
    subscribe("1", "/queue/test", c = producer)
    assert_received("END", "1", producer)
    // since we committed the transaction AFTER un-subscribing, there should be nothing in
    // the queue

  }

  test("Queue and a transacted send") {
    connect("1.1")

    def put(id: Int, tx: String = null) = {
      client.write(
        "SEND\n" +
                "destination:/queue/transacted\n" + {
          if (tx != null) {
            "transaction:" + tx + "\n"
          } else {
            ""
          }
        } +
                "\n" +
                "message:" + id + "\n")
    }

    put(1)
    client.write(
      "BEGIN\n" +
              "transaction:x\n" +
              "\n")
    put(2, "x")
    put(3)

    client.write(
      "SUBSCRIBE\n" +
              "destination:/queue/transacted\n" +
              "id:0\n" +
              "\n")

    def get(id: Int) = {
      val frame = client.receive()
      frame should startWith("MESSAGE\n")
      frame should endWith regex ("\n\nmessage:" + id + "\n")
    }
    get(1)
    get(3)

    client.write(
      "COMMIT\n" +
              "transaction:x\n" +
              "\n")

    get(2)

  }

  test("Topic and a transacted send") {
    connect("1.1")

    def put(id: Int, tx: String = null) = {
      client.write(
        "SEND\n" +
                "destination:/topic/transacted\n" + {
          if (tx != null) {
            "transaction:" + tx + "\n"
          } else {
            ""
          }
        } +
                "\n" +
                "message:" + id + "\n")
    }

    client.write(
      "SUBSCRIBE\n" +
              "destination:/topic/transacted\n" +
              "id:0\n" +
              "receipt:0\n" +
              "\n")
    wait_for_receipt("0")

    put(1)
    client.write(
      "BEGIN\n" +
              "transaction:x\n" +
              "\n")
    put(2, "x")
    put(3)

    def get(id: Int) = {
      val frame = client.receive()
      frame should startWith("MESSAGE\n")
      frame should endWith regex ("\n\nmessage:" + id + "\n")
    }

    get(1)
    get(3)

    client.write(
      "COMMIT\n" +
              "transaction:x\n" +
              "\n")

    get(2)

  }

  test("ack:client redelivers on client disconnect") {
    connect("1.1")

    def put(id: Int) = {
      client.write(
        "SEND\n" +
                "destination:/queue/ackmode-client\n" +
                "\n" +
                "message:" + id + "\n")
    }
    put(1)
    put(2)
    put(3)

    client.write(
      "SUBSCRIBE\n" +
              "destination:/queue/ackmode-client\n" +
              "ack:client\n" +
              "id:0\n" +
              "\n")

    def get(id: Int) = {
      val frame = client.receive()
      frame should startWith("MESSAGE\n")
      frame should include("subscription:0\n")
      frame should include regex ("message-id:.+?\n")
      frame should endWith regex ("\n\nmessage:" + id + "\n")

      val p = """(?s).*?\nmessage-id:(.+?)\n.*""".r
      frame match {
        case p(x) => x
        case _ => null
      }
    }

    get(1)
    val mid = get(2)
    get(3)

    // Ack the first 2 messages..
    client.write(
      "ACK\n" +
              "subscription:0\n" +
              "message-id:" + mid + "\n" +
              "receipt:0\n" +
              "\n")

    wait_for_receipt("0")
    client.close

    connect("1.1")

    client.write(
      "SUBSCRIBE\n" +
              "destination:/queue/ackmode-client\n" +
              "ack:client\n" +
              "id:0\n" +
              "\n")
    get(3)


  }


  test("ack:client-individual redelivers on client disconnect") {
    connect("1.1")

    def put(id: Int) = {
      client.write(
        "SEND\n" +
                "destination:/queue/ackmode-message\n" +
                "\n" +
                "message:" + id + "\n")
    }
    put(1)
    put(2)
    put(3)

    client.write(
      "SUBSCRIBE\n" +
              "destination:/queue/ackmode-message\n" +
              "ack:client-individual\n" +
              "id:0\n" +
              "\n")

    def get(id: Int) = {
      val frame = client.receive()
      frame should startWith("MESSAGE\n")
      frame should include("subscription:0\n")
      frame should include regex ("message-id:.+?\n")
      frame should endWith regex ("\n\nmessage:" + id + "\n")

      val p = """(?s).*?\nmessage-id:(.+?)\n.*""".r
      frame match {
        case p(x) => x
        case _ => null
      }
    }

    get(1)
    val mid = get(2)
    get(3)

    // Ack the first 2 messages..
    client.write(
      "ACK\n" +
              "subscription:0\n" +
              "message-id:" + mid + "\n" +
              "receipt:0\n" +
              "\n")

    wait_for_receipt("0")
    client.close

    connect("1.1")

    client.write(
      "SUBSCRIBE\n" +
              "destination:/queue/ackmode-message\n" +
              "ack:client-individual\n" +
              "id:0\n" +
              "\n")
    get(1)
    get(3)

  }

  test("Temp Queue Send Receive") {
    connect("1.1")

    def put(msg: String) = {
      client.write(
        "SEND\n" +
                "destination:/temp-queue/test\n" +
                "reply-to:/temp-queue/test\n" +
                "receipt:0\n" +
                "\n" +
                "message:" + msg + "\n")
      wait_for_receipt("0")
    }

    put("1")

    client.write(
      "SUBSCRIBE\n" +
              "destination:/temp-queue/test\n" +
              "id:1\n" +
              "\n")

    def get(dest: String) = {
      val frame = client.receive()
      frame should startWith("MESSAGE\n")
      frame should endWith("\n\nmessage:%s\n".format(dest))

      // extract headers as a map of values.
      Map((frame.split("\n").reverse.flatMap {
        line =>
          if (line.contains(":")) {
            val parts = line.split(":", 2)
            Some((parts(0), parts(1)))
          } else {
            None
          }
      }): _*)
    }

    // The destination and reply-to headers should get updated with actual
    // Queue names
    val message = get("1")
    val actual_temp_dest_name = message.get("destination").get
    actual_temp_dest_name should startWith("/queue/temp.default.")
    message.get("reply-to") should be === (message.get("destination"))

    // Different connection should be able to send a message to the temp destination..
    var other = new StompClient
    connect("1.1", other)
    other.write(
      "SEND\n" +
              "destination:" + actual_temp_dest_name + "\n" +
              "receipt:0\n" +
              "\n")
    wait_for_receipt("0", other)

    // First client chould get the message.
    var frame = client.receive()
    frame should startWith("MESSAGE\n")

    // But not consume from it.
    other.write(
      "SUBSCRIBE\n" +
              "destination:" + actual_temp_dest_name + "\n" +
              "id:1\n" +
              "receipt:0\n" +
              "\n")
    frame = other.receive()
    frame should startWith("ERROR\n")
    frame should include regex ("""message:Not authorized to receive from the temporary destination""")
    other.close()

    // Check that temp queue is deleted once the client disconnects
    put("2")
    expect(true)(queue_exists(actual_temp_dest_name.stripPrefix("/queue/")))
    client.close();

    within(10, SECONDS) {
      expect(false)(queue_exists(actual_temp_dest_name.stripPrefix("/queue/")))
    }
  }

  test("Temp Topic Send Receive") {
    connect("1.1")

    client.write(
      "SUBSCRIBE\n" +
              "destination:/temp-topic/test\n" +
              "id:1\n" +
              "\n")

    def get(dest: String) = {
      val frame = client.receive()
      frame should startWith("MESSAGE\n")
      frame should endWith("\n\nmessage:%s\n".format(dest))

      // extract headers as a map of values.
      Map((frame.split("\n").reverse.flatMap {
        line =>
          if (line.contains(":")) {
            val parts = line.split(":", 2)
            Some((parts(0), parts(1)))
          } else {
            None
          }
      }): _*)
    }

    def put(msg: String) = {
      client.write(
        "SEND\n" +
                "destination:/temp-topic/test\n" +
                "reply-to:/temp-topic/test\n" +
                "receipt:0\n" +
                "\n" +
                "message:" + msg + "\n")
      wait_for_receipt("0", client)
    }
    put("1")

    // The destination and reply-to headers should get updated with actual
    // Queue names
    val message = get("1")
    val actual_temp_dest_name = message.get("destination").get
    actual_temp_dest_name should startWith("/topic/temp.default.")
    message.get("reply-to") should be === (message.get("destination"))

    // Different connection should be able to send a message to the temp destination..
    var other = new StompClient
    connect("1.1", other)
    other.write(
      "SEND\n" +
              "destination:" + actual_temp_dest_name + "\n" +
              "receipt:0\n" +
              "\n")
    wait_for_receipt("0", other)

    // First client chould get the message.
    var frame = client.receive()
    frame should startWith("MESSAGE\n")

    // But not consume from it.
    other.write(
      "SUBSCRIBE\n" +
              "destination:" + actual_temp_dest_name + "\n" +
              "id:1\n" +
              "receipt:0\n" +
              "\n")
    frame = other.receive()
    frame should startWith("ERROR\n")
    frame should include regex ("""message:Not authorized to receive from the temporary destination""")
    other.close()

    // Check that temp queue is deleted once the client disconnects
    put("2")
    expect(true)(topic_exists(actual_temp_dest_name.stripPrefix("/topic/")))
    client.close();

    within(10, SECONDS) {
      expect(false)(topic_exists(actual_temp_dest_name.stripPrefix("/topic/")))
    }


  }

  test("Odd reply-to headers do not cause errors") {
    connect("1.1")

    client.write(
      "SEND\n" +
              "destination:/queue/oddrepyto\n" +
              "reply-to:sms:8139993334444\n" +
              "receipt:0\n" +
              "\n")
    wait_for_receipt("0")

    client.write(
      "SUBSCRIBE\n" +
              "destination:/queue/oddrepyto\n" +
              "id:1\n" +
              "\n")

    val frame = client.receive()
    frame should startWith("MESSAGE\n")
    frame should include("reply-to:sms:8139993334444\n")
  }

  test("NACKing moves messages to DLQ (non-persistent)") {
    connect("1.1")
    sync_send("/queue/nacker.a", "this msg is not persistent")

    subscribe("0", "/queue/nacker.a", "client", false, "", false)
    subscribe("dlq", "/queue/dlq.nacker.a", "auto", false, "", false)
    var ack = assert_received("this msg is not persistent", "0")
    ack(false)
    ack = assert_received("this msg is not persistent", "0")
    ack(false)

    // It should be sent to the DLQ after the 2nd nak
    assert_received("this msg is not persistent", "dlq")
  }

  test("NACKing moves messages to DLQ (persistent)") {
    connect("1.1")
    sync_send("/queue/nacker.b", "this msg is persistent", "persistent:true\n")

    subscribe("0", "/queue/nacker.b", "client", false, "", false)
    subscribe("dlq", "/queue/dlq.nacker.b", "auto", false, "", false)
    var ack = assert_received("this msg is persistent", "0")
    ack(false)
    ack = assert_received("this msg is persistent", "0")
    ack(false)

    // It should be sent to the DLQ after the 2nd nak
    assert_received("this msg is persistent", "dlq")
  }

  test("NACKing without DLQ consumer (persistent)") {
    connect("1.1")
    sync_send("/queue/nacker.c", "this msg is persistent", "persistent:true\n")

    subscribe("0", "/queue/nacker.c", "client", false, "", false)

    var ack = assert_received("this msg is persistent", "0")
    ack(false)
    ack = assert_received("this msg is persistent", "0")
    ack(false)
    Thread.sleep(1000)
  }


}
/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.apollo.test3;

import java.net.URI;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.apollo.broker.Broker;
import org.apache.activemq.apollo.broker.BrokerFactory;
import org.apache.activemq.transport.TransportFactory;

/**
 * @version $Revision: 1.3 $
 */
public class JmsTopicSendReceiveWithTwoConnectionsAndEmbeddedBrokerTest extends JmsTopicSendReceiveWithTwoConnectionsTest {

    protected Broker broker;
    protected String bindAddress = "tcp://localhost:61616";

    /**
     * Sets up a test where the producer and consumer have their own connection.
     * 
     * @see junit.framework.TestCase#setUp()
     */
    protected void setUp() throws Exception {
        if (broker == null) {
            broker = createBroker();
        }
        super.setUp();
    }

    protected void tearDown() throws Exception {
        super.tearDown();

        if (broker != null) {
            broker.stop();
        }
    }

    /**
     * Factory method to create a new broker
     * 
     * @throws Exception
     */
    protected Broker createBroker() throws Exception {
    	Broker answer = BrokerFactory.createBroker("jaxb:classpath:non-persistent-activemq.xml");
        configureBroker(answer);
        answer.start();
        return answer;
    }

    protected void configureBroker(Broker answer) throws Exception {
        answer.addTransportServer(TransportFactory.bind(new URI(bindAddress)));
    }

    protected ActiveMQConnectionFactory createConnectionFactory() throws Exception {
        return new ActiveMQConnectionFactory(bindAddress);
    }
}
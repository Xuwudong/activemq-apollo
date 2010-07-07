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
package org.apache.activemq.apollo.jaxb;

import java.util.ArrayList;
import java.util.List;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement(name="broker")
@XmlAccessorType(XmlAccessType.FIELD)
public class BrokerConfig {
	
	@XmlAttribute(name="id")
	public String id;

    /**
     * Used to track config revisions.
     */
    @XmlAttribute(name="rev")
    public int rev;

    @XmlElement(name="virtual-host")
    public List<VirtualHostConfig> virtualHosts = new ArrayList<VirtualHostConfig>();
    @XmlElement(name="transport-server")
    public List<String> transportServers = new ArrayList<String>();
    @XmlElement(name="connect-uri")
    public List<String> connectUris = new ArrayList<String>();


	public List<VirtualHostConfig> getVirtualHosts() {
		return virtualHosts;
	}
	public void setVirtualHosts(List<VirtualHostConfig> virtualHosts) {
		this.virtualHosts = virtualHosts;
	}

	public List<String> getTransportServers() {
		return transportServers;
	}
	public void setTransportServers(List<String> transportServers) {
		this.transportServers = transportServers;
	}


	public List<String> getConnectUris() {
		return connectUris;
	}
	public void setConnectUris(List<String> connectUris) {
		this.connectUris = connectUris;
	}

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }
}

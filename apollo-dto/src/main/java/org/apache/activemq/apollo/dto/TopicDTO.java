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
package org.apache.activemq.apollo.dto;



import javax.xml.bind.annotation.*;

/**
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
@XmlRootElement(name = "topic")
@XmlAccessorType(XmlAccessType.FIELD)
public class TopicDTO extends StringIdDTO {

    /**
     * Controls when the topic will auto delete.
     * If set to zero, then the topic will NOT auto
     * delete, otherwise the topic will auto delete
     * after it has been unused for the number
     * of seconds configured in this field.  If unset,
     * it defaults to 5 minutes
     */
    @XmlAttribute(name="auto_delete_after")
    public Integer auto_delete_after;

    @XmlAttribute(name="slow_consumer_policy")
    public String slow_consumer_policy;

    @XmlElement(name="acl")
    public TopicAclDTO acl;

}

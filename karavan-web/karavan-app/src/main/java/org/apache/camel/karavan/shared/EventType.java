/*
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
package org.apache.camel.karavan.shared;

public class EventType {

    //    Start Kubernetes or Docker event Listeners
    public static final String START_INFRASTRUCTURE_LISTENERS = "START_INFRASTRUCTURE_LISTENERS";

    //    Import projects from Git repository
    public static final String IMPORT_PROJECTS = "IMPORT_PROJECTS";

    public static final String START_INFINISPAN_IN_DOCKER = "START_INFINISPAN_IN_DOCKER";
    public static final String INFINISPAN_STARTED = "INFINISPAN_STARTED";
    public static final String GITEA_STARTED = "GITEA_STARTED";

    public static final String CONTAINER_STATUS = "CONTAINER_STATUS";
    public static final String DEVMODE_CONTAINER_READY = "DEVMODE_STATUS";
    public static final String DELAY_MESSAGE = "DELAY_MESSAGE";

}

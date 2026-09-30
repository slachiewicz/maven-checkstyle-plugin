/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.checkstyle.exec;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.apache.maven.api.Language;
import org.apache.maven.api.Project;
import org.apache.maven.api.ProjectScope;
import org.apache.maven.api.Session;
import org.apache.maven.api.model.Resource;
import org.apache.maven.api.services.ProjectManager;

/**
 * Reads the source and resource roots of a project through the Maven 4 {@link ProjectManager}.
 */
public final class ProjectRoots {

    private ProjectRoots() {}

    /**
     * Returns the Java source roots of the project, including roots added by other plugins.
     *
     * @param session the session
     * @param project the project
     * @param scope {@link ProjectScope#MAIN} or {@link ProjectScope#TEST}
     * @return the absolute source roots
     */
    public static List<File> sourceRoots(Session session, Project project, ProjectScope scope) {
        List<File> roots = new ArrayList<>();
        session.getService(ProjectManager.class)
                .getEnabledSourceRoots(project, scope, Language.JAVA_FAMILY)
                .forEach(root -> roots.add(root.directory().toFile()));
        return roots;
    }

    /**
     * Returns the resource roots of the project as model resources.
     *
     * @param session the session
     * @param project the project
     * @param scope {@link ProjectScope#MAIN} or {@link ProjectScope#TEST}
     * @return the resources
     */
    public static List<Resource> resources(Session session, Project project, ProjectScope scope) {
        List<Resource> resources = new ArrayList<>();
        session.getService(ProjectManager.class)
                .getEnabledSourceRoots(project, scope, Language.RESOURCES)
                .forEach(root -> resources.add(Resource.newBuilder()
                        .directory(root.directory().toString())
                        .includes(root.includes())
                        .excludes(root.excludes())
                        .build()));
        return resources;
    }
}

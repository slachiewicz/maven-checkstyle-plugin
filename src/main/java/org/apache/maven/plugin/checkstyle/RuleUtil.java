package org.apache.maven.plugin.checkstyle;

import com.puppycrawl.tools.checkstyle.api.AuditEvent;

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

/**
 * Tooling for Checkstyle rules conventions: names, categories.
 *
 * @author Hervé Boutemy
 * @since 2.13
 */
public class RuleUtil
{
    /**
     * Get the rule name from an audit event.
     *
     * @param event the audit event
     * @return the rule name, which is the class name without package and removed eventual "Check" suffix
     */
    public static String getName( AuditEvent event )
    {
        return getName( event.getSourceName() );
    }
    /**
     * Get the rule name from an audit event source name.
     *
     * @param eventSrcName the audit event source name
     * @return the rule name, which is the class name without package and removed eventual "Check" suffix
     */
    public static String getName( String eventSrcName )
    {
        if ( eventSrcName == null )
        {
            return null;
        }

        if ( eventSrcName.endsWith( "Check" ) )
        {
            eventSrcName = eventSrcName.substring( 0, eventSrcName.length() - 5 );
        }

        return eventSrcName.substring( eventSrcName.lastIndexOf( '.' ) + 1 );
    }

    /**
     * Get the rule category from an audit event.
     *
     * @param event the audit event
     * @return the rule category, which is the last package name or "misc" or "extension"
     */
    public static String getCategory( AuditEvent event )
    {
        return getCategory( event.getSourceName() );
    }

    /**
     * Get the rule category from an audit event source name.
     *
     * @param eventSrcName the audit event source name
     * @return the rule category, which is the last package name or "misc" or "extension"
     */
    public static String getCategory( String eventSrcName )
    {
        if ( eventSrcName == null )
        {
            return null;
        }

        int end = eventSrcName.lastIndexOf( '.' );
        eventSrcName = eventSrcName.substring( 0,  end );

        if ( "com.puppycrawl.tools.checkstyle.checks".equals( eventSrcName ) )
        {
            return "misc";
        }
        else if ( !eventSrcName.startsWith( "com.puppycrawl.tools.checkstyle.checks" ) )
        {
            return "extension";
        }

        return eventSrcName.substring( eventSrcName.lastIndexOf( '.' ) + 1 );
    }
}
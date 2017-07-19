//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//


package org.eclipse.jetty.maven.plugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.eclipse.jetty.xml.XmlConfiguration;

/**
 * WebAppStarter
 *
 *
 */
public class WebAppStarter extends AbstractLifeCycle
{
    JettyWebAppContext _webApp;
    String _properties;
    Properties _props;
    private ContextHandlerCollection _contexts;
    private Server _server;
    


    /** 
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        if (StringUtil.isBlank(_properties))
            throw new IllegalStateException("No properties");
        
        if (_contexts == null)
            throw new IllegalStateException("No contexts");        
        
        if (_server == null)
            throw new IllegalStateException("No Server");
        
        File f = new File(_properties);
        _props = new Properties();
        try (InputStream in = new FileInputStream(f))
        {
            _props.load(in);
        }
        
        _webApp = new JettyWebAppContext();
        
        //apply a properties file that defines the things that we configure in the jetty:run plugin:
        // - the context path
        String str = (String)_props.get("context.path");
        if (!StringUtil.isBlank(str))
            _webApp.setContextPath(str);
        
        // - web.xml
        str = (String)_props.get("web.xml");
        if (!StringUtil.isBlank(str))
            _webApp.setDescriptor(str); 
        
        str = (String)_props.get("quickstart.web.xml");
        if (!StringUtil.isBlank(str))
            _webApp.setQuickStartWebDescriptor(Resource.newResource(new File(str)));
        
        // - the tmp directory
        str = (String)_props.getProperty("tmp.dir");
        if (!StringUtil.isBlank(str))
            _webApp.setTempDirectory(new File(str.trim()));

        str = (String)_props.getProperty("tmp.dir.persist");
        if (!StringUtil.isBlank(str))
            _webApp.setPersistTempDirectory(Boolean.valueOf(str));
        
        //Get the calculated base dirs which includes the overlays
        str = (String)_props.getProperty("base.dirs");
        if (!StringUtil.isBlank(str))
        {
            ResourceCollection bases = new ResourceCollection(StringUtil.csvSplit(str));
            _webApp.setWar(null);
            _webApp.setBaseResource(bases);
        }     

        // - the equivalent of web-inf classes
        str = (String)_props.getProperty("classes.dir");
        if (!StringUtil.isBlank(str))
        {
            _webApp.setClasses(new File(str));
            System.err.println("SET CLASSES DIR="+str);
        }
        
        str = (String)_props.getProperty("testClasses.dir"); 
        if (!StringUtil.isBlank(str))
        {
            _webApp.setTestClasses(new File(str));
            System.err.println("SET TEST CLASSES DIR="+str);
        }


        // - the equivalent of web-inf lib
        str = (String)_props.getProperty("lib.jars");
        if (!StringUtil.isBlank(str))
        {
            List<File> jars = new ArrayList<File>();
            String[] names = StringUtil.csvSplit(str);
            for (int j=0; names != null && j < names.length; j++)
            {
                System.err.println("ADDING LIB="+names[j]);
                jars.add(new File(names[j].trim()));
            }
            _webApp.setWebInfLib(jars);
        }

        str = _props.getProperty( "projects.classes.dir" );
        if (str != null && !"".equals(str.trim()))
        {
            List<File> classesDirectories = //
                Arrays.stream(str.split( Pattern.quote("|") )) //
                    .map( s -> Paths.get( s).toFile() ).collect( Collectors.toList() );
            _webApp.getWebInfLib().addAll( classesDirectories );
        }
        
        
        //- a context xml file to apply: in keeping with the other goals that
        //cannot apply an xml file first to be overridden, we apply the context 
        //xml file last too
        str = (String)_props.getProperty("context.xml");
        if (!StringUtil.isBlank(str))
        {
            XmlConfiguration xmlConfiguration = new XmlConfiguration(Resource.newResource(str).getURI().toURL());
            xmlConfiguration.getIdMap().put("Server",_server);
            xmlConfiguration.configure(_webApp);
        }

        _contexts.addHandler(_webApp);
        
        super.doStart();
    }

    /** 
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStop()
     */
    @Override
    protected void doStop() throws Exception
    {
        if (_webApp != null)
            _webApp.stop();
        super.doStop();
    }

    /**
     * @return the properties
     */
    public String getProperties()
    {
        return _properties;
    }

    /**
     * @param properties the properties to set
     */
    public void setProperties(String properties)
    {
        _properties = properties;
    }
    
    public void setContexts (ContextHandlerCollection contexts)
    {
        _contexts  = contexts;
    }

    public ContextHandlerCollection getContexts()
    {
        return _contexts;
    }

    /**
     * @return the server
     */
    public Server getServer()
    {
        return _server;
    }

    /**
     * @param server the server to set
     */
    public void setServer(Server server)
    {
        _server = server;
    }
}

/**
 * (C) Copyright IBM Corporation 2014, 2019.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.openliberty.tools.maven.server;

import static org.twdata.maven.mojoexecutor.MojoExecutor.artifactId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.configuration;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executeMojo;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executionEnvironment;
import static org.twdata.maven.mojoexecutor.MojoExecutor.goal;
import static org.twdata.maven.mojoexecutor.MojoExecutor.groupId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.plugin;
import static org.twdata.maven.mojoexecutor.MojoExecutor.version;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.FileWriter;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.HashSet;
import java.util.Set;
import java.util.EnumSet;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import javax.xml.transform.TransformerException;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.tools.ant.taskdefs.Copy;
import org.apache.tools.ant.types.FileSet;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.twdata.maven.mojoexecutor.MojoExecutor.Element;

import io.openliberty.tools.ant.ServerTask;
import io.openliberty.tools.maven.BasicSupport;
import io.openliberty.tools.maven.utils.ExecuteMojoUtil;
import io.openliberty.tools.common.plugins.config.ServerConfigDropinXmlDocument;

import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Start/Debug server support.
 */
public class StartDebugMojoSupport extends BasicSupport {

    private static final String LIBERTY_MAVEN_PLUGIN_GROUP_ID = "io.openliberty.tools";
    private static final String LIBERTY_MAVEN_PLUGIN_ARTIFACT_ID = "liberty-maven-plugin";
    private static final String HEADER = "# Generated by liberty-maven-plugin";
    private static final String LIBERTY_CONFIG_MAVEN_PROPS = "(^liberty\\.(env|jvm|bootstrap|var|defaultVar)\\.).+";
    private static final Pattern pattern = Pattern.compile(LIBERTY_CONFIG_MAVEN_PROPS); 

    protected final String PLUGIN_VARIABLE_CONFIG_XML = "configDropins/overrides/liberty-plugin-variable-config.xml";
    protected final String PROJECT_ROOT_NAME = "io.openliberty.tools.projectRoot";
    protected final String PROJECT_ROOT_TARGET_LIBS = "target/libs";

    protected Map<String,String> bootstrapMavenProps = new HashMap<String,String>();  
    protected Map<String,String> envMavenProps = new HashMap<String,String>();  
    protected List<String> jvmMavenProps = new ArrayList<String>();  
    protected Map<String,String> varMavenProps = new HashMap<String,String>();  
    protected Map<String,String> defaultVarMavenProps = new HashMap<String,String>();  

    protected Map<String,String> combinedBootstrapProperties = null;
    protected List<String> combinedJvmOptions = null;
    
    @Component
    protected BuildPluginManager pluginManager;

    /**
     * Location of customized configuration file server.xml
     */
    @Parameter(alias="configFile", property = "serverXmlFile")
    protected File serverXmlFile;

    /**
     * Location of bootstrap.properties file.
     */
    @Parameter(property = "bootstrapPropertiesFile")
    protected File bootstrapPropertiesFile;

    @Parameter
    protected Map<String, String> bootstrapProperties;

    /**
     * Location of jvm.options file.
     */
    @Parameter(property = "jvmOptionsFile")
    protected File jvmOptionsFile;

    @Parameter
    protected List<String> jvmOptions;

    private enum PropertyType {
        BOOTSTRAP("liberty.bootstrap."),
        ENV("liberty.env."),
        JVM("liberty.jvm."),
        VAR("liberty.var."),
        DEFAULTVAR("liberty.defaultVar.");

        private final String prefix;

        private PropertyType(final String prefix) {
            this.prefix = prefix;
        }

        private static final Map<String, PropertyType> lookup = new HashMap<String, PropertyType>();

        static {
            for (PropertyType s : EnumSet.allOf(PropertyType.class)) {
               lookup.put(s.prefix, s);
            }
        }

        public static PropertyType getPropertyType(String propertyName) {
            // get a matcher object from pattern 
            Matcher matcher = pattern.matcher(propertyName); 
  
            // check whether Regex string is found in propertyName or not 
            if (matcher.find()) {
                // strip off the end of the property name to get the prefix
                String prefix = matcher.group(1);
                return lookup.get(prefix);
            }
            return null;
        } 

        public String getPrefix() {
            return prefix;
        }

    }

    protected ServerTask initializeJava() {
        ServerTask serverTask = (ServerTask) ant.createTask("antlib:io/openliberty/tools/ant:server");
        if (serverTask == null) {
            throw new IllegalStateException(MessageFormat.format(messages.getString("error.dependencies.not.found"), "server"));
        }
        serverTask.setInstallDir(installDirectory);
        serverTask.setServerName(serverName);
        serverTask.setUserDir(userDirectory);
        serverTask.setOutputDir(outputDirectory);
        return serverTask;
    }
    
    protected void runMojo(String groupId, String artifactId, String goal) throws MojoExecutionException {
        Plugin plugin = getPlugin(groupId, artifactId);
        Xpp3Dom config = ExecuteMojoUtil.getPluginGoalConfig(plugin, goal, log);
        log.info("Running " + artifactId + ":" + goal);
        log.debug("configuration:\n" + config);
        executeMojo(plugin, goal(goal), config,
                executionEnvironment(project, session, pluginManager));
    }
    
    /**
     * Given the groupId and artifactId get the corresponding plugin
     * 
     * @param groupId
     * @param artifactId
     * @return Plugin
     */
    protected Plugin getPlugin(String groupId, String artifactId) {
        Plugin plugin = project.getPlugin(groupId + ":" + artifactId);
        if (plugin == null) {
            plugin = plugin(groupId(groupId), artifactId(artifactId), version("RELEASE"));
        }
        return plugin;
    }
    
    protected Plugin getLibertyPlugin() {
        Plugin plugin = project.getPlugin(LIBERTY_MAVEN_PLUGIN_GROUP_ID + ":" + LIBERTY_MAVEN_PLUGIN_ARTIFACT_ID);
        if (plugin == null) {
            plugin = plugin(LIBERTY_MAVEN_PLUGIN_GROUP_ID, LIBERTY_MAVEN_PLUGIN_ARTIFACT_ID, "LATEST");
        }
        return plugin;
    }

    protected void runLibertyMojoCreate() throws MojoExecutionException {
        Xpp3Dom config = ExecuteMojoUtil.getPluginGoalConfig(getLibertyPlugin(), "create", log);
        runLibertyMojo("create", config);
    }

    protected void runLibertyMojoDeploy() throws MojoExecutionException {
        runLibertyMojoDeploy(true);
    }
    
    protected void runLibertyMojoDeploy(boolean forceLooseApp) throws MojoExecutionException {
        Xpp3Dom config = ExecuteMojoUtil.getPluginGoalConfig(getLibertyPlugin(), "deploy", log);
        if(forceLooseApp) {
            Xpp3Dom looseApp = config.getChild("looseApplication");
            if (looseApp != null && "false".equals(looseApp.getValue())) {
                log.warn("Overriding liberty plugin pararmeter, \"looseApplication\" to \"true\" and deploying application in looseApplication format");
                looseApp.setValue("true");
            }
        }
        runLibertyMojo("deploy", config);
    }

    protected void runLibertyMojoInstallFeature(Element features) throws MojoExecutionException {
        Xpp3Dom config = ExecuteMojoUtil.getPluginGoalConfig(getLibertyPlugin(), "install-feature", log);;
        if (features != null) {
            config = Xpp3Dom.mergeXpp3Dom(configuration(features), config);
        }
        runLibertyMojo("install-feature", config);
    }

    private void runLibertyMojo(String goal, Xpp3Dom config) throws MojoExecutionException {
        log.info("Running liberty:" + goal);
        log.debug("configuration:\n" + config);
        executeMojo(getLibertyPlugin(), goal(goal), config,
                executionEnvironment(project, session, pluginManager));
    }

    /**
     * @throws Exception
     */
    protected void copyConfigFiles() throws Exception {

        String jvmOptionsPath = null;
        String bootStrapPropertiesPath = null;
        String serverEnvPath = null;
        String serverXMLPath = null;

        // First check for Liberty configuration specified by Maven properties.
        loadLibertyConfigFromProperties();

        if (configDirectory != null && configDirectory.exists()) {
            // copy configuration files from configuration directory to server directory if end-user set it
            Copy copydir = (Copy) ant.createTask("copy");
            FileSet fileset = new FileSet();
            fileset.setDir(configDirectory);
            copydir.addFileset(fileset);
            copydir.setTodir(serverDirectory);
            copydir.setOverwrite(true);
            copydir.execute();

            File configDirServerXML = new File(configDirectory, "server.xml");
            if (configDirServerXML.exists()) {
                serverXMLPath = configDirServerXML.getCanonicalPath();
            }

            File configDirJvmOptionsFile = new File(configDirectory, "jvm.options");
            if (configDirJvmOptionsFile.exists()) {
                jvmOptionsPath = configDirJvmOptionsFile.getCanonicalPath();
            }

            File configDirBootstrapFile = new File(configDirectory, "bootstrap.properties");
            if (configDirBootstrapFile.exists()) {
                bootStrapPropertiesPath = configDirBootstrapFile.getCanonicalPath();
            }

            File configDirServerEnv = new File(configDirectory, "server.env");
            if (configDirServerEnv.exists()) {
                serverEnvPath = configDirServerEnv.getCanonicalPath();
            }
        }

        // copy server.xml file to server directory if end-user explicitly set it.
        if (serverXmlFile != null && serverXmlFile.exists()) {
            if (serverXMLPath != null) {
                log.warn("The " + serverXMLPath + " file is overwritten by the "+serverXmlFile.getCanonicalPath()+" file.");
            }
            Copy copy = (Copy) ant.createTask("copy");
            copy.setFile(serverXmlFile);
            copy.setTofile(new File(serverDirectory, "server.xml"));
            copy.setOverwrite(true);
            copy.execute();
            serverXMLPath = serverXmlFile.getCanonicalPath();
        }

        // copy jvm.options to server directory if end-user explicitly set it
        File optionsFile = new File(serverDirectory, "jvm.options");
        if (jvmOptions != null || !jvmMavenProps.isEmpty()) {
            if (jvmOptionsPath != null) {
                log.warn("The " + jvmOptionsPath + " file is overwritten by inlined configuration.");
            }
            writeJvmOptions(optionsFile, jvmOptions, jvmMavenProps);
            jvmOptionsPath = "inlined configuration";
        } else if (jvmOptionsFile != null && jvmOptionsFile.exists()) {
            if (jvmOptionsPath != null) {
                log.warn("The " + jvmOptionsPath + " file is overwritten by the "+jvmOptionsFile.getCanonicalPath()+" file.");
            }
            Copy copy = (Copy) ant.createTask("copy");
            copy.setFile(jvmOptionsFile);
            copy.setTofile(optionsFile);
            copy.setOverwrite(true);
            copy.execute();
            jvmOptionsPath = jvmOptionsFile.getCanonicalPath();
        }

        // copy bootstrap.properties to server directory if end-user explicitly set it
        File bootstrapFile = new File(serverDirectory, "bootstrap.properties");
        if (bootstrapProperties != null || !bootstrapMavenProps.isEmpty()) {
            if (bootStrapPropertiesPath != null) {
                log.warn("The " + bootStrapPropertiesPath + " file is overwritten by inlined configuration.");
            }
            writeBootstrapProperties(bootstrapFile, bootstrapProperties, bootstrapMavenProps);
            bootStrapPropertiesPath = "inlined configuration";
        } else if (bootstrapPropertiesFile != null && bootstrapPropertiesFile.exists()) {
            if (bootStrapPropertiesPath != null) {
                log.warn("The " + bootStrapPropertiesPath + " file is overwritten by the "+ bootstrapPropertiesFile.getCanonicalPath()+" file.");
            }
            Copy copy = (Copy) ant.createTask("copy");
            copy.setFile(bootstrapPropertiesFile);
            copy.setTofile(bootstrapFile);
            copy.setOverwrite(true);
            copy.execute();
            bootStrapPropertiesPath = bootstrapPropertiesFile.getCanonicalPath();
        }

        // copy server.env to server directory if end-user explicitly set it
        File envFile = new File(serverDirectory, "server.env");
        if (!envMavenProps.isEmpty()) {
            if (serverEnvPath != null) {
                log.warn("The " + serverEnvPath + " file is overwritten by inlined configuration.");
            }
            writeServerEnvProperties(envFile, envMavenProps);
            serverEnvPath = "inlined configuration";
        } else if (serverEnvFile != null && serverEnvFile.exists()) {
            Copy copy = (Copy) ant.createTask("copy");
            copy.setFile(serverEnvFile);
            copy.setTofile(envFile);
            copy.setOverwrite(true);
            copy.execute();
            serverEnvPath = serverEnvFile.getCanonicalPath();
        }

        if (!varMavenProps.isEmpty() || !defaultVarMavenProps.isEmpty()) {
            File pluginVariableConfig = new File(serverDirectory, PLUGIN_VARIABLE_CONFIG_XML);
            writeConfigDropinsServerVariables(pluginVariableConfig, varMavenProps, defaultVarMavenProps);  
        }

        // log info on the configuration files that get used
        if (serverXMLPath != null && !serverXMLPath.isEmpty()) {
            log.info(MessageFormat.format(messages.getString("info.server.start.update.config"),
                "server.xml", serverXMLPath));
        }
        if (jvmOptionsPath != null && !jvmOptionsPath.isEmpty()) {
            log.info(MessageFormat.format(messages.getString("info.server.start.update.config"),
                "jvm.options", jvmOptionsPath));
        }
        if (bootStrapPropertiesPath != null && !bootStrapPropertiesPath.isEmpty()) {
            log.info(MessageFormat.format(messages.getString("info.server.start.update.config"),
                "bootstrap.properties", bootStrapPropertiesPath));
        }
        if (serverEnvPath != null && !serverEnvPath.isEmpty()) {
            log.info(MessageFormat.format(messages.getString("info.server.start.update.config"),
                "server.env", serverEnvPath));
        }
    }

    private void loadLibertyConfigFromProperties() {

        loadLibertyConfigFromProperties(project.getProperties());
        loadLibertyConfigFromProperties(System.getProperties());

    }

    private void loadLibertyConfigFromProperties(Properties props) {
        Set<Entry<Object, Object>> entries = props.entrySet();
        for (Entry<Object, Object> entry : entries) {
            String key = (String) entry.getKey();
            PropertyType propType = PropertyType.getPropertyType(key);

            if (propType != null) {
                String suffix = key.substring(propType.getPrefix().length());
                String value = (String) entry.getValue();
                log.debug("Processing Liberty configuration from property with key "+key+" and value "+value);
                switch (propType) {
                    case ENV:        envMavenProps.put(suffix, value);
                                     break;
                    case BOOTSTRAP:  bootstrapMavenProps.put(suffix, value);
                                     break;
                    case JVM:        jvmMavenProps.add(value);
                                     break;
                    case VAR:        varMavenProps.put(suffix, value);
                                     break;
                    case DEFAULTVAR: defaultVarMavenProps.put(suffix, value);
                                     break;
                }
            }
        }
    }

    // The properties parameter comes from the <bootstrapProperties> configuration in pom.xml and takes precedence over
    // the mavenProperties parameter, which comes from generic maven <properties> configuration.
    // One of the passed in Maps must be not null and not empty
    private void writeBootstrapProperties(File file, Map<String, String> properties, Map<String, String> mavenProperties) throws IOException {
        if (!mavenProperties.isEmpty()) {
            if (properties == null) {
                combinedBootstrapProperties = mavenProperties;
            } else {
                combinedBootstrapProperties = new HashMap<String,String> ();
                // add the maven properties first so that they do not take precedence over the properties specified with <bootstrapProperties>
                combinedBootstrapProperties.putAll(mavenProperties);
                combinedBootstrapProperties.putAll(properties);
            }
        } else {
            combinedBootstrapProperties = properties;
        }

        makeParentDirectory(file);
        PrintWriter writer = null;
        try {
            writer = new PrintWriter(file, "UTF-8");
            writer.println(HEADER);
            for (Map.Entry<String, String> entry : combinedBootstrapProperties.entrySet()) {
                String key = entry.getKey();
                writer.print(key);
                writer.print("=");
                String value = entry.getValue();
       
                writer.println((value != null) ? value.replace("\\", "/") : "");
                if (value == null) {
                    log.warn("The value of the bootstrap property " + key + " is null. Verify if the needed POM properties are set correctly.");
                }
            }
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    private void writeServerEnvProperties(File file, Map<String, String> mavenProperties) throws IOException {
        makeParentDirectory(file);
        PrintWriter writer = null;
        try {
            writer = new PrintWriter(file, "UTF-8");
            writer.println(HEADER);
            for (Map.Entry<String, String> entry : mavenProperties.entrySet()) {
                String key = entry.getKey();
                writer.print(key);
                writer.print("=");
                String value = entry.getValue();
                writer.println((value != null) ? value.replace("\\", "/") : "");
                if (value == null) {
                    log.warn("The value of the server.env property " + entry.getKey() + " is null. Verify if the needed POM properties are set correctly.");
                }
            }
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    // One of the passed in Lists must be not null and not empty
    private void writeJvmOptions(File file, List<String> options, List<String> mavenProperties) throws IOException {
        if (!mavenProperties.isEmpty()) {
            if (options == null) {
                combinedJvmOptions = mavenProperties;
            } else {
                combinedJvmOptions = new ArrayList<String> ();
                // add the maven properties first so that they do not take precedence over the options specified with jvmOptions
                combinedJvmOptions.addAll(mavenProperties);
                combinedJvmOptions.addAll(options);
            }
        } else {
            combinedJvmOptions = options;
        }

        makeParentDirectory(file);
        PrintWriter writer = null;
        try {
            writer = new PrintWriter(file, "UTF-8");
            writer.println(HEADER);
            for (String option : combinedJvmOptions) {
                writer.println(option);
            }
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    private void writeConfigDropinsServerVariables(File file, Map<String,String> varMavenProps, Map<String,String> defaultVarMavenProps) throws IOException, TransformerException, ParserConfigurationException {

        ServerConfigDropinXmlDocument configDocument = ServerConfigDropinXmlDocument.newInstance();

        configDocument.createComment(HEADER);
        Set<String> existingVarNames = new HashSet<String>();

        for (Map.Entry<String, String> entry : varMavenProps.entrySet()) {
            String key = entry.getKey();
            existingVarNames.add(key);
            configDocument.createVariableWithValue(entry.getKey(), entry.getValue(), false);
        }

        for (Map.Entry<String, String> entry : defaultVarMavenProps.entrySet()) {
            // check to see if a variable with a value already exists with the same name and log it
            String key = entry.getKey();
            if (existingVarNames.contains(key)) {
                // since the defaultValue will only be used if no other value exists for the variable, 
                // it does not make sense to generate the variable with a defaultValue when we know a value already exists.
                log.warn("The variable with name "+key+" and defaultValue "+entry.getValue()+" is skipped since a variable with that name already exists with a value.");
            } else {
                // set boolean to true so the variable is created with a defaultValue instead of a value
                configDocument.createVariableWithValue(entry.getKey(), entry.getValue(), true);
            }
        }

        // write XML document to file
        makeParentDirectory(file);
        configDocument.writeXMLDocument(file);

    }

    private void makeParentDirectory(File file) {
        File parentDir = file.getParentFile();
        if (parentDir != null) {
            parentDir.mkdirs();
        }
    }

}

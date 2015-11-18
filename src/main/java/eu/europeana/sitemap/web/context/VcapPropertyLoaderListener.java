package eu.europeana.sitemap.web.context; /**
 * Created by ymamakis on 11/16/15.
 */

import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.SpringApplication;

import org.springframework.boot.context.config.ConfigFileApplicationListener;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.json.JsonParser;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.*;
import org.springframework.util.StringUtils;
import org.springframework.web.context.support.StandardServletEnvironment;


import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

public class VcapPropertyLoaderListener implements
        ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    private final static String REDISHOST = "vcap.services.redis2.credentials.host";
    private final static String REDISPORT = "vcap.services.redis2.credentials.port";
    private final static String REDISPASSWORD = "vcap.services.redis2.credentials.password";
    private final static String SWIFT_AUTHENTICATION_URL="vcap.services.swift-sitemap.credentials.authentication_uri";
    private final static String SWIFT_AUTHENTICATION_AV_ZONE="vcap.services.swift-sitemap.credentials.availability_zone";
    private final static String SWIFT_AUTHENTICATION_TENANT_NAME="vcap.services.swift-sitemap.credentials.tenant_name";
    private final static String SWIFT_AUTHENTICATION_USER_NAME="vcap.services.swift-sitemap.credentials.user_name";
    private final static String SWIFT_AUTHENTICATION_PASSWORD="vcap.services.swift-sitemap.credentials.password";
    private static final String VCAP_APPLICATION = "VCAP_APPLICATION";

    private static final String VCAP_SERVICES = "VCAP_SERVICES";

    // Before ConfigFileApplicationListener so values there can use these ones
    private int order = ConfigFileApplicationListener.DEFAULT_ORDER - 1;;

    private final JsonParser parser = JsonParserFactory.getJsonParser();
    /*
     * # no trailing slash api2.url=http://hostname.domain/api
     * api2.canonical.url=http://hostname.domain/api
     *
     * portal.server = http://hostname.domain/portal portal.server.canonical=http://hostname.domain
     */


    private static StandardServletEnvironment env = new StandardServletEnvironment();

    public VcapPropertyLoaderListener() {


        this.onApplicationEvent(new ApplicationEnvironmentPreparedEvent(new SpringApplication(),
                new String[0], env));
        ClassLoader c = getClass().getClassLoader();
        @SuppressWarnings("resource")
        URLClassLoader urlC = (URLClassLoader) c;
        URL[] urls = urlC.getURLs();
        String path = urls[0].getPath();
        Properties props = new Properties();

        File europeanaProperties = new File(path + "/sitemap.properties");
        try {
            props.load(new FileInputStream(europeanaProperties));


            if (env.getProperty(SWIFT_AUTHENTICATION_URL) != null) {
                props.setProperty("swift.authUrl", env.getProperty(SWIFT_AUTHENTICATION_URL));
                props.setProperty("swift.password", env.getProperty(SWIFT_AUTHENTICATION_PASSWORD));
                props.setProperty("swift.username", env.getProperty(SWIFT_AUTHENTICATION_USER_NAME));
                props.setProperty("swift.regionName", env.getProperty(SWIFT_AUTHENTICATION_AV_ZONE));
                props.setProperty("swift.tenantName", env.getProperty(SWIFT_AUTHENTICATION_TENANT_NAME));
                props.setProperty("swift.containerName", "sitemap");
            }


            // Write the Properties into the europeana.properties
            // Using the built-in store() method escapes certain characters (e.g. '=' and ':'), which is
            // not what we want to do (it breaks reading the properties elsewhere)
            // While we're writing the properties manually, might as well sort the list alphabetically...
            List<String> sortedKeys = new ArrayList<String>();
            for (Object key : props.keySet()) {
                sortedKeys.add(key.toString());
            }
            Collections.sort(sortedKeys);

            StringBuilder sb = new StringBuilder();
            sb.append("#Generated by the VCAPPropertyLoaderListener" + "\n");
            sb.append("#" + new Date().toString() + "\n");
            for (String key : sortedKeys) {
                sb.append(key + "=" + props.getProperty(key).toString() + "\n");
            }
            // Overwriting the original file
            FileUtils.writeStringToFile(europeanaProperties, sb + "\n", false);

        } catch (IOException e1) {
            e1.printStackTrace();

        }

    }




        public void setOrder(int order) {
            this.order = order;
        }

        @Override
        public int getOrder() {
            return this.order;
        }

        @Override
        public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
            ConfigurableEnvironment environment = event.getEnvironment();
            if (!environment.containsProperty(VCAP_APPLICATION)
                    && !environment.containsProperty(VCAP_SERVICES)) {
                return;
            }
            Properties properties = new Properties();
            addWithPrefix(properties, getPropertiesFromApplication(environment),
                    "vcap.application.");
            addWithPrefix(properties, getPropertiesFromServices(environment),
                    "vcap.services.");
            MutablePropertySources propertySources = environment.getPropertySources();
            if (propertySources
                    .contains(CommandLinePropertySource.COMMAND_LINE_PROPERTY_SOURCE_NAME)) {
                propertySources.addAfter(
                        CommandLinePropertySource.COMMAND_LINE_PROPERTY_SOURCE_NAME,
                        new PropertiesPropertySource("vcap", properties));
            }
            else {
                propertySources.addFirst(new PropertiesPropertySource("vcap", properties));
            }
        }

        private void addWithPrefix(Properties properties, Properties other, String prefix) {
            Enumeration propertynames = other.propertyNames();
           while (propertynames.hasMoreElements()) {
               String key = propertynames.nextElement().toString();
                String prefixed = prefix + key;
               if(other.getProperty(key)!=null) {
                   properties.setProperty(prefixed, other.getProperty(key));
               } else {
                   System.out.println(key);
               }
            }
        }

        private Properties getPropertiesFromApplication(Environment environment) {
            Properties properties = new Properties();
            try {
                Map<String, Object> map = this.parser.parseMap(environment.getProperty(
                        VCAP_APPLICATION, "{}"));
                extractPropertiesFromApplication(properties, map);
            }
            catch (Exception ex) {
               ex.printStackTrace();
            }
            return properties;
        }

        private Properties getPropertiesFromServices(Environment environment) {
            Properties properties = new Properties();
            try {
                Map<String, Object> map = this.parser.parseMap(environment.getProperty(
                        VCAP_SERVICES, "{}"));
                extractPropertiesFromServices(properties, map);
            }
            catch (Exception ex) {
                ex.printStackTrace();
            }
            return properties;
        }

        private void extractPropertiesFromApplication(Properties properties,
                                                      Map<String, Object> map) {
            if (map != null) {
                flatten(properties, map, "");
            }
        }

        private void extractPropertiesFromServices(Properties properties,
                                                   Map<String, Object> map) {
            if (map != null) {
                for (Object services : map.values()) {
                    @SuppressWarnings("unchecked")
                    List<Object> list = (List<Object>) services;
                    for (Object object : list) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> service = (Map<String, Object>) object;
                        String key = (String) service.get("name");
                        if (key == null) {
                            key = (String) service.get("label");
                        }
                        flatten(properties, service, key);
                    }
                }
            }
        }

        private void flatten(Properties properties, Map<String, Object> input, String path) {
            for (Map.Entry<String, Object> entry : input.entrySet()) {
                String key = entry.getKey();
                if (StringUtils.hasText(path)) {
                    if (key.startsWith("[")) {
                        key = path + key;
                    }
                    else {
                        key = path + "." + key;
                    }
                }
                Object value = entry.getValue();

                if (value instanceof String) {

                    properties.put(key, value);
                }
                else if (value instanceof Map) {
                    // Need a compound key
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) value;

                    flatten(properties, map, key);
                }
                else if (value instanceof Collection) {
                    // Need a compound key
                    @SuppressWarnings("unchecked")
                    Collection<Object> collection = (Collection<Object>) value;
                    properties.put(key,
                            StringUtils.collectionToCommaDelimitedString(collection));
                    int count = 0;

                    for (Object object : collection) {
                        flatten(properties,
                                Collections.singletonMap("[" + (count++) + "]", object), key);
                    }
                }
                else {
                    System.out.println("is else " +key+":" +value);
                    properties.put(key, value == null ? "" : ""+value);
                }
            }
        }


}

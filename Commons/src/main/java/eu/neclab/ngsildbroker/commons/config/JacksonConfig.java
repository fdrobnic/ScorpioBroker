package eu.neclab.ngsildbroker.commons.config;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

@ApplicationScoped
public class JacksonConfig {

    private static final Logger logger = LoggerFactory.getLogger(JacksonConfig.class);

    @Inject
    ObjectMapper objectMapper;

    @PostConstruct
    public void enableSourceInLocation() {
        // Use reflection-only approach to enable a parser feature named
        // INCLUDE_SOURCE_IN_LOCATION across different Jackson versions
        try {
            Object factory = objectMapper.getFactory();
            java.lang.reflect.Method[] methods = factory.getClass().getMethods();
            boolean invoked = false;
            for (java.lang.reflect.Method m : methods) {
                String name = m.getName();
                if (!"enable".equals(name) && !"configure".equals(name)) {
                    continue;
                }
                Class<?>[] params = m.getParameterTypes();
                if (params.length < 1 || params.length > 2) {
                    continue;
                }
                Class<?> p = params[0];
                if (!p.isEnum()) {
                    continue;
                }
                try {
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    Enum e = Enum.valueOf((Class) p, "INCLUDE_SOURCE_IN_LOCATION");
                    if (params.length == 1) {
                        m.invoke(factory, e);
                    } else {
                        m.invoke(factory, e, true);
                    }
                    invoked = true;
                    logger.info("Enabled INCLUDE_SOURCE_IN_LOCATION via {}({})", name, p.getSimpleName());
                    break;
                } catch (IllegalArgumentException ignore) {
                    // enum doesn't have that constant; try next
                }
            }
            if (!invoked) {
                logger.warn("Could not find suitable enable/configure(...) method to set INCLUDE_SOURCE_IN_LOCATION");
            }
        } catch (Throwable t) {
            logger.warn("Reflection fallback failed when enabling INCLUDE_SOURCE_IN_LOCATION: {}", t.getMessage());
        }
    }
}

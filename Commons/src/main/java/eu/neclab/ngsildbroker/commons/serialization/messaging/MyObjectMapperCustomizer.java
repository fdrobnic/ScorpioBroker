package eu.neclab.ngsildbroker.commons.serialization.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;

import eu.neclab.ngsildbroker.commons.datatypes.requests.BaseRequest;
import io.quarkus.jackson.ObjectMapperCustomizer;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
@Startup
public class MyObjectMapperCustomizer implements ObjectMapperCustomizer {

    private static final Logger logger = LoggerFactory.getLogger(MyObjectMapperCustomizer.class);

    @Override
    public void customize(ObjectMapper objectMapper) {
        SimpleModule tmp = new SimpleModule();
        tmp.addDeserializer(BaseRequest.class, new BaseRequestDeserializer());
        objectMapper.registerModule(tmp);
        enableIncludeSourceInLocation(objectMapper);
    }

    private void enableIncludeSourceInLocation(ObjectMapper objectMapper) {
        try {
            Object factory = objectMapper.getFactory();
            java.lang.reflect.Method[] methods = factory.getClass().getMethods();
            boolean enabled = false;
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
                    enabled = true;
                    logger.info("Enabled INCLUDE_SOURCE_IN_LOCATION via {}({})", name, p.getSimpleName());
                    break;
                } catch (IllegalArgumentException ignore) {
                    // enum doesn't have that constant; try next
                }
            }
            if (!enabled) {
                logger.warn("Could not find suitable enable/configure(...) method to set INCLUDE_SOURCE_IN_LOCATION");
            }
        } catch (Throwable t) {
            logger.warn("Failed to enable INCLUDE_SOURCE_IN_LOCATION on ObjectMapper: {}", t.getMessage());
        }
    }
}


package com.burak.core.wiremock.servlet;

import com.burak.core.wiremock.http.RequestHandler;
import com.burak.core.wiremock.transformers.DynamicResponseTransformer;
import com.github.tomakehurst.wiremock.common.Notifier;
import com.github.tomakehurst.wiremock.common.ServletContextFileSource;
import com.github.tomakehurst.wiremock.common.Slf4jNotifier;
import com.github.tomakehurst.wiremock.core.MappingsSaver;
import com.github.tomakehurst.wiremock.core.WireMockApp;
import com.github.tomakehurst.wiremock.extension.ResponseTransformer;
import com.github.tomakehurst.wiremock.global.NotImplementedRequestDelayControl;
import com.github.tomakehurst.wiremock.http.AdminRequestHandler;
import com.github.tomakehurst.wiremock.http.BasicResponseRenderer;
import com.github.tomakehurst.wiremock.http.ProxyResponseRenderer;
import com.github.tomakehurst.wiremock.http.StubResponseRenderer;
import com.github.tomakehurst.wiremock.servlet.NotImplementedContainer;
import com.github.tomakehurst.wiremock.servlet.NotImplementedMappingsSaver;
import com.github.tomakehurst.wiremock.servlet.WireMockWebContextListener;
import com.github.tomakehurst.wiremock.standalone.JsonFileMappingsLoader;
import com.google.common.base.Optional;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import java.util.HashMap;
import java.util.Map;

import static com.google.common.base.Optional.fromNullable;

/**
 * This class is derived from wiremock library to override the request handler
 */
public class WireMockWebContextListenerExtension extends WireMockWebContextListener {

    private static final String FILES_ROOT = "__files";
    private static final String REPLACEMENTS_ROOT = "replacements";
    private static final String APP_CONTEXT_KEY = "WireMockApp";
    private static final String FILE_SOURCE_ROOT_KEY = "WireMockFileSourceRoot";

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        ServletContext context = sce.getServletContext();
        String fileSourceRoot = context.getInitParameter(FILE_SOURCE_ROOT_KEY);

        ServletContextFileSource fileSource = new ServletContextFileSource(context, fileSourceRoot);

        Optional<Integer> maxRequestJournalEntries = readMaxRequestJournalEntries(context);
        boolean verboseLoggingEnabled = Boolean.parseBoolean(
                fromNullable(context.getInitParameter("verboseLoggingEnabled"))
                        .or("true"));

        JsonFileMappingsLoader defaultMappingsLoader = new JsonFileMappingsLoader(fileSource.child("mappings"));
        MappingsSaver mappingsSaver = new NotImplementedMappingsSaver();
        Map<String, ResponseTransformer> transformers = new HashMap<>();
        DynamicResponseTransformer transformer = new DynamicResponseTransformer();
        transformer.setReplacementsFolder(fileSource.child(REPLACEMENTS_ROOT));
        transformers.put("dynamic-response-transformer", transformer);
        WireMockApp wireMockApp = new WireMockApp(
                new NotImplementedRequestDelayControl(),
                false,
                defaultMappingsLoader,
                mappingsSaver,
                false,
                maxRequestJournalEntries,
                transformers,
                fileSource,
                new NotImplementedContainer()
        );
        AdminRequestHandler adminRequestHandler = new AdminRequestHandler(wireMockApp, new BasicResponseRenderer());
        RequestHandler stubRequestHandler = new RequestHandler(wireMockApp,
                new StubResponseRenderer(fileSource.child(FILES_ROOT),
                        wireMockApp.getGlobalSettingsHolder(),
                        new ProxyResponseRenderer()));
        context.setAttribute(APP_CONTEXT_KEY, wireMockApp);
        context.setAttribute(RequestHandler.class.getName(), stubRequestHandler);
        context.setAttribute(AdminRequestHandler.class.getName(), adminRequestHandler);
        context.setAttribute(Notifier.KEY, new Slf4jNotifier(verboseLoggingEnabled));
    }

    /**
     * @param context Servlet context for parameter reading
     * @return Maximum number of entries or absent
     */
    private Optional<Integer> readMaxRequestJournalEntries(ServletContext context) {
        String str = context.getInitParameter("maxRequestJournalEntries");
        if (str == null) {
            return Optional.absent();
        }
        return Optional.of(Integer.parseInt(str));
    }
}


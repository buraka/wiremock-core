/*
 * Copyright 2003-2015 Monitise Group Limited. All Rights Reserved.
 *
 * Save to the extent permitted by law, you may not use, copy, modify,
 * distribute or create derivative works of this material or any part
 * of it without the prior written consent of Monitise Group Limited.
 * Any reproduction of this material must contain this notice.
 */
package com.burak.core.wiremock.http;

import com.github.tomakehurst.wiremock.core.StubServer;
import com.github.tomakehurst.wiremock.http.AbstractRequestHandler;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.http.ResponseRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.tomakehurst.wiremock.common.LocalNotifier.notifier;

/**
 * This class is derived from wiremock library to add additional logging
 */
public class RequestHandler extends AbstractRequestHandler {

    private static final Logger LOG = LoggerFactory.getLogger(RequestHandler.class);
    private final StubServer stubServer;

    public RequestHandler(StubServer stubServer, ResponseRenderer responseRenderer) {
        super(responseRenderer);
        this.stubServer = stubServer;
    }

    @Override
    public ResponseDefinition handleRequest(Request request) {
        notifier().info("Request received:\n" + request);

        LOG.info("------------------Request received--------------------");
        LOG.info("Request Url: [{}]", request.getAbsoluteUrl());
        LOG.info("Request body: [{}]", request.getBodyAsString());
        LOG.info("Request headers: [{}]", request.getHeaders());
        LOG.info("---------------End of request received----------------");

        ResponseDefinition responseDefinition = stubServer.serveStubFor(request);

        LOG.info("----------------Response evaluated--------------------");
        LOG.info("Response body: [{}]", responseDefinition.getBody());
        LOG.info("Response body file name: [{}]", responseDefinition.getBodyFileName());
        LOG.info("Response headers: [{}]", responseDefinition.getHeaders());
        LOG.info("-------------End of response evaluated----------------");

        return responseDefinition;
    }
}
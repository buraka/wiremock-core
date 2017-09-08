package com.burak.core.wiremock.transformers;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.BinaryFile;
import com.github.tomakehurst.wiremock.common.FileSource;
import com.github.tomakehurst.wiremock.common.TextFile;
import com.github.tomakehurst.wiremock.extension.ResponseTransformer;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.google.common.base.Strings;
import com.burak.core.wiremock.exception.SimulatorException;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;

import static com.google.common.base.Charsets.UTF_8;

public class DynamicResponseTransformer extends ResponseTransformer {

    private static final Logger LOG = LoggerFactory.getLogger(DynamicResponseTransformer.class);
    private static final String REQUEST_BODY_REPLACER = "request.body.";
    private static final String REQUEST_URL_PARAM = "request.url.param.";
    private static final String REQUEST_KEY_PARAM = "request.key.";
    private static final String UUID_WITHOUT_HYPHEN_REPLACER = "%uuid.without.hyphen%";
    private FileSource replacementsFolder;

    @Override
    public ResponseDefinition transform(Request request, ResponseDefinition responseDefinition, FileSource filesFolder) {
        String responseBodyFileName = responseDefinition.getBodyFileName();
        if (responseBodyFileName == null) {
            LOG.info("Not evaluating a dynamic response, as the request-response mapping is not using a bodyFileName");
            return responseDefinition;
        } else {
            return evaluateDynamicResponse(request, responseDefinition, filesFolder);
        }
    }

    private ResponseDefinition evaluateDynamicResponse(Request request, ResponseDefinition responseDefinition, FileSource filesFolder) {
        LOG.info("Evaluating dynamic response for the request [{}: {}]", request.getMethod(), request.getUrl());
        final String responseBodyFileName = responseDefinition.getBodyFileName();

        String responseBody = getCurrentResponseBody(responseBodyFileName, filesFolder);
        boolean isReplaceable = responseBody.contains(UUID_WITHOUT_HYPHEN_REPLACER);
        if (isReplaceable) {
            String uuidWithoutHyphen = UUID.randomUUID().toString().replaceAll("-", "");
            responseBody = getReplacedResponseBody(responseBody, UUID_WITHOUT_HYPHEN_REPLACER, uuidWithoutHyphen);
        }

        if (replacementsFolder.exists()) {
            final Map<String, String> replaceableStrings = getReplaceableStrings(responseBodyFileName, request);
            if (replaceableStrings.isEmpty() && !isReplaceable) {
                LOG.info("Replaceable strings are not defined at [/replacements/{}]", responseBodyFileName);
                return responseDefinition;
            }

            for (Map.Entry<String, String> entry : replaceableStrings.entrySet()) {
                responseBody = getReplacedResponseBody(responseBody, entry.getKey(), entry.getValue());
            }
        }

        responseDefinition.setBodyFileName(null);
        return ResponseDefinitionBuilder.like(responseDefinition).withBody(responseBody).build();
    }

    private String getReplacedResponseBody(String responseBody, String toBeReplaced, String replacedWith) {
        LOG.info("Replacing [{}] with [{}]", toBeReplaced, replacedWith);
        return responseBody.replaceAll(toBeReplaced, replacedWith);
    }

    private String getCurrentResponseBody(String responseBodyFileName, FileSource filesFolder) {
        FileSource responseBodyFolder = requestSourceDirectory(responseBodyFileName, filesFolder);
        String responseBodyFile = responseBodyFileName.substring(responseBodyFileName.lastIndexOf("/") + 1);
        BinaryFile binaryFile = responseBodyFolder.getBinaryFileNamed(responseBodyFile);

        try {
            return new String(binaryFile.readContents(), UTF_8);
        } catch (Exception ex) {
            throw new SimulatorException("Could not find the Current Response body file: " + responseBodyFileName);
        }
    }

    private Map<String, String> getReplaceableStrings(String responseBodyFileName, Request request) {
        FileSource currentReplacementsFolder = requestSourceDirectory(responseBodyFileName, replacementsFolder);

        String responseBodyFileNameNoExt = responseBodyFileName.substring(responseBodyFileName.lastIndexOf("/") + 1,
                                                                          responseBodyFileName.lastIndexOf('.'));

        Map<String, String> replaceableStrings = new HashMap<>();
        List<TextFile> replacementFiles = currentReplacementsFolder.listFilesRecursively();
        for (TextFile replacementFile : replacementFiles) {
            String replacementFileName = replacementFile.name();
            String replacementBodyFileNameNoExt = replacementFileName.substring(replacementFileName.lastIndexOf("/") + 1, replacementFileName.lastIndexOf("."));
            if (replacementBodyFileNameNoExt.equals(responseBodyFileNameNoExt)) {
                JSONObject json = getJsonObject(replacementFile.readContentsAsString());
                for (Iterator i = json.keys(); i.hasNext(); ) {
                    String key = i.next().toString();
                    String value = evaluateReplaceable(responseBodyFileName, key, json, request);
                    replaceableStrings.put(key, value);
                }
            }
        }
        return replaceableStrings;
    }

    private FileSource requestSourceDirectory(String responseBodyFileName, FileSource baseDir) {
        FileSource currentSourceDir = baseDir;
        int pathIndex = responseBodyFileName.lastIndexOf("/");
        if (pathIndex > 0) {
            String responseBodyPath = responseBodyFileName.substring(0, pathIndex);
            if (!Strings.isNullOrEmpty(responseBodyPath)) {
                currentSourceDir = baseDir.child(responseBodyPath);
            }
        }
        return currentSourceDir;
    }

    private String evaluateReplaceable(String responseBodyFileName, String key, JSONObject json, Request request) {
        String toBeEvaluated = getJsonValue(key, json);
        if (toBeEvaluated.contains(REQUEST_KEY_PARAM)) {
            return getJsonKey(toBeEvaluated, request);
        } else if (toBeEvaluated.contains(REQUEST_BODY_REPLACER)) {
            toBeEvaluated = toBeEvaluated.replaceAll(REQUEST_BODY_REPLACER, "");
            String requestBodyString = request.getBodyAsString();
            if (responseBodyFileName.contains(".json")) {
                JSONObject requestBody = getJsonObject(requestBodyString);
                return getJsonValue(toBeEvaluated, requestBody);
            } else if (responseBodyFileName.contains(".xml")) {
                return getXMLValue(toBeEvaluated, requestBodyString);
            }
        } else if (toBeEvaluated.contains(REQUEST_URL_PARAM)) {
            int urlParamNumber = getUrlParamNumber(toBeEvaluated);
            String[] urlParams = request.getUrl().split("/");
            int urlParamsLength = urlParams.length;
            if (urlParamNumber >= urlParamsLength) {
                // the first '/' is also counted hence doing a urlParamsLength - 1 below.
                throw new SimulatorException("The current url contains [" + (urlParamsLength - 1)
                        + "] url parameters. But the replacement value is configured to evaluate ["
                        + toBeEvaluated + "].");
            }
            return urlParams[urlParamNumber];
        }
        throw new SimulatorException("Dynamic response is not yet supported for a value like: " + toBeEvaluated);
    }

    private int getUrlParamNumber(String toBeEvaluated) {
        toBeEvaluated = toBeEvaluated.replaceAll(REQUEST_URL_PARAM, "");
        try {
            return Integer.valueOf(toBeEvaluated);
        } catch (NumberFormatException e) {
            throw new SimulatorException("Url param number should be passed as a number after ["
                    + REQUEST_URL_PARAM + "], but is [" + REQUEST_URL_PARAM + toBeEvaluated + "]");
        }
    }

    private String getJsonKey(String toBeEvaluated, Request request) {
        String key = toBeEvaluated.replaceAll(REQUEST_KEY_PARAM, "");
        String requestBodyString = request.getBodyAsString();
        JSONObject requestBody = getJsonObject(requestBodyString);
        String[] keys = key.split("\\.");
        String jsonKey;
        String value = "";
        for (int i = 0; i < keys.length; i++) {
            jsonKey = keys[i];
            if (i < keys.length - 1) {
                requestBody = getJsonValueFromJsonString(requestBody, jsonKey);
            } else {
                int attempts = -1;
                Iterator jsonKeys = requestBody.keys();
                int jsonKeyElement;
                try {
                    jsonKeyElement = Integer.parseInt(jsonKey);
                } catch (NumberFormatException e) {
                    throw new SimulatorException("The value \"" + jsonKey + "\" is not a number. Please check your replacement file");
                }
                while (jsonKeys.hasNext() && attempts != jsonKeyElement) {
                    value = (String) jsonKeys.next();
                    attempts++;
                }
            }
        }
        return value;
    }

    private JSONObject getJsonValueFromJsonString(JSONObject jsonString, String key) {
        try {
            return jsonString.getJSONObject(key);
        } catch (JSONException e) {
            throw new SimulatorException("The request body does not contain the value " + key);
        }
    }

    private String getJsonValue(String key, JSONObject json) {
        String[] keys = key.split("\\.");
        String jsonKey = "";
        if (keys.length > 1) {
            for (int i = 0; i < keys.length; i++) {
                jsonKey = keys[i];
                if (i < keys.length - 1) {
                    json = getJsonValueFromJsonString(json, jsonKey);
                }
            }
        } else {
            jsonKey = keys[0];
        }
        try {
            return json.getString(jsonKey);
        } catch (JSONException e) {
            throw new SimulatorException("Could not read the value for json element: " + jsonKey, e);
        }
    }

    private String getXMLValue(String element, String xml) {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder;
        try {
            builder = factory.newDocumentBuilder();
            Document document = builder.parse(new InputSource(new StringReader(xml)));
            return document.getElementsByTagName(element).item(0).getTextContent();
        } catch (ParserConfigurationException | SAXException | IOException | NullPointerException e) {
            throw new SimulatorException("Could not read the value for xml node: " + element, e);
        }
    }

    private JSONObject getJsonObject(String json) {
        try {
            return new JSONObject(json);
        } catch (JSONException e) {
            throw new SimulatorException("Could not process the json: " + json, e);
        }
    }

    @Override
    public String name() {
        return "dynamic-response-transformer";
    }

    public void setReplacementsFolder(FileSource replacementsFolder) {
        this.replacementsFolder = replacementsFolder;
    }
}

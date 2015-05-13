package com.netflix.zuul.context;

import com.google.inject.Inject;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;

import javax.annotation.Nullable;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Enumeration;
import java.util.Map;

/**
 * User: michaels@netflix.com
 * Date: 4/29/15
 * Time: 11:25 AM
 */
public class ServletSessionContextFactory implements SessionContextFactory<HttpServletRequest, HttpServletResponse>
{
    private static final Logger LOG = LoggerFactory.getLogger(ServletSessionContextFactory.class);
    private SessionContextDecorator decorator;

    @Inject
    public ServletSessionContextFactory(@Nullable SessionContextDecorator decorator) {
        this.decorator = decorator;
    }

    @Override
    public Observable<SessionContext> create(HttpServletRequest servletRequest)
    {
        // Parse the headers.
        Headers reqHeaders = new Headers();
        Enumeration headerNames = servletRequest.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = (String) headerNames.nextElement();
            Enumeration values = servletRequest.getHeaders(name);
            while (values.hasMoreElements()) {
                String value = (String) values.nextElement();
                reqHeaders.add(name, value);
            }
        }

        // Parse the url query parameters.
        HttpQueryParams queryParams = HttpQueryParams.parse(servletRequest.getQueryString());

        // Build the request object.
        HttpRequestMessage request = new HttpRequestMessage(servletRequest.getProtocol(), servletRequest.getMethod(), servletRequest.getRequestURI(), queryParams, reqHeaders, servletRequest.getRemoteAddr(), servletRequest.getScheme());

        // Buffer the request body into a byte array.
        request.setBody(bufferBody(servletRequest));

        // Create an empty response object.
        HttpResponseMessage response = new HttpResponseMessage(200);

        // Create the context.
        SessionContext ctx = new SessionContext(request, response);

        // Optionally decorate it.
        if (decorator != null) {
            ctx = decorator.decorate(ctx);
        }

        // Wrap in an Observable.
        return Observable.just(ctx);
    }

    private byte[] bufferBody(HttpServletRequest servletRequest)
    {
        byte[] body = null;
        try {
            body = IOUtils.toByteArray(servletRequest.getInputStream());
        }
        catch (SocketTimeoutException e) {
            // This can happen if the request body is smaller than the size specified in the
            // Content-Length header, and using tomcat APR connector.
            LOG.error("SocketTimeoutException reading request body from inputstream. error=" + String.valueOf(e.getMessage()));
        }
        catch (IOException e) {
            LOG.error("Exception reading request body from inputstream.", e);
        }
        return body;
    }

    @Override
    public void write(SessionContext ctx, HttpServletResponse servletResponse)
    {
        HttpResponseMessage responseMessage = (HttpResponseMessage) ctx.getResponse();
        if (responseMessage == null) {
            throw new RuntimeException("Null HttpResponseMessage when attempting to write to ServletResponse!");
        }

        // Status.
        servletResponse.setStatus(responseMessage.getStatus());

        // Headers.
        for (Map.Entry<String, String> header : responseMessage.getHeaders().entries()) {
            servletResponse.setHeader(header.getKey(), header.getValue());
        }

        // Body.
        if (responseMessage.getBody() != null) {
            try {
                ServletOutputStream output = servletResponse.getOutputStream();
                IOUtils.write(responseMessage.getBody(), output);
                output.flush();
            }
            catch (IOException e) {
                throw new RuntimeException("Error writing response body to outputstream!", e);
            }
        }
    }
}

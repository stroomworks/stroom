/*
 * Copyright 2016-2025 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.dropwizard.common;

import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.ext.ExceptionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BasicExceptionMapper implements ExceptionMapper<Throwable> {

    private static final Logger LOGGER = LoggerFactory.getLogger(BasicExceptionMapper.class);

    @Override
    public Response toResponse(final Throwable exception) {
        if (exception instanceof WebApplicationException) {
            final WebApplicationException wae = (WebApplicationException) exception;
            return wae.getResponse();
        } else if (exception.getClass().getName().contains("AuthenticationException") ||
                exception.getClass().getName().contains("TokenException") ||
                // SyntaxException is the ANTLR-driven query parser's equivalent of the legacy TokenException (a
                // malformed StroomQL/Cypher query) - classify it the same way so a syntax error is a client
                // error, not an opaque HTTP 500, whichever compiler produced it.
                exception.getClass().getName().contains("SyntaxException") ||
                exception.getClass().getName().contains("PermissionException")) {
            return createExceptionResponse(Status.FORBIDDEN, exception);
        } else {
            return createExceptionResponse(Status.INTERNAL_SERVER_ERROR, exception);
        }
    }

    private Response createExceptionResponse(final Response.Status status,
                                             final Throwable throwable) {
        LOGGER.debug(throwable.getMessage(), throwable);
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(new ErrorMessage(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(),
                        throwable.getMessage(),
                        throwable.toString()))
                .build();
    }
}

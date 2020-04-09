/*
 * Copyright (C) 2020 Dremio
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
package com.dremio.iceberg.server.rest;

import java.util.Optional;
import java.util.UUID;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Metered;
import com.codahale.metrics.annotation.Timed;
import com.dremio.iceberg.backend.Backend;
import com.dremio.iceberg.model.Tag;
import com.dremio.iceberg.model.Tags;
import com.dremio.iceberg.server.ServerConfiguration;
import com.dremio.iceberg.server.auth.Secured;

@Path("tags")
public class ListTags {

  private static final Logger logger = LoggerFactory.getLogger(ListTags.class);
  private ServerConfiguration config;
  private Backend backend;

  @Inject
  public ListTags(ServerConfiguration config, Backend backend) {
    this.config = config;
    this.backend = backend;
  }

  @GET
  @Metered
  @ExceptionMetered(name="exception-readall")
  @Secured
  @RolesAllowed({"admin", "user"})
  @Timed(name="timed-readall")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getTables(@QueryParam("namespace") String namespace) {
    Tags tags = new Tags(backend.tagBackend().getAll(namespace, false));
    return Response.ok(tags).build();
  }


  @GET
  @Path("{name}")
  @Metered
  @ExceptionMetered(name="exception-read")
  @Secured
  @RolesAllowed({"admin", "user"})
  @Timed(name="timed-read")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getTable(@PathParam("name") String name, @Context Request request) {
    Tag tag = backend.tagBackend().get(name);
    if (tag == null || tag.isDeleted()) {
      return Response.status(404, "tag does not exist").build();
    }
    EntityTag eTag = new EntityTag(Integer.toString(tag.hashCode()));
    return Response.ok(tag).tag(eTag).build();
  }

  @GET
  @Path("by-name/{name}")
  @Metered
  @ExceptionMetered(name="exception-read-name")
  @Secured
  @RolesAllowed({"admin", "user"})
  @Timed(name="timed-read-name")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getTableByName(@PathParam("name") String name, @QueryParam("namespace") String namespace) {
    Optional<Tag> tag = backend.tagBackend().getAll(namespace, false).stream()
      .filter(t -> t.getName().equals(name)).findFirst();
    if (!tag.isPresent() || tag.get().isDeleted()) {
      return Response.status(404, "tag does not exist").build();
    }
    EntityTag eTag = new EntityTag(Integer.toString(tag.hashCode()));
    return Response.ok(tag.get()).tag(eTag).build();
  }

  @POST
  @Metered
  @ExceptionMetered(name="exception-create")
  @Secured
  @RolesAllowed({"admin"})
  @Timed(name="timed-create")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response setTable(Tag tag) {
    try {
      Optional<Tag> tagExisting = backend.tagBackend().getAll(null, false).stream()
//        .filter(t -> t.getName().equals(tag.getTableName()))
//        .filter(t -> StringUtils.compare(t.getNamespace(), tag.getNamespace()) == 0)
        .findFirst();
      if (tagExisting.isPresent()) {
        return Response.status(409).build();
      }
      String id = UUID.randomUUID().toString();
      tag.setUuid(id);
      backend.tagBackend().create(id, tag);
      return Response.status(201).header(HttpHeaders.LOCATION, "tags/" + id).build();
    } catch (Throwable t) {
      return Response.status(400, "something went wrong").build();
    }
  }

  @DELETE
  @Metered
  @ExceptionMetered(name="exception-delete")
  @Secured
  @RolesAllowed({"admin"})
  @Timed(name="timed-delete")
  @Path("{name}")
  public Response deleteTable(
    @PathParam("name") String name,
    @DefaultValue("false") @QueryParam("purge") boolean purge) {
    try {
      if (backend.tagBackend().get(name) == null || backend.tagBackend().get(name).isDeleted()) {
        throw new RuntimeException();
      }
      if (purge) {
        backend.tagBackend().remove(name);
      } else {
        Tag tag = backend.tagBackend().get(name);
        tag.setDeleted(true);
        backend.tagBackend().update(name, tag);
      }
      return Response.status(200).build();
    } catch (Throwable t) {
      return Response.status(404, "something went wrong").build();
    }
  }

  @PUT
  @Metered
  @ExceptionMetered(name="exception-update")
  @Secured
  @RolesAllowed({"admin"})
  @Timed(name="timed-update")
  @Path("{name}")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response updateTable(@PathParam("name") String name, @Context Request request, Tag tag) {
    EntityTag eTag = new EntityTag(Integer.toString(backend.tagBackend().get(name).hashCode()));
    Response.ResponseBuilder evaluationResultBuilder = request.evaluatePreconditions(eTag);
    if (evaluationResultBuilder == null) {
      try {
        backend.tagBackend().update(name, tag);
        return Response.status(200).build();
      } catch (Throwable t) {
        return Response.status(404, "something went wrong").build();
      }
    } else {
      return evaluationResultBuilder.status(412, "Tag not up to date").build();
    }
  }
}
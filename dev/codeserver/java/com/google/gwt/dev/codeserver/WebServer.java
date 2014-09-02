/*
 * Copyright 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.gwt.dev.codeserver;

import com.google.gwt.core.ext.TreeLogger;
import com.google.gwt.core.ext.TreeLogger.Type;
import com.google.gwt.core.ext.UnableToCompleteException;
import com.google.gwt.dev.json.JsonObject;

import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.server.AbstractHttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlets.GzipFilter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * The web server for Super Dev Mode, also known as the code server. The URLs handled include:
 * <ul>
 *   <li>HTML pages for the front page and module pages</li>
 *   <li>JavaScript that implementing the bookmarklets</li>
 *   <li>The web API for recompiling a GWT app</li>
 *   <li>The output files and log files from the GWT compiler</li>
 *   <li>Java source code (for source-level debugging)</li>
 * </ul>
 *
 * <p>EXPERIMENTAL. There is no authentication, encryption, or XSS protection, so this server is
 * only safe to run on localhost.</p>
 */
public class WebServer {

  private static final Pattern SAFE_DIRECTORY =
      Pattern.compile("([a-zA-Z0-9_-]+\\.)*[a-zA-Z0-9_-]+"); // no extension needed

  private static final Pattern SAFE_FILENAME =
      Pattern.compile("([a-zA-Z0-9_-]+\\.)+[a-zA-Z0-9_-]+"); // an extension is required

  private static final Pattern SAFE_MODULE_PATH =
      Pattern.compile("/(" + SAFE_DIRECTORY + ")/$");

  static final Pattern SAFE_DIRECTORY_PATH =
      Pattern.compile("/(" + SAFE_DIRECTORY + "/)+$");

  /* visible for testing */
  static final Pattern SAFE_FILE_PATH =
      Pattern.compile("/(" + SAFE_DIRECTORY + "/)+" + SAFE_FILENAME + "$");

  private static final Pattern SAFE_CALLBACK =
      Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*\\.)*[a-zA-Z_][a-zA-Z0-9_]*");

  static final Pattern STRONG_NAME = Pattern.compile("[\\dA-F]{32}");

  private static final Pattern CACHE_JS_FILE = Pattern.compile("/(" + STRONG_NAME + ").cache.js$");

  private static final MimeTypes MIME_TYPES = new MimeTypes();

  private final SourceHandler handler;
  private final OutboxTable outboxes;
  private final JobRunner runner;
  private final ProgressTable progressTable;

  private final String bindAddress;
  private final int port;

  private Server server;

  WebServer(SourceHandler handler, OutboxTable outboxes, JobRunner runner,
      ProgressTable progressTable, String bindAddress, int port) {
    this.handler = handler;
    this.outboxes = outboxes;
    this.runner = runner;
    this.progressTable = progressTable;
    this.bindAddress = bindAddress;
    this.port = port;
  }

  void start(final TreeLogger logger) throws UnableToCompleteException {

    SelectChannelConnector connector = new SelectChannelConnector();
    connector.setHost(bindAddress);
    connector.setPort(port);
    connector.setReuseAddress(false);
    connector.setSoLingerTime(0);

    Server newServer = new Server();
    newServer.addConnector(connector);

    ServletContextHandler newHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
    newHandler.setContextPath("/");
    newHandler.addServlet(new ServletHolder(new HttpServlet() {
      @Override
      protected void doGet(HttpServletRequest request, HttpServletResponse response)
          throws ServletException, IOException {
        handleRequest(request.getPathInfo(), request, response, logger);
      }
    }), "/*");
    newHandler.addFilter(GzipFilter.class, "/*", EnumSet.allOf(DispatcherType.class));
    newServer.setHandler(newHandler);
    try {
      newServer.start();
    } catch (Exception e) {
      logger.log(TreeLogger.ERROR, "cannot start web server", e);
      throw new UnableToCompleteException();
    }
    this.server = newServer;
  }

  public int getPort() {
    return port;
  }

  public void stop() throws Exception {
    server.stop();
    server = null;
  }

  /**
   * Returns the location of the compiler output. (Changes after every recompile.)
   */
  public File getCurrentWarDir(String moduleName) {
    return outboxes.findByModuleName(moduleName).getWarDir();
  }

  private void handleRequest(String target, HttpServletRequest request,
      HttpServletResponse response, TreeLogger logger)
      throws IOException {

    if (request.getMethod().equalsIgnoreCase("get")) {
      doGet(target, request, response, logger);
    }
  }

  private void doGet(String target, HttpServletRequest request, HttpServletResponse response,
      TreeLogger parentLogger)
      throws IOException {

    TreeLogger logger = parentLogger.branch(Type.TRACE, "GET " + target);

    if (!target.endsWith(".cache.js")) {
      // Make sure IE9 doesn't cache any pages.
      // (Nearly all pages may change on server restart.)
      PageUtil.setNoCacheHeaders(response);
    }

    if (target.equals("/")) {
      setHandled(request);
      JsonObject config = outboxes.getConfig();
      PageUtil.sendJsonAndHtml("config", config, "frontpage.html", response, logger);
      return;
    }

    if (target.equals("/dev_mode_on.js")) {
      setHandled(request);
      JsonObject config = outboxes.getConfig();
      PageUtil
          .sendJsonAndJavaScript("__gwt_codeserver_config", config, "dev_mode_on.js", response,
              logger);
      return;
    }

    // Recompile on request from the bookmarklet.
    // This is a GET because a bookmarklet can call it from a different origin (JSONP).
    if (target.startsWith("/recompile/")) {
      setHandled(request);
      String moduleName = target.substring("/recompile/".length());
      Outbox outbox = outboxes.findByModuleName(moduleName);
      if (outbox == null) {
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
        logger.log(TreeLogger.WARN, "not found: " + target);
        return;
      }

      // We are passing properties from an unauthenticated GET request directly to the compiler.
      // This should be safe, but only because these are binding properties. For each binding
      // property, you can only choose from a set of predefined values. So all an attacker can do is
      // cause a spurious recompile, resulting in an unexpected permutation being loaded later.
      //
      // It would be unsafe to allow a configuration property to be changed.
      Job job = new Job(outbox.getModuleName(), getBindingProperties(request), logger);
      runner.submit(job);
      boolean ok = job.waitForResult().isOk();

      JsonObject config = outboxes.getConfig();
      config.put("status", ok ? "ok" : "failed");
      sendJsonResult(config, request, response, logger);
      return;
    }

    if (target.startsWith("/log/")) {
      setHandled(request);
      String moduleName = target.substring("/log/".length());
      File file = outboxes.findByModuleName(moduleName).getCompileLog();
      sendLogPage(moduleName, file, response);
      return;
    }

    if (target.equals("/favicon.ico")) {
      InputStream faviconStream = getClass().getResourceAsStream("favicon.ico");
      if (faviconStream != null) {
        setHandled(request);
        // IE8 will not load the favicon in an img tag with the default MIME type,
        // so use "image/x-icon" instead.
        PageUtil.sendStream("image/x-icon", faviconStream, response);
      }
      return;
    }

    if (target.equals("/policies/")) {
      setHandled(request);
      sendPolicyIndex(response);
      return;
    }

    if (target.equals("/progress")) {
      setHandled(request);
      // TODO: return a list of progress objects here, one for each job.
      Progress progress = progressTable.getProgressForCompilingJob();

      JsonObject json;
      if (progress == null) {
        json = new JsonObject();
        json.put("status", "idle");
      } else {
        json = progress.toJsonObject();
      }
      sendJsonResult(json, request, response, logger);
      return;
    }

    Matcher matcher = SAFE_MODULE_PATH.matcher(target);
    if (matcher.matches()) {
      setHandled(request);
      sendModulePage(matcher.group(1), response, logger);
      return;
    }

    matcher = SAFE_DIRECTORY_PATH.matcher(target);
    if (matcher.matches() && handler.isSourceMapRequest(target)) {
      setHandled(request);
      handler.handle(target, request, response, logger);
      return;
    }

    matcher = SAFE_FILE_PATH.matcher(target);
    if (matcher.matches()) {
      setHandled(request);
      if (handler.isSourceMapRequest(target)) {
        handler.handle(target, request, response, logger);
        return;
      }
      if (target.startsWith("/policies/")) {
        sendPolicyFile(target, response, logger);
        return;
      }
      sendOutputFile(target, request, response, logger);
      return;
    }

    logger.log(TreeLogger.WARN, "ignored get request: " + target);
  }

  private void sendOutputFile(String target, HttpServletRequest request,
      HttpServletResponse response, TreeLogger logger) throws IOException {

    int secondSlash = target.indexOf('/', 1);
    String moduleName = target.substring(1, secondSlash);
    Outbox outbox = outboxes.findByModuleName(moduleName);

    File file = outbox.getOutputFile(target);
    if (!file.isFile()) {
      // perhaps it's compressed
      file = outbox.getOutputFile(target + ".gz");
      if (!file.isFile()) {
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
        logger.log(TreeLogger.WARN, "not found: " + file.toString());
        return;
      }
      if (!request.getHeader("Accept-Encoding").contains("gzip")) {
        response.sendError(HttpServletResponse.SC_NOT_IMPLEMENTED);
        logger.log(TreeLogger.WARN, "client doesn't accept gzip; bailing");
        return;
      }
      response.setHeader("Content-Encoding", "gzip");
    }

    Matcher match = CACHE_JS_FILE.matcher(target);
    if (match.matches()) {
      String strongName = match.group(1);
      String template = SourceHandler.sourceMapLocationTemplate(moduleName);
      String sourceMapUrl = template.replace("__HASH__", strongName);
      response.setHeader("X-SourceMap", sourceMapUrl);
    }
    response.setHeader("Access-Control-Allow-Origin", "*");
    String mimeType = guessMimeType(target);
    PageUtil.sendFile(mimeType, file, response);
  }

  private void sendModulePage(String moduleName, HttpServletResponse response, TreeLogger logger)
      throws IOException {
    Outbox module = outboxes.findByModuleName(moduleName);
    if (module == null) {
      response.sendError(HttpServletResponse.SC_NOT_FOUND);
      logger.log(TreeLogger.WARN, "module not found: " + moduleName);
      return;
    }
    PageUtil
        .sendJsonAndHtml("config", module.getTemplateVariables(), "modulepage.html", response,
            logger);
  }

  private void sendPolicyIndex(HttpServletResponse response) throws IOException {

    response.setContentType("text/html");

    HtmlWriter out = new HtmlWriter(response.getWriter());

    out.startTag("html").nl();
    out.startTag("head").nl();
    out.startTag("title").text("Policy Files").endTag("title").nl();
    out.endTag("head");
    out.startTag("body");

    out.startTag("h1").text("Policy Files").endTag("h1").nl();

    for (String moduleName : outboxes.getModuleNames()) {
      Outbox module = outboxes.findByModuleName(moduleName);
      File manifest = module.getExtraFile("rpcPolicyManifest/manifest.txt");
      if (manifest.isFile()) {
        out.startTag("h2").text(moduleName).endTag("h2").nl();

        out.startTag("table").nl();
        String text = PageUtil.loadFile(manifest);
        for (String line : text.split("\n")) {
          line = line.trim();
          if (line.isEmpty() || line.startsWith("#")) {
            continue;
          }
          String[] fields = line.split(", ");
          if (fields.length < 2) {
            continue;
          }

          String serviceName = fields[0];
          String policyFileName = fields[1];

          String serviceUrl = SourceHandler.SOURCEMAP_PATH + moduleName + "/" +
              serviceName.replace('.', '/') + ".java";
          String policyUrl = "/policies/" + policyFileName;

          out.startTag("tr");

          out.startTag("td");
          out.startTag("a", "href=", serviceUrl).text(serviceName).endTag("a");
          out.endTag("td");

          out.startTag("td");
          out.startTag("a", "href=", policyUrl).text(policyFileName).endTag("a");
          out.endTag("td");

          out.endTag("tr").nl();
        }
        out.endTag("table").nl();
      }
    }

    out.endTag("body").nl();
    out.endTag("html").nl();
  }

  private void sendPolicyFile(String target, HttpServletResponse response, TreeLogger logger)
      throws IOException {
    int secondSlash = target.indexOf('/', 1);
    if (secondSlash < 1) {
      response.sendError(HttpServletResponse.SC_NOT_FOUND);
      return;
    }
    String rest = target.substring(secondSlash + 1);
    if (rest.contains("/") || !rest.endsWith(".gwt.rpc")) {
      response.sendError(HttpServletResponse.SC_NOT_FOUND);
      return;
    }

    for (String moduleName : outboxes.getModuleNames()) {
      Outbox module = outboxes.findByModuleName(moduleName);
      File policy = module.getOutputFile(moduleName + "/" + rest);
      if (policy.isFile()) {
        PageUtil.sendFile("text/plain", policy, response);
        return;
      }
    }

    logger.log(TreeLogger.Type.WARN, "policy file not found: " + rest);
    response.sendError(HttpServletResponse.SC_NOT_FOUND);
  }

  private void sendJsonResult(JsonObject json, HttpServletRequest request,
      HttpServletResponse response, TreeLogger logger) throws IOException {

    response.setStatus(HttpServletResponse.SC_OK);
    response.setHeader("Cache-control", "no-cache");
    PrintWriter out = response.getWriter();

    String callbackExpression = request.getParameter("_callback");
    if (callbackExpression == null) {
      // AJAX
      response.setContentType("application/json");
      json.write(out);
    } else {
      // JSONP
      response.setContentType("application/javascript");
      if (SAFE_CALLBACK.matcher(callbackExpression).matches()) {
        out.print(callbackExpression + "(");
        json.write(out);
        out.println(");");
      } else {
        logger.log(TreeLogger.ERROR, "invalid callback: " + callbackExpression);
        // Notice that we cannot execute the callback
        out.print("alert('invalid callback parameter');\n");
        json.write(out);
      }
    }
  }

  /**
   * Sends the log file as html with errors highlighted in red.
   */
  private void sendLogPage(String moduleName, File file, HttpServletResponse response)
       throws IOException {
    BufferedReader reader = new BufferedReader(new FileReader(file));

    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType("text/html");
    response.setHeader("Content-Style-Type", "text/css");

    HtmlWriter out = new HtmlWriter(response.getWriter());
    out.startTag("html").nl();
    out.startTag("head").nl();
    out.startTag("title").text(moduleName + " compile log").endTag("title").nl();
    out.startTag("style").nl();
    out.text(".error { color: red; font-weight: bold; }").nl();
    out.endTag("style").nl();
    out.endTag("head").nl();
    out.startTag("body").nl();
    sendLogAsHtml(reader, out);
    out.endTag("body").nl();
    out.endTag("html").nl();
  }

  private static final Pattern ERROR_PATTERN = Pattern.compile("\\[ERROR\\]");

  /**
   * Copies in to out line by line, escaping each line for html characters and highlighting
   * error lines. Closes <code>in</code> when done.
   */
  private static void sendLogAsHtml(BufferedReader in, HtmlWriter out) throws IOException {
    try {
      out.startTag("pre").nl();
      String line = in.readLine();
      while (line != null) {
        Matcher m = ERROR_PATTERN.matcher(line);
        boolean error = m.find();
        if (error) {
          out.startTag("span", "class=", "error");
        }
        out.text(line);
        if (error) {
          out.endTag("span");
        }
        out.nl(); // the readLine doesn't include the newline.
        line = in.readLine();
      }
      out.endTag("pre").nl();
    } finally {
      in.close();
    }
  }

  /* visible for testing */
  static String guessMimeType(String filename) {
    Buffer mimeType = MIME_TYPES.getMimeByExtension(filename);
    return mimeType != null ? mimeType.toString() : "";
  }

  /**
   * Returns the binding properties from the web page where dev mode is being used. (As passed in
   * by dev_mode_on.js in a JSONP request to "/recompile".)
   */
  private Map<String, String> getBindingProperties(HttpServletRequest request) {
    Map<String, String> result = new HashMap<String, String>();
    for (Object key : request.getParameterMap().keySet()) {
      String propName = (String) key;
      if (!propName.equals("_callback")) {
        result.put(propName, request.getParameter(propName));
      }
    }
    return result;
  }

  private static void setHandled(HttpServletRequest request) {
    Request baseRequest = (request instanceof Request) ? (Request) request :
        AbstractHttpConnection.getCurrentConnection().getRequest();
    baseRequest.setHandled(true);
  }
}
